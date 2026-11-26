#pragma once
// Cadence gate for TV mode (capture card -> GPU enhance -> 4K display).
//
// The console scans out a fixed 60 Hz grid no matter what the game renders, so 30 fps content
// arrives as pairs of identical frames and 40-59 fps content arrives with uneven holds. Encoding
// that costs almost nothing, but presenting it unchanged means the panel holds the same picture for
// two or three refreshes: the judder the Quest pipeline already fixes.
//
// This is the same signal path the Quest pipeline already proved out:
//   * luma MAD over an 8x4 sampled grid, because a duplicate inside a compressed stream differs by
//     encoder noise that a strict picture hash always calls "different" (LATENCY notes section 8);
//   * a starvation guard so a genuinely static scene cannot freeze the output;
//   * an EMA plus hysteresis so the frame-generation mode cannot flap.
//
// The one deliberate difference is that the thresholds are *display relative* instead of the Quest's
// fixed 34/46 fps band. A TV mode has to serve 60 Hz and 120 Hz panels: 30 fps content should double
// on a 60 Hz display and triple on a 120 Hz one, while 60 fps content must not double on a 120 Hz
// display unless there is real headroom. Expressing the decision as refreshHz / contentFps does that
// with one rule.
//
// Header-only and free of D3D/Win32 so the gate can be unit tested with synthetic frame sequences.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace gta60::pipeline {

class TvCadenceGate {
public:
    struct Config {
        // Luma MAD below which a frame counts as a repeat of the previous one. Measured on real
        // content: repeats cluster at 0.0-0.4 and new frames at 1.5-100, so 1.0 sits in the valley.
        // 0 disables duplicate rejection (present everything).
        float dedupMad = 1.0f;
        // Never hold the same picture longer than this, whatever the MAD says.
        int64_t starveMs = 50;
        // Frame generation turns on when the display needs this many times the content rate, and off
        // only when that ratio has collapsed again. The gap is the dead band.
        double enableRatio = 1.6;
        double disableRatio = 1.25;
        int enableStreak = 12;
        int disableStreak = 40;
        // The enhancement pipeline carries at most three outputs per source frame (60 or 90 fps
        // allocation), so a request for 4x has to be clamped.
        uint32_t maxMultiplier = 3;
        bool allowFrameGen = true;
        // Content rates outside this range are treated as noise and ignored.
        double minContentFps = 5.0;
        double maxContentFps = 200.0;
    };

    struct Decision {
        bool keep = true;      // false = repeat frame, do not touch the GPU
        bool first = true;     // first frame seen (no MAD yet)
        double madY = 0.0;
        bool madValid = false;
        double contentFps = 60.0;  // smoothed copy of the rate the gate is acting on
        bool fgActive = false;
        uint32_t multiplier = 1;   // 1 = enhance only, N = [N-1 interpolated, real]
        uint32_t droppedRun = 0;   // repeats skipped immediately before this decision
        const char* reason = "first";
    };

    TvCadenceGate() = default;

    void configure(const Config& cfg) { cfg_ = cfg; }

    void setRefresh(uint32_t refreshHz) {
        refreshHz_ = refreshHz ? refreshHz : 60;
        contentFps_ = static_cast<double>(refreshHz_);
    }

    void reset() {
        havePrev_ = false;
        prevSig_.clear();
        haveForward_ = false;
        lastForwardMs_ = 0;
        contentFps_ = static_cast<double>(refreshHz_);
        fgActive_ = false;
        lowStreak_ = highStreak_ = 0;
        droppedRun_ = 0;
        kept_ = dropped_ = 0;
        fgFlips_ = 0;
    }

    // `nowMs` is a monotonic millisecond clock (steady_clock based). `yPlane`/`stride` describe the
    // captured NV12 luma plane.
    Decision observe(const uint8_t* yPlane, uint32_t width, uint32_t height, uint32_t stride, int64_t nowMs) {
        Decision d;
        d.first = !haveForward_;
        d.droppedRun = droppedRun_;

        double mad = 0.0;
        bool valid = false;
        constexpr uint32_t stepX = 8, stepY = 4;
        if (yPlane && width && height && stride) {
            const uint32_t sw = (width + stepX - 1) / stepX;
            const uint32_t sh = (height + stepY - 1) / stepY;
            curSig_.resize(static_cast<size_t>(sw) * sh);
            size_t idx = 0;
            for (uint32_t y = 0; y < sh; ++y) {
                const uint8_t* row = yPlane + static_cast<size_t>(y * stepY) * stride;
                for (uint32_t x = 0; x < sw; ++x) curSig_[idx++] = row[x * stepX];
            }
            if (prevSig_.size() == curSig_.size()) {
                uint64_t sad = 0;
                for (size_t i = 0; i < curSig_.size(); ++i) {
                    const int a = int(curSig_[i]), b = int(prevSig_[i]);
                    sad += static_cast<uint64_t>(a > b ? a - b : b - a);
                }
                mad = static_cast<double>(sad) / static_cast<double>(curSig_.size());
                valid = true;
            }
            prevSig_.swap(curSig_);
            havePrev_ = true;
        }
        d.madY = mad;
        d.madValid = valid;

        const bool starving = haveForward_ && (nowMs - lastForwardMs_) > cfg_.starveMs;
        const bool duplicate = cfg_.dedupMad > 0.0f && valid && mad < cfg_.dedupMad;
        if (!d.first && duplicate && !starving) {
            ++droppedRun_;
            ++dropped_;
            d.keep = false;
            d.reason = "repeat";
            d.contentFps = contentFps_;
            d.fgActive = fgActive_;
            d.multiplier = fgActive_ ? multiplierFor(contentFps_) : 1u;
            return d;
        }

        // A kept frame: update the content-rate estimate from the interval between forwarded frames,
        // which is exactly the game's real render cadence.
        if (haveForward_ && nowMs > lastForwardMs_) {
            const double fps = 1000.0 / static_cast<double>(nowMs - lastForwardMs_);
            if (fps >= cfg_.minContentFps && fps <= cfg_.maxContentFps) {
                contentFps_ = contentFps_ * 0.90 + fps * 0.10;
            }
        }
        lastForwardMs_ = nowMs;
        haveForward_ = true;
        droppedRun_ = 0;
        ++kept_;

        updateFrameGen();
        d.keep = true;
        d.contentFps = contentFps_;
        d.fgActive = fgActive_;
        d.multiplier = fgActive_ ? multiplierFor(contentFps_) : 1u;
        d.reason = d.first ? "first" : (starving && duplicate ? "starve" : "new");
        return d;
    }

    double contentFps() const { return contentFps_; }
    bool frameGenActive() const { return fgActive_; }
    uint32_t multiplier() const { return fgActive_ ? multiplierFor(contentFps_) : 1u; }
    uint64_t keptFrames() const { return kept_; }
    uint64_t droppedFrames() const { return dropped_; }
    uint64_t fgFlips() const { return fgFlips_; }
    uint32_t refreshHz() const { return refreshHz_; }

private:
    uint32_t multiplierFor(double fps) const {
        const double ratio = static_cast<double>(refreshHz_) / std::max(fps, cfg_.minContentFps);
        const auto m = static_cast<long long>(std::llround(ratio));
        return static_cast<uint32_t>(std::clamp<long long>(m, 1, static_cast<long long>(cfg_.maxMultiplier)));
    }

    void updateFrameGen() {
        const bool prev = fgActive_;
        if (!cfg_.allowFrameGen) {
            fgActive_ = false;
        } else {
            const double ratio = static_cast<double>(refreshHz_) / std::max(contentFps_, cfg_.minContentFps);
            if (ratio >= cfg_.enableRatio) {
                ++lowStreak_;
                highStreak_ = 0;
                if (lowStreak_ >= cfg_.enableStreak) fgActive_ = true;
            } else if (ratio <= cfg_.disableRatio) {
                ++highStreak_;
                lowStreak_ = 0;
                if (highStreak_ >= cfg_.disableStreak) fgActive_ = false;
            } else {
                // Dead band: neither sustained condition, keep the current mode.
                lowStreak_ = 0;
                highStreak_ = 0;
            }
        }
        if (fgActive_ != prev) ++fgFlips_;
    }

    Config cfg_{};
    uint32_t refreshHz_ = 60;
    bool havePrev_ = false;
    std::vector<uint8_t> prevSig_;
    std::vector<uint8_t> curSig_;
    bool haveForward_ = false;
    int64_t lastForwardMs_ = 0;
    double contentFps_ = 60.0;
    bool fgActive_ = false;
    int lowStreak_ = 0;
    int highStreak_ = 0;
    uint32_t droppedRun_ = 0;
    uint64_t kept_ = 0;
    uint64_t dropped_ = 0;
    uint64_t fgFlips_ = 0;
};

} // namespace gta60::pipeline

#pragma once

#include "V4l2Capture.hpp"

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace gta60_linux {

struct DuplicateDetector {
    static constexpr uint32_t kStep = 32;
    std::vector<uint8_t> prevGrid;
    std::vector<uint8_t> curGrid;
    bool hasPrev = false;

    uint64_t totalFrames = 0;
    uint64_t dupFrames = 0;
    uint64_t dupConsec = 0;
    uint64_t run1 = 0;
    uint64_t run2 = 0;
    uint64_t run3p = 0;
    double madSum = 0.0;
    std::chrono::steady_clock::time_point winStart = std::chrono::steady_clock::now();

    void resetAll() {
        hasPrev = false;
        dupConsec = 0;
        resetStats();
    }

    void resetStats() {
        totalFrames = 0;
        dupFrames = 0;
        run1 = 0;
        run2 = 0;
        run3p = 0;
        madSum = 0.0;
        winStart = std::chrono::steady_clock::now();
    }

    // Returns true if this frame matches the previous captured frame within threshold MAD.
    bool observe(const CapturedFrame& cf, double threshold, double* outMad = nullptr) {
        const uint32_t cols = (cf.width + kStep - 1) / kStep;
        const uint32_t rows = (cf.height + kStep - 1) / kStep;
        curGrid.resize(static_cast<size_t>(cols) * rows);

        size_t idx = 0;
        if (cf.format == CapturePixelFormat::NV12) {
            for (uint32_t y = 0; y < cf.height; y += kStep) {
                const uint8_t* row = cf.data + static_cast<size_t>(y) * cf.stride;
                for (uint32_t x = 0; x < cf.width; x += kStep) {
                    curGrid[idx++] = row[x];
                }
            }
        } else {
            for (uint32_t y = 0; y < cf.height; y += kStep) {
                const uint8_t* row = cf.data + static_cast<size_t>(y) * cf.stride;
                for (uint32_t x = 0; x < cf.width; x += kStep) {
                    curGrid[idx++] = row[static_cast<size_t>(x) * 2];
                }
            }
        }

        double mad = 255.0;
        bool isDup = false;
        if (hasPrev && prevGrid.size() == curGrid.size() && !curGrid.empty()) {
            uint64_t sumAbs = 0;
            for (size_t i = 0; i < curGrid.size(); ++i) {
                const int d = static_cast<int>(curGrid[i]) - static_cast<int>(prevGrid[i]);
                sumAbs += static_cast<uint64_t>(d < 0 ? -d : d);
            }
            mad = static_cast<double>(sumAbs) / static_cast<double>(curGrid.size());
            isDup = (mad <= threshold);
        }

        prevGrid.swap(curGrid);
        hasPrev = true;

        ++totalFrames;
        madSum += mad;
        if (isDup) {
            ++dupFrames;
            ++dupConsec;
        } else if (dupConsec > 0) {
            if (dupConsec == 1) ++run1;
            else if (dupConsec == 2) ++run2;
            else ++run3p;
            dupConsec = 0;
        }

        if (outMad) *outMad = mad;
        return isDup;
    }

    // Never tag consecutive duplicates (dupConsec >= 2) with kFlagDuplicate:
    // 30fps cadence always has dupConsec == 1, whereas static scenes/menus have
    // dupConsec >= 2 and tagging them all would freeze Quest FG60 presentation.
    bool shouldTagDuplicate(bool isDup, bool tagDuplicatesEnabled) const {
        return isDup && tagDuplicatesEnabled && (dupConsec == 1);
    }

    void maybeLog(uint64_t droppedTotal) {
        const auto now = std::chrono::steady_clock::now();
        const double dt = std::chrono::duration<double>(now - winStart).count();
        if (dt < 2.0 || totalFrames == 0) return;

        const double inFps = static_cast<double>(totalFrames) / dt;
        const double uniqFps = static_cast<double>(totalFrames - dupFrames) / dt;
        const double dupPct = 100.0 * static_cast<double>(dupFrames) / static_cast<double>(totalFrames);
        const double avgMad = madSum / static_cast<double>(totalFrames);
        std::printf("[perf] in=%.1f uniq=%.1f dup=%.1f%% runs=%llu/%llu/%llu mad=%.2f drop=%llu\n",
                    inFps, uniqFps, dupPct,
                    static_cast<unsigned long long>(run1),
                    static_cast<unsigned long long>(run2),
                    static_cast<unsigned long long>(run3p),
                    avgMad,
                    static_cast<unsigned long long>(droppedTotal));
        std::fflush(stdout);
        resetStats();
    }
};

} // namespace gta60_linux

#pragma once

#include <chrono>

namespace gta60_linux {

/**
 * Detects a dead capture/encode pipeline and decides when to recover it.
 *
 * Two independent stalls are tracked while a Quest client is connected:
 *
 *  - frame stall:  V4L2 stopped delivering buffers. This is what happens when the Elgato
 *                  re-enumerates on USB (cable bump, hub glitch, source switch): the ALSA
 *                  audio thread keeps running and the GT60 socket stays up, so the headset
 *                  shows a black panel with sound and nothing ever recovers on its own.
 *  - encode stall: frames arrive but VA-API produces no access units (encoder wedged after
 *                  a driver reset or a failed reconfiguration).
 *
 * Recovery is rate limited by `cooldown` so a permanently dead source does not spin the
 * process, and `maxConsecutiveFailures` lets the caller escalate to a full restart.
 */
class StreamWatchdog {
public:
    using Clock = std::chrono::steady_clock;
    using Duration = Clock::duration;

    enum class Action {
        None,
        RecoverCapture,
        RecoverEncoder,
    };

    struct Config {
        Duration frameStall = std::chrono::milliseconds(2000);
        Duration encodeStall = std::chrono::milliseconds(2000);
        Duration cooldown = std::chrono::milliseconds(2000);
        int maxConsecutiveFailures = 3;
    };

    StreamWatchdog() : StreamWatchdog(Config{}) {}
    explicit StreamWatchdog(Config cfg) : cfg_(cfg) {}

    /** Clears all state; safe to call at startup or whenever the client goes away. */
    void reset(Clock::time_point now) {
        lastFrame_ = now;
        lastPacket_ = now;
        lastRecovery_ = now - cfg_.cooldown;
        frameFailures_ = 0;
        encodeFailures_ = 0;
        lastStallMs_ = 0;
    }

    /** A V4L2 frame arrived (duplicates count: a paused game still proves the pipe is alive). */
    void noteFrame(Clock::time_point now) {
        lastFrame_ = now;
        frameFailures_ = 0;
    }

    /** An encoded access unit was produced. */
    void notePacket(Clock::time_point now) {
        lastPacket_ = now;
        encodeFailures_ = 0;
    }

    /** Poll the pipeline state; returns the recovery the caller should perform, if any. */
    Action poll(Clock::time_point now, bool clientConnected) {
        if (!clientConnected) {
            // Idle with no viewer: a silent card is expected, not a fault.
            reset(now);
            return Action::None;
        }
        if (now - lastRecovery_ < cfg_.cooldown) {
            return Action::None;
        }
        if (now - lastFrame_ >= cfg_.frameStall) {
            lastRecovery_ = now;
            lastStallMs_ = toMs(now - lastFrame_);
            ++frameFailures_;
            return Action::RecoverCapture;
        }
        if (now - lastPacket_ >= cfg_.encodeStall) {
            lastRecovery_ = now;
            lastStallMs_ = toMs(now - lastPacket_);
            ++encodeFailures_;
            return Action::RecoverEncoder;
        }
        return Action::None;
    }

    /** True once recovery has failed repeatedly: the caller should restart the process. */
    bool exhausted() const {
        return frameFailures_ > cfg_.maxConsecutiveFailures ||
               encodeFailures_ > cfg_.maxConsecutiveFailures;
    }

    int frameFailures() const { return frameFailures_; }
    int encodeFailures() const { return encodeFailures_; }
    long long lastStallMs() const { return lastStallMs_; }
    const Config& config() const { return cfg_; }

private:
    static long long toMs(Duration d) {
        return static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(d).count());
    }

    Config cfg_{};
    Clock::time_point lastFrame_{};
    Clock::time_point lastPacket_{};
    Clock::time_point lastRecovery_{};
    int frameFailures_ = 0;
    int encodeFailures_ = 0;
    long long lastStallMs_ = 0;
};

} // namespace gta60_linux

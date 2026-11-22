#include "AlsaCapture.hpp"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>

#ifdef HAVE_ALSA
#include <alsa/asoundlib.h>
#endif

namespace gta60_linux {

struct AlsaCapture::Impl {
    std::atomic<bool> running{false};
    std::thread worker;
};

AlsaCapture::AlsaCapture() : p_(std::make_unique<Impl>()) {}
AlsaCapture::~AlsaCapture() { stop(); }

bool AlsaCapture::start(const std::string& device, AudioCallback cb) {
    stop();
#ifndef HAVE_ALSA
    (void)device;
    (void)cb;
    std::fprintf(stderr, "[alsa] built without libasound2-dev; audio capture disabled\n");
    return false;
#else
    if (device.empty() || device == "none") return false;

    snd_pcm_t* pcm = nullptr;
    int err = snd_pcm_open(&pcm, device.c_str(), SND_PCM_STREAM_CAPTURE, 0);
    if (err < 0) {
        std::fprintf(stderr, "[alsa] cannot open '%s': %s\n", device.c_str(), snd_strerror(err));
        return false;
    }

    err = snd_pcm_set_params(pcm, SND_PCM_FORMAT_S16_LE, SND_PCM_ACCESS_RW_INTERLEAVED,
                             2, 48000, 1, 20000);
    if (err < 0) {
        std::fprintf(stderr, "[alsa] set_params failed on '%s': %s\n", device.c_str(), snd_strerror(err));
        snd_pcm_close(pcm);
        return false;
    }

    p_->running.store(true);
    p_->worker = std::thread([this, pcm, cb = std::move(cb)]() {
        constexpr snd_pcm_uframes_t kFrames = 480; // 10 ms at 48 kHz
        std::vector<int16_t> buf(kFrames * 2);
        const auto t0 = std::chrono::steady_clock::now();

        while (p_->running.load()) {
            snd_pcm_sframes_t n = snd_pcm_readi(pcm, buf.data(), kFrames);
            if (n < 0) {
                n = snd_pcm_recover(pcm, static_cast<int>(n), 1);
                if (n < 0) break;
                continue;
            }
            if (n > 0 && cb) {
                const uint32_t tsMs = static_cast<uint32_t>(
                    std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - t0).count());
                cb(buf.data(), static_cast<size_t>(n), tsMs);
            }
        }
        snd_pcm_close(pcm);
    });
    return true;
#endif
}

void AlsaCapture::stop() {
    p_->running.store(false);
    if (p_->worker.joinable()) p_->worker.join();
}

} // namespace gta60_linux

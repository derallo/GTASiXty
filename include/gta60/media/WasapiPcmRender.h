#pragma once
#include <cstdint>
#include <memory>
#include <string>

namespace gta60::media {

// Renders interleaved 48 kHz stereo S16 PCM to a WASAPI shared-mode render endpoint.
//
// TV mode takes audio from the capture card's UAC stream (HDMI audio arriving with the picture) and
// has to play it on the *television*, which is the GPU's HDMI audio endpoint rather than whatever
// the desktop is using. So this class can pick an endpoint by friendly-name substring, and falls
// back to the system default.
//
// Push driven on purpose: the capture loop already runs at frame rate, so it hands over whole PCM
// blocks and this writes only what fits in the WASAPI buffer. Overflow is dropped, never queued -
// a growing audio queue is lip-sync drift that no later stage can undo.
class WasapiPcmRender {
public:
    struct Stats {
        uint64_t framesPushed = 0;
        uint64_t framesDropped = 0;
        uint32_t paddingFrames = 0;
        uint32_t bufferFrames = 0;
    };

    WasapiPcmRender();
    ~WasapiPcmRender();
    WasapiPcmRender(const WasapiPcmRender&) = delete;
    WasapiPcmRender& operator=(const WasapiPcmRender&) = delete;

    // Empty substring selects the default render endpoint. `bufferMs` is the shared-mode buffer,
    // i.e. the standing audio latency.
    bool start(const std::wstring& deviceNameSubstring, uint32_t bufferMs = 30);
    void stop();
    bool active() const;

    // Delays playback by this much, once, by starting with silence. Used to line audio up with the
    // video path when the pipeline is the slower side. Call before the first push.
    void setStartupDelayMs(uint32_t ms);

    // Non-blocking. `frames` counts interleaved stereo sample pairs.
    void pushStereo16(const int16_t* interleaved, size_t frames);

    std::string deviceName() const;
    Stats consumeStats();

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60::media

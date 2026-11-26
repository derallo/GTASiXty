#pragma once
#include "gta60/media/MfH264Decoder.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace gta60::media {

struct CaptureFormat {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t fps = 0;
    // What the card is actually producing before we hand NV12 to the encoder.
    std::string subtype;
    std::wstring deviceName;
    bool audio = false;
};

// What a caller is willing to accept from the card. The defaults are the Quest capture-card mode's
// contract (uncompressed up to 1440p): raising them is opt-in because 4K60 NV12 is 746 MB/s, which
// only fits a 10 Gbit/s USB link, and because the Quest encode path is already over budget at 2160p.
struct CaptureLimits {
    uint32_t maxWidth = 2560;
    uint32_t maxHeight = 1440;
    uint32_t minWidth = 1280;
    uint32_t minHeight = 720;
    uint32_t maxFps = 120;
};

// One ReadSample. Video and audio arrive as separate samples, so a single read() fills at most one.
struct CaptureRead {
    bool video = false;
    bool audio = false;
    DecodedNv12Frame frame;
    std::vector<int16_t> pcm; // interleaved s16 stereo, 48 kHz
};

// Media Foundation capture of a UVC/HDMI card. Picks uncompressed NV12 or YUY2 (1440p60, then
// 1080p60) ahead of MJPEG, and always returns NV12. Does not open an Xbox session.
class MfCaptureSource {
public:
    MfCaptureSource();
    ~MfCaptureSource();
    MfCaptureSource(const MfCaptureSource&) = delete;
    MfCaptureSource& operator=(const MfCaptureSource&) = delete;

    // Empty substring selects the first video capture device.
    bool open(const std::wstring& deviceNameSubstring);
    // Same, with an explicit resolution envelope (TV mode raises it to 4K).
    bool open(const std::wstring& deviceNameSubstring, const CaptureLimits& limits);
    void close();

    const CaptureFormat& format() const;

    // Blocks until the device delivers a sample. Call this even when nobody is listening so the
    // driver queue cannot fill. Returns false when the device fails or the stream ends.
    bool read(CaptureRead& out);

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60::media

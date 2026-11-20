#pragma once
#include <cstdint>
#include <memory>
#include <vector>

namespace gta60::media {

struct DecodedNv12Frame {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stride = 0; // equals width (packed Y followed by packed UV)
    int64_t pts100ns = 0;
    std::vector<uint8_t> yPlane;
    std::vector<uint8_t> uvPlane;
};

class MfH264Decoder {
public:
    MfH264Decoder();
    ~MfH264Decoder();
    MfH264Decoder(const MfH264Decoder&) = delete;
    MfH264Decoder& operator=(const MfH264Decoder&) = delete;

    bool initialize(uint32_t initialWidth = 1920, uint32_t initialHeight = 1080);
    bool decode(const uint8_t* annexB, size_t size, int64_t pts100ns, DecodedNv12Frame& outFrame);
    void flush();
    void shutdown();

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60::media

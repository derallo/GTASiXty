#pragma once

#include "Gt60Server.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace gta60_linux {

enum class RateControlMode : uint8_t {
    Auto = 0,
    QVBR = 1,
    VBR = 2,
    CBR = 3,
    CQP = 4,
};

inline const char* rateControlModeName(RateControlMode m) {
    switch (m) {
        case RateControlMode::QVBR: return "QVBR";
        case RateControlMode::VBR: return "VBR";
        case RateControlMode::CBR: return "CBR";
        case RateControlMode::CQP: return "CQP";
        default: return "AUTO";
    }
}

struct EncoderConfig {
    uint32_t width = 2560;
    uint32_t height = 1440;
    uint32_t fps = 60;
    uint32_t bitrateMbps = 100;
    VideoCodec codec = VideoCodec::HEVC;
    bool tenBit = false;
    RateControlMode rcMode = RateControlMode::QVBR;
    int quality = 15; // QVBR global_quality or CQP qp (1..51)
    std::string drmDevice = "/dev/dri/renderD128";
};

// Wraps a raw AV1 sequence header OBU into an ISOBMFF AV1CodecConfigurationRecord ("av1C")
// expected by Android MediaCodec video/av01 csd-0. Idempotent if already wrapped (0x81 header).
std::vector<uint8_t> buildAv1CodecConfigRecord(const uint8_t* seqHdrObu, size_t len, bool tenBit);

class VaapiEncoder {
public:
    using EncodedCallback = std::function<void(const uint8_t* data, size_t size, bool isIdr)>;

    VaapiEncoder();
    ~VaapiEncoder();
    VaapiEncoder(const VaapiEncoder&) = delete;
    VaapiEncoder& operator=(const VaapiEncoder&) = delete;

    bool init(const EncoderConfig& cfg);
    bool init(uint32_t width, uint32_t height, uint32_t fps,
              uint32_t bitrateMbps, VideoCodec codec,
              const std::string& drmDevice = "/dev/dri/renderD128");
    void shutdown();

    const std::vector<uint8_t>& csd() const;
    const EncoderConfig& config() const;
    const char* activeRcModeName() const;

    void requestIdr();
    bool encodeNv12(const uint8_t* yPlane, int yStride,
                    const uint8_t* uvPlane, int uvStride,
                    int64_t pts, const EncodedCallback& cb);
    bool encodeYuyv(const uint8_t* yuyv, int yuyvStride,
                    int64_t pts, const EncodedCallback& cb);

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60_linux

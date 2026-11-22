#pragma once

#include "Gt60Server.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace gta60_linux {

class VaapiEncoder {
public:
    using EncodedCallback = std::function<void(const uint8_t* data, size_t size, bool isIdr)>;

    VaapiEncoder();
    ~VaapiEncoder();
    VaapiEncoder(const VaapiEncoder&) = delete;
    VaapiEncoder& operator=(const VaapiEncoder&) = delete;

    bool init(uint32_t width, uint32_t height, uint32_t fps,
              uint32_t bitrateMbps, VideoCodec codec,
              const std::string& drmDevice = "/dev/dri/renderD128");
    void shutdown();

    const std::vector<uint8_t>& csd() const;

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

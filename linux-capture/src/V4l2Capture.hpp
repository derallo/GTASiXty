#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace gta60_linux {

enum class CapturePixelFormat {
    NV12,
    YUYV,
};

struct CapturedFrame {
    const uint8_t* data = nullptr;
    size_t size = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stride = 0;
    CapturePixelFormat format = CapturePixelFormat::YUYV;
    uint32_t timestampMs = 0;
};

class V4l2Capture {
public:
    V4l2Capture();
    ~V4l2Capture();
    V4l2Capture(const V4l2Capture&) = delete;
    V4l2Capture& operator=(const V4l2Capture&) = delete;

    bool open(const std::string& device, uint32_t width, uint32_t height, uint32_t fps);
    void close();

    uint32_t width() const;
    uint32_t height() const;
    uint32_t fps() const;
    CapturePixelFormat format() const;

    // Blocks up to timeoutMs waiting for the next frame; returns false on timeout or error.
    bool grabFrame(int timeoutMs, const std::function<void(const CapturedFrame&)>& onFrame);

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60_linux

#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace gta60_linux {

class AlsaCapture {
public:
    using AudioCallback = std::function<void(const int16_t* interleavedStereo, size_t frames, uint32_t tsMs)>;

    AlsaCapture();
    ~AlsaCapture();
    AlsaCapture(const AlsaCapture&) = delete;
    AlsaCapture& operator=(const AlsaCapture&) = delete;

    bool start(const std::string& device, AudioCallback cb);
    void stop();

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60_linux

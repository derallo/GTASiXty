#pragma once
#include <cstdint>
#include <memory>

namespace gta60::media {

class OpusWasapiAudio {
public:
    OpusWasapiAudio();
    ~OpusWasapiAudio();
    OpusWasapiAudio(const OpusWasapiAudio&) = delete;
    OpusWasapiAudio& operator=(const OpusWasapiAudio&) = delete;

    bool start();
    void pushOpusPacket(const uint8_t* data, size_t size);
    void stop();

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60::media

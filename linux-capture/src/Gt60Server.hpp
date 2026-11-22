#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace gta60_linux {

enum class VideoCodec : uint8_t {
    H264 = 0,
    HEVC = 1,
};

inline const char* videoCodecName(VideoCodec c) {
    return c == VideoCodec::HEVC ? "HEVC" : "H264";
}

class Gt60Server {
public:
    static constexpr uint32_t kMagic = 0x30365447u; // "GT60"
    static constexpr uint8_t kTypeVideoConfig = 0x01;
    static constexpr uint8_t kTypeVideoAu = 0x02;
    static constexpr uint8_t kTypeAudioPcm = 0x03;
    static constexpr uint8_t kTypeClientIdr = 0x81;
    static constexpr uint8_t kTypeClientGamepad = 0x82;
    static constexpr uint8_t kTypeClientConfig = 0x83;
    static constexpr uint8_t kTypeClientRendered = 0x84;

    static constexpr uint8_t kFlagIdr = 0x01;
    static constexpr uint8_t kFlagGenerated = 0x02;
    static constexpr uint8_t kFlagDuplicate = 0x04; // experimental: captured frame matched previous frame

    struct Callbacks {
        std::function<void()> onClientConnected;
        std::function<void()> onIdrRequested;
    };

    Gt60Server();
    ~Gt60Server();
    Gt60Server(const Gt60Server&) = delete;
    Gt60Server& operator=(const Gt60Server&) = delete;

    bool start(uint16_t tcpPort, uint16_t udpDiscoveryPort,
               uint32_t width, uint32_t height, uint32_t fps,
               VideoCodec codec, std::vector<uint8_t> csd, Callbacks cbs = {});
    void stop();

    void setVideoConfig(uint32_t width, uint32_t height, uint32_t fps, const std::vector<uint8_t>& csd);
    void sendVideoAccessUnit(const uint8_t* data, size_t size, uint32_t timestampMs,
                             bool isIdr, bool isDuplicate = false);
    void sendAudioPcm(const int16_t* interleavedStereo, size_t frameCount, uint32_t timestampMs);

    bool hasClient() const;
    uint64_t droppedVideoFrames() const;

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60_linux

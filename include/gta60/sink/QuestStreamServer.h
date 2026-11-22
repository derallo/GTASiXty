#pragma once
#include "gta60/media/VideoCodec.h"
#include "gta60/xbox/Protocol.h"

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

namespace gta60::sink {

// Binary TCP framing protocol ("GT60") + UDP LAN discovery responder for the native Quest 3 APK.
// 16-byte header (little-endian):
//   uint32_t magic = 0x30365447 ('G','T','6','0')
//   uint8_t  type  (0x01=VideoConfig, 0x02=VideoAccessUnit, 0x03=PCM48kStereo,
//                   0x81=Client Request IDR, 0x82=Client GamepadFrame, 0x83=Client StreamConfig,
//                   0x84=Client Rendered echo)
//   uint8_t  flags (bit0=IDR, bit1=GeneratedFrame, bit2=Duplicate [experimental, linux-capture])
//   uint16_t meta  (VideoConfig only: stream fps)
//   uint32_t payloadSize
//   uint32_t timestampMs
//
// VIDEO_CONFIG payload (the only type whose layout changed for HEVC):
//   uint32_t width | uint32_t height | uint8_t codec (media::VideoCodec) | Annex-B csd bytes
// The csd blob carries SPS+PPS for H.264 and VPS+SPS+PPS for HEVC; the headset splits it into
// MediaCodec csd-0/csd-1/csd-2 itself, so the wire format stays codec-agnostic apart from the byte.
class QuestStreamServer {
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

    // width + height + codec byte, ahead of the parameter-set blob.
    static constexpr size_t kVideoConfigHeaderBytes = 9;

    struct ClientStreamConfig {
        uint32_t width = 2560;
        uint32_t height = 1440;
        uint16_t fps = 60;
        uint16_t bitrateMbps = 50;
    };
    static_assert(sizeof(ClientStreamConfig) == 12);

    struct Callbacks {
        std::function<void()> onClientConnected;
        std::function<void()> onIdrRequested;
        std::function<void(const xbox::GamepadFrame&)> onGamepad;
        std::function<void(const ClientStreamConfig&)> onConfigRequested;
        // Headset entered Direct mode (or the agent forwarded that). Drop the Xbox session.
        std::function<void()> onStopRequested;
    };

    QuestStreamServer();
    ~QuestStreamServer();
    QuestStreamServer(const QuestStreamServer&) = delete;
    QuestStreamServer& operator=(const QuestStreamServer&) = delete;

    bool start(uint16_t tcpPort, uint32_t width, uint32_t height, uint32_t fps,
               std::vector<uint8_t> initialSpsPps, media::VideoCodec codec = media::VideoCodec::H264,
               Callbacks cbs = {});
    void stop();

    void setVideoConfig(uint32_t width, uint32_t height, uint32_t fps, const std::vector<uint8_t>& spsPps);
    bool sendVideoAccessUnit(const uint8_t* data, size_t size, uint32_t timestampMs, bool isIdr, bool isGenerated);
    void sendAudioPcm(const int16_t* interleavedStereo, size_t frameCount, uint32_t timestampMs);

    struct ServerStats {
        double tcpSendAvgMs = 0.0;
        double tcpSendMaxMs = 0.0;
        uint64_t videoDropped = 0;
        size_t maxBacklog = 0;
        // Quest-reported present echo: socket-write -> Quest presents -> echo received.
        // Best-case value is the closest thing to a true PC->Quest->PC latency figure.
        double e2eMinMs = 0.0;
        double e2eAvgMs = 0.0;
        uint64_t e2eSamples = 0;
    };

    bool hasClient() const;
    size_t sendBacklogFrames() const;
    void setMaxVideoBacklog(size_t maxFrames);
    void setSendBufferBytes(int bytes);
    ServerStats consumeStats();
    uint16_t port() const;

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60::sink

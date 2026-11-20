#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gta60/xbox/Protocol.h"
#include "gta60/xbox/StreamApi.h"

namespace rtc {
class PeerConnection;
class DataChannel;
class Track;
} // namespace rtc

namespace gta60::xbox {

class Signaling {
public:
    virtual ~Signaling() = default;
    virtual std::string exchangeSdp(const std::string& offerSdp) = 0;
    virtual std::vector<IceCandidate> exchangeIce(const std::vector<IceCandidate>& local) = 0;
};

struct WebRtcCallbacks {
    std::function<void(std::vector<uint8_t>&& unit, uint32_t rtpTimestamp, int64_t arrival100ns)> video;
    std::function<void(const uint8_t* data, size_t size, uint32_t rtpTimestamp)> audio;
    std::function<void(const Vibration&)> vibration;
    std::function<void(uint32_t width, uint32_t height)> serverVideoSize;
    std::function<void(const std::string& reason)> ended;
};

struct WebRtcStats {
    uint64_t bytesReceived = 0;
    uint64_t videoBytes = 0;
    uint64_t videoUnits = 0, audioPackets = 0, keyframeRequests = 0;
    double rttMs = 0;
    bool handshakeDone = false;
};

class WebRtcSession {
public:
    WebRtcSession();
    ~WebRtcSession();
    WebRtcSession(const WebRtcSession&) = delete;
    WebRtcSession& operator=(const WebRtcSession&) = delete;

    void setCallbacks(WebRtcCallbacks callbacks) { callbacks_ = std::move(callbacks); }
    void setBindAddress(std::string address) { bindAddress_ = std::move(address); }

    bool start(Signaling& signaling, std::string* error, std::chrono::milliseconds timeout = std::chrono::seconds(20));
    bool ready() const { return handshakeDone_.load(); }
    bool waitReady(std::chrono::milliseconds timeout);

    void sendGamepad(const GamepadFrame& frame);
    void requestKeyframe();
    // Advertise the bandwidth we can accept (REMB, bits/sec). The console's encoder uses our
    // feedback to pick its target rate; if we never send REMB it falls back to a conservative
    // default (~15 Mbps), so telling it the LAN can carry far more is the only client-side lever
    // on the Xbox->PC video quality.
    void setVideoBitrateRequest(unsigned int bitsPerSecond) { requestedVideoBitrate_.store(bitsPerSecond); }
    void pumpBitrateRequest();
    void videoPresented(uint32_t rtpTimestamp, int64_t arrival, int64_t submitted, int64_t decoded, int64_t presented);
    void close();

    WebRtcStats stats() const;
    static std::string describeCodecs(const std::string& sdp);

private:
    void onMessageChannel(const std::string& text);
    void onInputChannel(const uint8_t* data, size_t size);
    bool sendMessageJson(const std::string& text);
    bool sendClientConfig();
    bool sendBinary(const std::shared_ptr<rtc::DataChannel>& channel, const uint8_t* data, size_t size);
    void sendFailure(const std::exception& error);
    double nowMs() const;

    WebRtcCallbacks callbacks_;
    struct CallbackGate {
        std::recursive_mutex mutex;
        std::atomic<bool> open{true};
    };
    std::shared_ptr<CallbackGate> gate_ = std::make_shared<CallbackGate>();
    std::atomic<bool> sendErrorLogged_{false}, feedbackSent_{false};
    std::atomic<uint32_t> lastFeedbackRtp_{0};
    std::shared_ptr<rtc::PeerConnection> pc_;
    std::shared_ptr<rtc::Track> audio_, video_;
    std::shared_ptr<rtc::DataChannel> chat_, control_, input_, message_;
    std::mutex mutex_;
    std::condition_variable changed_;
    std::atomic<bool> connected_{false}, failed_{false}, closed_{false}, handshakeDone_{false}, handshakeSent_{false};
    std::atomic<uint32_t> inputSequence_{0};
    std::atomic<uint32_t> requestedVideoBitrate_{0};
    std::chrono::steady_clock::time_point lastBitrateRequest_{};
    std::atomic<uint64_t> videoUnits_{0}, audioPackets_{0}, keyframeRequests_{0}, videoBytes_{0};
    std::chrono::steady_clock::time_point origin_ = std::chrono::steady_clock::now();
    std::string failure_;
    std::string bindAddress_;
};

} // namespace gta60::xbox

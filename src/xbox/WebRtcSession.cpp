#include "gta60/xbox/WebRtcSession.h"
#include "gta60/Log.h"

#include <windows.h>
#include <rtc/rtc.hpp>

#include <algorithm>
#include <cstring>
#include <nlohmann/json.hpp>
#include <random>
#include <sstream>

namespace gta60::xbox {
namespace {

using json = nlohmann::json;
using Clock = std::chrono::steady_clock;

int64_t host100ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count() / 100;
}

std::string uuid4() {
    static thread_local std::mt19937_64 rng{std::random_device{}()};
    uint8_t b[16];
    for (int i = 0; i < 16; i += 8) {
        const uint64_t r = rng();
        std::memcpy(b + i, &r, 8);
    }
    b[6] = static_cast<uint8_t>((b[6] & 0x0F) | 0x40);
    b[8] = static_cast<uint8_t>((b[8] & 0x3F) | 0x80);
    static const char* hex = "0123456789abcdef";
    std::string s;
    for (int i = 0; i < 16; ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) s += '-';
        s += hex[b[i] >> 4];
        s += hex[b[i] & 15];
    }
    return s;
}

std::string message(const std::string& target, const json& content) {
    return json{{"type", "Message"}, {"content", content.dump()}, {"id", uuid4()}, {"target", target}, {"cv", ""}}.dump();
}

void logOnce() {
    static std::once_flag once;
    std::call_once(once, [] {
        const bool debug = GetEnvironmentVariableA("GTA60_XBOX_RTC_DEBUG", nullptr, 0) > 0;
        rtc::InitLogger(debug ? rtc::LogLevel::Verbose : rtc::LogLevel::Warning,
                        [](rtc::LogLevel level, std::string text) {
                            if (level <= rtc::LogLevel::Error) log::warn("xbox-rtc", text);
                            else log::info("xbox-rtc", text);
                        });
    });
}

std::string withoutCandidates(const std::string& sdp) {
    std::istringstream in(sdp);
    std::string out, line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.rfind("a=candidate:", 0) == 0 || line == "a=end-of-candidates") continue;
        out += line;
        out += "\r\n";
    }
    return out;
}

} // namespace

WebRtcSession::WebRtcSession() { logOnce(); }
WebRtcSession::~WebRtcSession() { close(); }

double WebRtcSession::nowMs() const {
    return std::chrono::duration<double, std::milli>(Clock::now() - origin_).count();
}

std::string WebRtcSession::describeCodecs(const std::string& sdp) {
    std::istringstream in(sdp);
    std::string out, line;
    while (std::getline(in, line)) {
        if (line.rfind("a=rtpmap:", 0) == 0 || line.rfind("a=fmtp:", 0) == 0 || line.rfind("m=", 0) == 0) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            out += line + "\n";
        }
    }
    return out;
}

bool WebRtcSession::start(Signaling& signaling, std::string* error, std::chrono::milliseconds timeout) {
    const auto fail = [&](const std::string& why) {
        if (error) *error = why;
        log::warn("xbox", "webrtc start failed: " + why);
        return false;
    };

    rtc::Configuration config;
    config.disableAutoNegotiation = true;
    config.maxMessageSize = 256 * 1024;
    if (!bindAddress_.empty()) config.bindAddress = bindAddress_;
    pc_ = std::make_shared<rtc::PeerConnection>(config);

    pc_->onStateChange([this, gate = gate_](rtc::PeerConnection::State state) {
        if (!gate->open.load(std::memory_order_acquire)) return;
        log::info("xbox", std::string("peer connection state ") + std::to_string(static_cast<int>(state)));
        if (state == rtc::PeerConnection::State::Connected) {
            connected_ = true;
            if (pc_) {
                const auto la = pc_->localAddress().value_or("?");
                const auto ra = pc_->remoteAddress().value_or("?");
                log::info("xbox", "ICE active route: local=" + la + " <-> remote=" + ra);
            }
        }
        if (state == rtc::PeerConnection::State::Failed || state == rtc::PeerConnection::State::Closed ||
            state == rtc::PeerConnection::State::Disconnected) {
            const bool wasConnected = connected_.exchange(false);
            failed_ = true;
            if (wasConnected && !closed_ && callbacks_.ended) callbacks_.ended("connection lost");
        }
        changed_.notify_all();
    });
    pc_->onGatheringStateChange([this, gate = gate_](rtc::PeerConnection::GatheringState) {
        if (gate->open.load(std::memory_order_acquire)) changed_.notify_all();
    });
    pc_->onIceStateChange([](rtc::PeerConnection::IceState state) {
        log::info("xbox", "ICE state " + std::to_string(static_cast<int>(state)));
    });

    rtc::Description::Audio audio("0", rtc::Description::Direction::SendRecv);
    audio.addOpusCodec(111, std::string("minptime=10;useinbandfec=1;stereo=1"));
    audio_ = pc_->addTrack(audio);

    rtc::Description::Video video("1", rtc::Description::Direction::RecvOnly);
    video.addH264Codec(102, std::string("level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=4d002a;x-google-min-bitrate=15000;x-google-start-bitrate=20000;x-google-max-bitrate=25000"));
    video.addH264Codec(104, std::string("level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e02a;x-google-min-bitrate=15000;x-google-start-bitrate=20000;x-google-max-bitrate=25000"));
    video.addH264Codec(106, std::string("level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42002a;x-google-min-bitrate=15000;x-google-start-bitrate=20000;x-google-max-bitrate=25000"));
    video_ = pc_->addTrack(video);

    auto depacketizer = std::make_shared<rtc::H264RtpDepacketizer>(rtc::NalUnit::Separator::LongStartSequence);
    video_->setMediaHandler(depacketizer);
    video_->chainMediaHandler(std::make_shared<rtc::RtcpReceivingSession>());
    video_->onFrame([this, gate = gate_](rtc::binary data, rtc::FrameInfo info) {
        if (!gate->open.load(std::memory_order_acquire)) return;
        ++videoUnits_;
        videoBytes_ += data.size();
        if (!callbacks_.video) return;
        std::vector<uint8_t> unit(data.size());
        std::memcpy(unit.data(), data.data(), data.size());
        callbacks_.video(std::move(unit), info.timestamp, host100ns());
    });

    audio_->setMediaHandler(std::make_shared<rtc::OpusRtpDepacketizer>());
    audio_->chainMediaHandler(std::make_shared<rtc::RtcpReceivingSession>());
    audio_->onFrame([this, gate = gate_](rtc::binary data, rtc::FrameInfo info) {
        if (!gate->open.load(std::memory_order_acquire)) return;
        ++audioPackets_;
        if (callbacks_.audio) {
            callbacks_.audio(reinterpret_cast<const uint8_t*>(data.data()), data.size(), info.timestamp);
        }
    });

    const auto channel = [&](const char* label, const char* protocol) {
        rtc::DataChannelInit init;
        init.protocol = protocol;
        return pc_->createDataChannel(label, init);
    };
    chat_ = channel("chat", "chatV1");
    control_ = channel("control", "controlV1");
    input_ = channel("input", "1.0");
    message_ = channel("message", "messageV1");

    const auto maybeHandshake = [this, gate = gate_] {
        if (!gate->open.load(std::memory_order_acquire)) return;
        std::shared_ptr<rtc::DataChannel> ch, ctrl, inp, msg;
        {
            std::lock_guard lk(gate->mutex);
            ch = chat_;
            ctrl = control_;
            inp = input_;
            msg = message_;
        }
        if (!msg || !ctrl || !inp || !ch) return;
        if (!msg->isOpen() || !ctrl->isOpen() || !inp->isOpen() || !ch->isOpen()) return;
        if (handshakeSent_.exchange(true)) return;
        log::info("xbox", "data channels open; sending handshake");
        const std::string text =
            json{{"type", "Handshake"}, {"version", "messageV1"}, {"id", "be0bfc6d-1e83-4c8a-90ed-fa8601c5a179"}, {"cv", "0"}}
                .dump();
        sendBinary(msg, reinterpret_cast<const uint8_t*>(text.data()), text.size());
    };
    for (const auto& dc : {chat_, control_, input_, message_}) dc->onOpen(maybeHandshake);

    message_->onMessage([this, gate = gate_](rtc::message_variant data) {
        if (!gate->open.load(std::memory_order_acquire)) return;
        if (const auto* text = std::get_if<std::string>(&data)) onMessageChannel(*text);
        else if (const auto* bin = std::get_if<rtc::binary>(&data)) {
            onMessageChannel(std::string(reinterpret_cast<const char*>(bin->data()), bin->size()));
        }
    });
    input_->onMessage([this, gate = gate_](rtc::message_variant data) {
        if (!gate->open.load(std::memory_order_acquire)) return;
        if (const auto* bin = std::get_if<rtc::binary>(&data)) {
            onInputChannel(reinterpret_cast<const uint8_t*>(bin->data()), bin->size());
        }
    });
    control_->onMessage([](rtc::message_variant data) {
        if (const auto* text = std::get_if<std::string>(&data)) log::info("xbox-control", *text);
    });

    pc_->setLocalDescription(rtc::Description::Type::Offer);
    {
        std::unique_lock lock(mutex_);
        changed_.wait_for(lock, std::chrono::seconds(5),
                          [&] { return pc_->gatheringState() == rtc::PeerConnection::GatheringState::Complete; });
    }
    const auto local = pc_->localDescription();
    if (!local) return fail("no local description");
    const std::string offer = withoutCandidates(std::string(*local));
    std::vector<IceCandidate> candidates;
    for (const auto& c : local->candidates()) {
        std::string text = c.candidate();
        if (text.rfind("a=", 0) == 0) text = text.substr(2);
        candidates.push_back({text, c.mid(), c.mid() == "0" ? 0 : (c.mid() == "1" ? 1 : 2)});
    }
    log::info("xbox", "offer codecs:\n" + describeCodecs(offer));

    std::string answer;
    try {
        answer = signaling.exchangeSdp(offer);
    } catch (const std::exception& e) {
        return fail(std::string("SDP exchange: ") + e.what());
    }
    log::info("xbox", "answer codecs:\n" + describeCodecs(answer));
    try {
        pc_->setRemoteDescription(rtc::Description(answer, rtc::Description::Type::Answer));
    } catch (const std::exception& e) {
        return fail(std::string("the console's answer was not accepted: ") + e.what());
    }

    std::vector<IceCandidate> remote;
    try {
        remote = signaling.exchangeIce(candidates);
    } catch (const std::exception& e) {
        return fail(std::string("ICE exchange: ") + e.what());
    }
    int added = 0;
    for (const auto& c : remote) {
        std::string text = c.candidate;
        if (text.rfind("a=", 0) == 0) text = text.substr(2);
        while (!text.empty() && text.back() == ' ') text.pop_back();
        try {
            pc_->addRemoteCandidate(rtc::Candidate(text, c.sdpMid.empty() ? "0" : c.sdpMid));
            ++added;
        } catch (const std::exception& e) {
            log::warn("xbox", std::string("remote candidate ignored: ") + e.what() + " [" + text + "]");
        }
    }
    log::info("xbox", "local candidates " + std::to_string(candidates.size()) + ", remote " + std::to_string(added));
    if (added == 0) return fail("the console sent no usable network candidates");

    std::unique_lock lock(mutex_);
    if (!changed_.wait_for(lock, timeout, [&] { return connected_.load() || failed_.load(); }) || !connected_) {
        return fail(failed_ ? "the connection to the console failed (network or firewall)"
                            : "timed out connecting to the console");
    }
    return true;
}

bool WebRtcSession::waitReady(std::chrono::milliseconds timeout) {
    std::unique_lock lock(mutex_);
    return changed_.wait_for(lock, timeout, [&] { return handshakeDone_.load() || failed_.load(); }) && handshakeDone_;
}

void WebRtcSession::sendFailure(const std::exception& error) {
    if (!sendErrorLogged_.exchange(true, std::memory_order_relaxed)) {
        log::warn("xbox", std::string("transport send stopped: ") + error.what());
    }
}

bool WebRtcSession::sendBinary(const std::shared_ptr<rtc::DataChannel>& channel, const uint8_t* data, size_t size) {
    if (closed_ || !channel || !channel->isOpen()) return false;
    try {
        channel->send(reinterpret_cast<const std::byte*>(data), size);
        return true;
    } catch (const std::exception& error) {
        sendFailure(error);
        return false;
    }
}

bool WebRtcSession::sendMessageJson(const std::string& text) {
    std::shared_ptr<rtc::DataChannel> msg;
    {
        std::lock_guard lock(gate_->mutex);
        msg = message_;
    }
    return sendBinary(msg, reinterpret_cast<const uint8_t*>(text.data()), text.size());
}

bool WebRtcSession::sendClientConfig() {
    bool sent = true;
    sent &= sendMessageJson(message("/streaming/systemUi/configuration", {{"version", {0, 2, 0}}, {"systemUis", json::array()}}));
    sent &= sendMessageJson(message("/streaming/properties/clientappinstallidchanged",
                                    {{"clientAppInstallId", "c97d7ee0-73b2-4239-bf1d-9d805a338429"}}));
    sent &= sendMessageJson(message("/streaming/characteristics/orientationchanged", {{"orientation", 0}}));
    sent &= sendMessageJson(message("/streaming/characteristics/touchinputenabledchanged", {{"touchInputEnabled", false}}));
    sent &= sendMessageJson(message("/streaming/characteristics/clientdevicecapabilities", json::object()));
    sent &= sendMessageJson(message("/streaming/characteristics/dimensionschanged",
                                    {{"horizontal", 1920},
                                     {"vertical", 1080},
                                     {"preferredWidth", 1920},
                                     {"preferredHeight", 1080},
                                     {"safeAreaLeft", 0},
                                     {"safeAreaTop", 0},
                                     {"safeAreaRight", 1920},
                                     {"safeAreaBottom", 1080},
                                     {"supportsCustomResolution", false}}));
    return sent;
}

void WebRtcSession::onMessageChannel(const std::string& text) {
    json j;
    try {
        j = json::parse(text);
    } catch (const json::exception&) {
        log::warn("xbox", "message channel: not JSON");
        return;
    }
    try {
        const std::string type = j.value("type", "");
        if (type == "HandshakeAck") {
            log::info("xbox", "handshake acknowledged");
            const auto sendControl = [this](const std::string& s) {
                return sendBinary(control_, reinterpret_cast<const uint8_t*>(s.data()), s.size());
            };
            bool initialized = sendControl(
                json{{"message", "authorizationRequest"}, {"accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"}}.dump());
            initialized &= sendControl(json{{"message", "gamepadChanged"}, {"gamepadIndex", 0}, {"wasAdded", true}}.dump());
            initialized &= sendControl(json{{"message", "gamepadChanged"}, {"gamepadIndex", 0}, {"wasAdded", false}}.dump());
            initialized &= sendControl(json{{"message", "gamepadChanged"}, {"gamepadIndex", 0}, {"wasAdded", true}}.dump());
            if (input_ && input_->isOpen()) {
                const auto report = clientMetadataReport(0, nowMs(), 1);
                initialized &= sendBinary(input_, report.data(), report.size());
            }
            initialized &= sendClientConfig();
            handshakeDone_ = initialized;
            changed_.notify_all();
            return;
        }
        if (type == "TransactionStart" || type == "Message") {
            const std::string target = j.value("target", "");
            const std::string id = j.value("id", "");
            if (target == "/streaming/sessionLifetimeManagement/serverInitiatedDisconnect") {
                sendMessageJson(json{{"type", "TransactionComplete"}, {"content", "\"\""}, {"id", id}, {"cv", ""}}.dump());
                const auto content = json::parse(j.value("content", std::string()), nullptr, false);
                const std::string reason = content.is_object() && content.contains("reason") && content["reason"].is_string()
                                               ? content["reason"].get<std::string>()
                                               : "unknown server disconnect";
                if (callbacks_.ended) callbacks_.ended(reason);
            } else if (target == "/streaming/systemUi/messages/ShowMessageDialog") {
                sendMessageJson(
                    json{{"type", "TransactionComplete"}, {"content", json{{"Result", 0}}.dump()}, {"id", id}, {"cv", ""}}.dump());
            } else if (type == "TransactionStart") {
                sendMessageJson(json{{"type", "TransactionComplete"}, {"content", "\"\""}, {"id", id}, {"cv", ""}}.dump());
            }
            return;
        }
    } catch (const json::exception&) {
        log::warn("xbox", "message channel: invalid field type");
    }
}

void WebRtcSession::onInputChannel(const uint8_t* data, size_t size) {
    if (size < 2) return;
    if (auto v = parseVibration(data, size)) {
        if (callbacks_.vibration) callbacks_.vibration(*v);
        return;
    }
    uint32_t w = 0, h = 0;
    if (parseServerMetadata(data, size, &w, &h)) {
        log::info("xbox", "server video " + std::to_string(w) + "x" + std::to_string(h));
        if (callbacks_.serverVideoSize) callbacks_.serverVideoSize(w, h);
    }
}

void WebRtcSession::sendGamepad(const GamepadFrame& frame) {
    if (!gate_->open.load(std::memory_order_acquire) || closed_.load(std::memory_order_acquire) ||
        !handshakeDone_.load(std::memory_order_acquire)) {
        return;
    }
    std::shared_ptr<rtc::DataChannel> input;
    {
        std::lock_guard lock(gate_->mutex);
        input = input_;
    }
    const auto report = gamepadReport(++inputSequence_, nowMs(), {frame});
    sendBinary(input, report.data(), report.size());
}

void WebRtcSession::videoPresented(uint32_t rtp, int64_t arrival, int64_t submitted, int64_t decoded, int64_t presented) {
    if (!gate_->open.load(std::memory_order_acquire) || closed_.load(std::memory_order_acquire) ||
        !handshakeDone_.load(std::memory_order_acquire)) {
        return;
    }
    if (feedbackSent_.load(std::memory_order_acquire) && rtp == lastFeedbackRtp_.load(std::memory_order_relaxed)) {
        return;
    }
    std::shared_ptr<rtc::DataChannel> input;
    {
        std::lock_guard lock(gate_->mutex);
        input = input_;
    }
    const auto origin = std::chrono::duration_cast<std::chrono::nanoseconds>(origin_.time_since_epoch()).count() / 100;
    const auto ms = [origin](int64_t stamp) {
        return static_cast<uint32_t>(std::max<int64_t>(0, stamp - origin) / 10000);
    };
    const auto report = videoFeedbackReport(++inputSequence_, nowMs(),
                                            {rtp, ms(arrival), ms(submitted), ms(decoded), ms(presented)});
    if (sendBinary(input, report.data(), report.size())) {
        if (!feedbackSent_.exchange(true, std::memory_order_release)) {
            log::info("xbox", "video presentation feedback started");
        }
        lastFeedbackRtp_.store(rtp, std::memory_order_relaxed);
    }
}

void WebRtcSession::pumpBitrateRequest() {
    const unsigned int bps = requestedVideoBitrate_.load(std::memory_order_relaxed);
    if (bps == 0) return;
    if (!gate_->open.load(std::memory_order_acquire) || closed_.load(std::memory_order_acquire)) return;
    if (!connected_.load(std::memory_order_acquire)) return;
    // Called from the frame loop; keep REMB traffic to about twice a second.
    const auto now = std::chrono::steady_clock::now();
    if (now - lastBitrateRequest_ < std::chrono::milliseconds(500)) return;
    lastBitrateRequest_ = now;
    std::shared_ptr<rtc::Track> video;
    {
        std::lock_guard lock(gate_->mutex);
        video = video_;
    }
    if (!video) return;
    try {
        // libdatachannel sends RR only unless a bitrate has been requested, so without this the
        // console never learns that the LAN can carry more than its default target.
        video->requestBitrate(bps);
    } catch (const std::exception& error) {
        sendFailure(error);
    }
}

void WebRtcSession::requestKeyframe() {
    if (!gate_->open.load(std::memory_order_acquire) || closed_.load(std::memory_order_acquire)) return;
    ++keyframeRequests_;
    std::shared_ptr<rtc::Track> video;
    std::shared_ptr<rtc::DataChannel> control;
    {
        std::lock_guard lock(gate_->mutex);
        video = video_;
        control = control_;
    }
    try {
        if (video) video->requestKeyframe();
    } catch (const std::exception& error) {
        sendFailure(error);
    }
    if (handshakeDone_.load(std::memory_order_acquire)) {
        const std::string s = json{{"message", "videoKeyframeRequested"}, {"ifrRequested", true}}.dump();
        sendBinary(control, reinterpret_cast<const uint8_t*>(s.data()), s.size());
    }
}

void WebRtcSession::close() {
    if (closed_.exchange(true)) return;
    std::shared_ptr<rtc::DataChannel> ch, ctrl, inp, msg;
    std::shared_ptr<rtc::Track> aud, vid;
    std::shared_ptr<rtc::PeerConnection> pc;
    {
        std::lock_guard lock(gate_->mutex);
        gate_->open.store(false, std::memory_order_release);
        handshakeDone_ = false;
        connected_ = false;
        ch = std::move(chat_);
        ctrl = std::move(control_);
        inp = std::move(input_);
        msg = std::move(message_);
        aud = std::move(audio_);
        vid = std::move(video_);
        pc = std::move(pc_);
    }
    for (const auto& dc : {ch, ctrl, inp, msg}) {
        if (dc) dc->resetCallbacks();
    }
    if (aud) aud->resetCallbacks();
    if (vid) vid->resetCallbacks();
    if (pc) pc->resetCallbacks();
    if (pc) {
        try {
            pc->close();
        } catch (...) {}
    }
    changed_.notify_all();
}

WebRtcStats WebRtcSession::stats() const {
    WebRtcStats s;
    s.videoUnits = videoUnits_.load();
    s.audioPackets = audioPackets_.load();
    s.keyframeRequests = keyframeRequests_.load();
    s.videoBytes = videoBytes_.load();
    s.handshakeDone = handshakeDone_.load();
    if (!gate_->open.load(std::memory_order_acquire) || closed_.load(std::memory_order_acquire)) {
        return s;
    }
    std::shared_ptr<rtc::PeerConnection> pc;
    {
        std::lock_guard lock(gate_->mutex);
        pc = pc_;
    }
    if (pc) {
        s.bytesReceived = pc->bytesReceived();
        if (const auto rtt = pc->rtt()) s.rttMs = static_cast<double>(rtt->count());
    }
    return s;
}

} // namespace gta60::xbox

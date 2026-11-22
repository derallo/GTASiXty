#include "gta60/sink/QuestStreamServer.h"
#include "gta60/Log.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <format>
#include <mutex>
#include <thread>

#pragma comment(lib, "ws2_32.lib")

namespace gta60::sink {
namespace {

struct Gt60Header {
    uint32_t magic;
    uint8_t type;
    uint8_t flags;
    uint16_t meta;
    uint32_t payloadSize;
    uint32_t timestampMs;
};
static_assert(sizeof(Gt60Header) == 16);

// MAC of the first up, non-loopback adapter. Advertised in the LAN discovery reply so the Quest can
// remember it and send a Wake-on-LAN magic packet later, when this machine is powered off and can
// no longer answer discovery.
std::string primaryMacAddress() {
    const ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    ULONG size = 0;
    if (GetAdaptersAddresses(AF_INET, flags, nullptr, nullptr, &size) != ERROR_BUFFER_OVERFLOW) return {};
    std::vector<uint8_t> storage(size);
    auto* addrs = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(storage.data());
    if (GetAdaptersAddresses(AF_INET, flags, nullptr, addrs, &size) != NO_ERROR) return {};
    for (auto* a = addrs; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp) continue;
        if (a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        if (a->PhysicalAddressLength < 6) continue;
        const auto* m = a->PhysicalAddress;
        return std::format("{:02X}:{:02X}:{:02X}:{:02X}:{:02X}:{:02X}", m[0], m[1], m[2], m[3], m[4], m[5]);
    }
    return {};
}

bool sendAll(SOCKET s, const uint8_t* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        const int n = send(s, reinterpret_cast<const char*>(data + sent), static_cast<int>(len - sent), 0);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool recvAll(SOCKET s, uint8_t* data, size_t len) {
    size_t got = 0;
    while (got < len) {
        const int n = recv(s, reinterpret_cast<char*>(data + got), static_cast<int>(len - got), 0);
        if (n <= 0) return false;
        got += static_cast<size_t>(n);
    }
    return true;
}

// VIDEO_CONFIG payload: width, height, codec byte, then the Annex-B parameter sets.
std::vector<uint8_t> makeVideoConfigPayload(uint32_t width, uint32_t height, media::VideoCodec codec,
                                            const std::vector<uint8_t>& csd) {
    std::vector<uint8_t> payload(QuestStreamServer::kVideoConfigHeaderBytes + csd.size());
    std::memcpy(payload.data() + 0, &width, 4);
    std::memcpy(payload.data() + 4, &height, 4);
    payload[8] = static_cast<uint8_t>(codec);
    if (!csd.empty()) {
        std::memcpy(payload.data() + QuestStreamServer::kVideoConfigHeaderBytes, csd.data(), csd.size());
    }
    return payload;
}

} // namespace

struct QuestStreamServer::Impl {
    struct QueuedPacket {
        uint8_t type = 0;
        uint8_t flags = 0;
        uint64_t clientGen = 0;
        std::vector<uint8_t> bytes;
    };

    bool wsaInit = false;
    SOCKET listenSock = INVALID_SOCKET;
    SOCKET udpSock = INVALID_SOCKET;
    SOCKET clientSock = INVALID_SOCKET;
    uint16_t tcpPort = 7660;
    uint32_t width = 2560, height = 1440, fps = 60;
    media::VideoCodec codec = media::VideoCodec::H264;
    Callbacks cbs;

    std::mutex sendMtx;
    std::mutex cfgMtx;
    std::mutex queueMtx;
    std::condition_variable queueCv;
    std::deque<QueuedPacket> sendQueue;
    std::atomic<size_t> queuedVideoFrames{0};
    std::atomic<size_t> maxVideoBacklog{0}; // 0 = unlimited; >0 enables drop-to-IDR congestion control
    std::atomic<int> sendBufBytes{512 * 1024};
    bool waitingForRecoveryIdr = false;
    uint64_t nextGen = 0;
    std::atomic<uint64_t> activeGen{0};

    // Real TCP socket telemetry
    std::atomic<uint64_t> tcpSendUsSum{0};
    std::atomic<uint64_t> tcpSendCount{0};
    std::atomic<uint64_t> tcpSendMaxUs{0};
    std::atomic<uint64_t> videoDropped{0};
    std::atomic<size_t> observedMaxBacklog{0};

    // PC->Quest latency probe: remember when each access unit actually left the socket, then match
    // it against the timestamp the Quest echoes back on present.
    std::array<std::pair<uint32_t, std::chrono::steady_clock::time_point>, 64> sentAuTimes{};
    size_t sentAuIdx = 0;
    std::mutex sentAuMtx;
    std::atomic<uint64_t> e2eMinUs{0};
    std::atomic<uint64_t> e2eSumUs{0};
    std::atomic<uint64_t> e2eCount{0};

    std::vector<uint8_t> spsPps;
    std::atomic<bool> running{false};
    std::string macAddress;
    std::jthread acceptThread;
    std::jthread udpThread;
    std::jthread sendThread;

    void closeClientLocked() {
        activeGen.store(0, std::memory_order_release);
        if (clientSock != INVALID_SOCKET) {
            shutdown(clientSock, SD_BOTH);
            closesocket(clientSock);
            clientSock = INVALID_SOCKET;
        }
    }

    bool enqueuePacket(uint8_t type, uint8_t flags, uint16_t meta, uint32_t tsMs, const uint8_t* payload, size_t len) {
        const uint64_t gen = activeGen.load(std::memory_order_acquire);
        if (gen == 0) return false;

        bool requestIdrAfterUnlock = false;
        {
            std::lock_guard qlock(queueMtx);
            if (activeGen.load(std::memory_order_acquire) != gen) return false;

            if (type == kTypeVideoConfig) {
                waitingForRecoveryIdr = false;
            } else if (type == kTypeAudioPcm) {
                // Keep audio tightly bounded so it never lags behind video
                size_t audioQueued = 0;
                for (const auto& q : sendQueue) {
                    if (q.type == kTypeAudioPcm) ++audioQueued;
                }
                if (audioQueued >= 4) {
                    return false;
                }
            } else if (type == kTypeVideoAu) {
                const bool isIdr = (flags & kFlagIdr) != 0;
                const size_t cap = maxVideoBacklog.load(std::memory_order_relaxed);
                if (waitingForRecoveryIdr) {
                    if (!isIdr) {
                        videoDropped.fetch_add(1, std::memory_order_relaxed);
                        return false;
                    }
                    waitingForRecoveryIdr = false;
                }
                if (cap > 0 && queuedVideoFrames.load(std::memory_order_relaxed) >= cap) {
                    // Purge stale queued video packets and recover cleanly with a fresh IDR
                    size_t purged = 0;
                    for (auto it = sendQueue.begin(); it != sendQueue.end();) {
                        if (it->type == kTypeVideoAu) {
                            it = sendQueue.erase(it);
                            ++purged;
                        } else {
                            ++it;
                        }
                    }
                    queuedVideoFrames.store(0, std::memory_order_relaxed);
                    videoDropped.fetch_add(purged, std::memory_order_relaxed);
                    if (!isIdr) {
                        waitingForRecoveryIdr = true;
                        videoDropped.fetch_add(1, std::memory_order_relaxed);
                        requestIdrAfterUnlock = true;
                    }
                }
            }

            if (!requestIdrAfterUnlock) {
                QueuedPacket pkt;
                pkt.type = type;
                pkt.flags = flags;
                pkt.clientGen = gen;
                pkt.bytes.resize(sizeof(Gt60Header) + len);
                Gt60Header hdr{kMagic, type, flags, meta, static_cast<uint32_t>(len), tsMs};
                std::memcpy(pkt.bytes.data(), &hdr, sizeof(hdr));
                if (len > 0 && payload) {
                    std::memcpy(pkt.bytes.data() + sizeof(hdr), payload, len);
                }
                if (type == kTypeVideoAu) {
                    const size_t qv = queuedVideoFrames.fetch_add(1, std::memory_order_relaxed) + 1;
                    size_t prevMax = observedMaxBacklog.load(std::memory_order_relaxed);
                    while (qv > prevMax && !observedMaxBacklog.compare_exchange_weak(prevMax, qv, std::memory_order_relaxed)) {}
                }
                sendQueue.push_back(std::move(pkt));
            }
        }
        if (requestIdrAfterUnlock) {
            if (cbs.onIdrRequested) cbs.onIdrRequested();
            return false;
        }
        queueCv.notify_one();
        return true;
    }
};

QuestStreamServer::QuestStreamServer() : p_(std::make_unique<Impl>()) {}
QuestStreamServer::~QuestStreamServer() { stop(); }

bool QuestStreamServer::start(uint16_t tcpPort,
                              uint32_t width,
                              uint32_t height,
                              uint32_t fps,
                              std::vector<uint8_t> initialSpsPps,
                              media::VideoCodec codec,
                              Callbacks cbs) {
    stop();

    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;
    p_->wsaInit = true;
    p_->width = width;
    p_->height = height;
    p_->fps = fps;
    p_->codec = codec;
    p_->spsPps = std::move(initialSpsPps);
    p_->cbs = std::move(cbs);

    p_->listenSock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (p_->listenSock == INVALID_SOCKET) {
        stop();
        return false;
    }

    int reuse = 1;
    setsockopt(p_->listenSock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(tcpPort);
    if (bind(p_->listenSock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
        listen(p_->listenSock, 4) != 0) {
        log::error("quest-srv", std::format("TCP listen on port {} failed err={}", tcpPort, WSAGetLastError()));
        stop();
        return false;
    }

    sockaddr_in bound{};
    int boundLen = sizeof(bound);
    if (getsockname(p_->listenSock, reinterpret_cast<sockaddr*>(&bound), &boundLen) == 0) {
        p_->tcpPort = ntohs(bound.sin_port);
    } else {
        p_->tcpPort = tcpPort;
    }

    // UDP discovery socket on port 7661 (best-effort)
    p_->udpSock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (p_->udpSock != INVALID_SOCKET) {
        setsockopt(p_->udpSock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));
        sockaddr_in uaddr{};
        uaddr.sin_family = AF_INET;
        uaddr.sin_addr.s_addr = INADDR_ANY;
        uaddr.sin_port = htons(7661);
        if (bind(p_->udpSock, reinterpret_cast<sockaddr*>(&uaddr), sizeof(uaddr)) != 0) {
            closesocket(p_->udpSock);
            p_->udpSock = INVALID_SOCKET;
        }
    }

    p_->running.store(true);
    p_->macAddress = primaryMacAddress();
    if (!p_->macAddress.empty()) {
        log::info("quest-srv", "advertising MAC " + p_->macAddress + " for Wake-on-LAN");
    }

    p_->sendThread = std::jthread([this](std::stop_token st) {
        SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
        while (!st.stop_requested() && p_->running.load()) {
            Impl::QueuedPacket pkt;
            {
                std::unique_lock qlock(p_->queueMtx);
                p_->queueCv.wait(qlock, [&] {
                    return st.stop_requested() || !p_->running.load() || !p_->sendQueue.empty();
                });
                if (st.stop_requested() || !p_->running.load()) break;
                pkt = std::move(p_->sendQueue.front());
                p_->sendQueue.pop_front();
                if (pkt.type == kTypeVideoAu) {
                    const size_t cur = p_->queuedVideoFrames.load(std::memory_order_relaxed);
                    if (cur > 0) p_->queuedVideoFrames.fetch_sub(1, std::memory_order_relaxed);
                }
            }

            if (pkt.clientGen != p_->activeGen.load(std::memory_order_acquire)) continue;

            std::lock_guard lock(p_->sendMtx);
            if (p_->clientSock == INVALID_SOCKET || pkt.clientGen != p_->activeGen.load(std::memory_order_relaxed)) {
                continue;
            }
            const auto t0 = std::chrono::steady_clock::now();
            const bool ok = sendAll(p_->clientSock, pkt.bytes.data(), pkt.bytes.size());
            const auto t1 = std::chrono::steady_clock::now();
            if (ok && pkt.type == kTypeVideoAu) {
                const uint64_t us = static_cast<uint64_t>(
                    std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count());
                p_->tcpSendUsSum.fetch_add(us, std::memory_order_relaxed);
                p_->tcpSendCount.fetch_add(1, std::memory_order_relaxed);
                uint64_t prevMax = p_->tcpSendMaxUs.load(std::memory_order_relaxed);
                while (us > prevMax && !p_->tcpSendMaxUs.compare_exchange_weak(prevMax, us, std::memory_order_relaxed)) {}
                // Remember when this AU left the socket so the Quest's present-echo can be resolved
                // back to an exact send time instead of being approximated from queue depth.
                if (pkt.bytes.size() >= sizeof(Gt60Header)) {
                    Gt60Header h{};
                    std::memcpy(&h, pkt.bytes.data(), sizeof(h));
                    std::lock_guard lk(p_->sentAuMtx);
                    p_->sentAuTimes[p_->sentAuIdx % p_->sentAuTimes.size()] = {h.timestampMs, t1};
                    ++p_->sentAuIdx;
                }
            }
            if (!ok) {
                log::warn("quest-srv", "Quest client disconnected on send");
                p_->closeClientLocked();
                std::lock_guard qlock(p_->queueMtx);
                p_->sendQueue.clear();
                p_->queuedVideoFrames.store(0, std::memory_order_relaxed);
                p_->waitingForRecoveryIdr = false;
            }
        }
    });

    if (p_->udpSock != INVALID_SOCKET) {
        p_->udpThread = std::jthread([this](std::stop_token st) {
            char buf[256];
            while (!st.stop_requested() && p_->running.load()) {
                sockaddr_in from{};
                int fromLen = sizeof(from);
                const int n = recvfrom(p_->udpSock, buf, sizeof(buf) - 1, 0,
                                       reinterpret_cast<sockaddr*>(&from), &fromLen);
                if (n <= 0) break;
                buf[n] = '\0';
                if (std::strncmp(buf, "GTA60_DISCOVER", 14) == 0) {
                    const std::string reply = std::format("GTA60_OFFER|{}|{}|{}|{}|{}|{}",
                                                          p_->tcpPort, p_->width, p_->height, p_->fps,
                                                          media::videoCodecName(p_->codec), p_->macAddress);
                    sendto(p_->udpSock, reply.data(), static_cast<int>(reply.size()), 0,
                           reinterpret_cast<sockaddr*>(&from), fromLen);
                } else if (std::strncmp(buf, "GTA60_STOP", 10) == 0) {
                    log::info("quest-srv", "GTA60_STOP received; releasing the Xbox session");
                    if (p_->cbs.onStopRequested) p_->cbs.onStopRequested();
                }
            }
        });
    }

    p_->acceptThread = std::jthread([this](std::stop_token st) {
        while (!st.stop_requested() && p_->running.load()) {
            sockaddr_in peer{};
            int peerLen = sizeof(peer);
            SOCKET c = accept(p_->listenSock, reinterpret_cast<sockaddr*>(&peer), &peerLen);
            if (c == INVALID_SOCKET) break;

            int nodelay = 1;
            int sndbuf = p_->sendBufBytes.load(std::memory_order_relaxed);
            int dscp = 0xB8; // DSCP EF (46) for WMM AC_VO/VI low-latency QoS on LAN/Wi-Fi
            setsockopt(c, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay), sizeof(nodelay));
            setsockopt(c, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sndbuf), sizeof(sndbuf));
            setsockopt(c, IPPROTO_IP, IP_TOS, reinterpret_cast<const char*>(&dscp), sizeof(dscp));

            char ipBuf[64]{};
            inet_ntop(AF_INET, &peer.sin_addr, ipBuf, sizeof(ipBuf));
            log::info("quest-srv", std::format("Quest 3 client connected from {}:{} (SO_SNDBUF={}KB)",
                                               ipBuf, ntohs(peer.sin_port), sndbuf / 1024));

            const uint64_t gen = ++p_->nextGen;
            {
                std::lock_guard lock(p_->sendMtx);
                p_->closeClientLocked();
                {
                    std::lock_guard qlock(p_->queueMtx);
                    p_->sendQueue.clear();
                    p_->queuedVideoFrames.store(0, std::memory_order_relaxed);
                    p_->waitingForRecoveryIdr = false;
                }
                p_->clientSock = c;
                p_->activeGen.store(gen, std::memory_order_release);
            }

            {
                std::vector<uint8_t> cfgCopy;
                uint32_t w = 0, h = 0, fps = 0;
                media::VideoCodec codec = media::VideoCodec::H264;
                {
                    std::lock_guard cfgLock(p_->cfgMtx);
                    cfgCopy = p_->spsPps;
                    w = p_->width;
                    h = p_->height;
                    fps = p_->fps;
                    codec = p_->codec;
                }
                if (!cfgCopy.empty()) {
                    const auto initPayload = makeVideoConfigPayload(w, h, codec, cfgCopy);
                    p_->enqueuePacket(kTypeVideoConfig, 0, static_cast<uint16_t>(fps), 0,
                                      initPayload.data(), initPayload.size());
                }
            }

            if (p_->cbs.onClientConnected) p_->cbs.onClientConnected();
            if (p_->cbs.onIdrRequested) p_->cbs.onIdrRequested();

            // Read backchannel packets from this client until disconnect
            std::vector<uint8_t> payload;
            while (!st.stop_requested() && p_->running.load()) {
                Gt60Header hdr{};
                if (!recvAll(c, reinterpret_cast<uint8_t*>(&hdr), sizeof(hdr))) break;
                if (hdr.magic != kMagic || hdr.payloadSize > 1024 * 1024) break;
                payload.resize(hdr.payloadSize);
                if (hdr.payloadSize > 0 && !recvAll(c, payload.data(), hdr.payloadSize)) break;

                if (hdr.type == kTypeClientIdr && p_->cbs.onIdrRequested) {
                    p_->cbs.onIdrRequested();
                } else if (hdr.type == kTypeClientGamepad && p_->cbs.onGamepad && payload.size() >= sizeof(xbox::GamepadFrame)) {
                    xbox::GamepadFrame gf{};
                    std::memcpy(&gf, payload.data(), sizeof(gf));
                    p_->cbs.onGamepad(gf);
                } else if (hdr.type == kTypeClientConfig && p_->cbs.onConfigRequested && payload.size() >= sizeof(ClientStreamConfig)) {
                    ClientStreamConfig cfg{};
                    std::memcpy(&cfg, payload.data(), sizeof(cfg));
                    p_->cbs.onConfigRequested(cfg);
                } else if (hdr.type == kTypeClientRendered && payload.size() >= 4) {
                    // The Quest just presented an AU we sent: resolve it to the exact socket-write
                    // time and record the round trip.
                    uint32_t auTs = 0;
                    std::memcpy(&auTs, payload.data(), 4);
                    std::chrono::steady_clock::time_point sentAt{};
                    bool found = false;
                    {
                        std::lock_guard lk(p_->sentAuMtx);
                        for (const auto& e : p_->sentAuTimes) {
                            if (e.first == auTs) {
                                sentAt = e.second;
                                found = true;
                                break;
                            }
                        }
                    }
                    if (found) {
                        const uint64_t e2eUs = static_cast<uint64_t>(
                            std::chrono::duration_cast<std::chrono::microseconds>(
                                std::chrono::steady_clock::now() - sentAt).count());
                        p_->e2eSumUs.fetch_add(e2eUs, std::memory_order_relaxed);
                        p_->e2eCount.fetch_add(1, std::memory_order_relaxed);
                        uint64_t prevMin = p_->e2eMinUs.load(std::memory_order_relaxed);
                        while ((prevMin == 0 || e2eUs < prevMin) &&
                               !p_->e2eMinUs.compare_exchange_weak(prevMin, e2eUs, std::memory_order_relaxed)) {}
                    }
                }
            }

            {
                std::lock_guard lock(p_->sendMtx);
                if (p_->clientSock == c) p_->closeClientLocked();
            }
        }
    });

    log::info("quest-srv", std::format("serving {} on TCP {} (stream) + UDP 7661 (LAN discovery)",
                                       media::videoCodecName(p_->codec), p_->tcpPort));
    return true;
}

void QuestStreamServer::setVideoConfig(uint32_t width, uint32_t height, uint32_t fps, const std::vector<uint8_t>& spsPps) {
    media::VideoCodec codec = media::VideoCodec::H264;
    {
        std::lock_guard cfgLock(p_->cfgMtx);
        p_->width = width;
        p_->height = height;
        p_->fps = fps;
        p_->spsPps = spsPps;
        codec = p_->codec;
    }
    if (!spsPps.empty() && hasClient()) {
        const auto initPayload = makeVideoConfigPayload(width, height, codec, spsPps);
        p_->enqueuePacket(kTypeVideoConfig, 0, static_cast<uint16_t>(fps), 0,
                          initPayload.data(), initPayload.size());
    }
}

bool QuestStreamServer::sendVideoAccessUnit(const uint8_t* data, size_t size, uint32_t timestampMs,
                                            bool isIdr, bool isGenerated) {
    const uint8_t flags = static_cast<uint8_t>((isIdr ? kFlagIdr : 0) | (isGenerated ? kFlagGenerated : 0));
    return p_->enqueuePacket(kTypeVideoAu, flags, 0, timestampMs, data, size);
}

void QuestStreamServer::sendAudioPcm(const int16_t* interleavedStereo, size_t frameCount, uint32_t timestampMs) {
    if (!interleavedStereo || frameCount == 0) return;
    p_->enqueuePacket(kTypeAudioPcm, 0, 0, timestampMs,
                      reinterpret_cast<const uint8_t*>(interleavedStereo), frameCount * 2 * sizeof(int16_t));
}

bool QuestStreamServer::hasClient() const {
    return p_->activeGen.load(std::memory_order_acquire) != 0;
}

size_t QuestStreamServer::sendBacklogFrames() const {
    return p_->queuedVideoFrames.load(std::memory_order_relaxed);
}

void QuestStreamServer::setMaxVideoBacklog(size_t maxFrames) {
    p_->maxVideoBacklog.store(maxFrames, std::memory_order_relaxed);
}

void QuestStreamServer::setSendBufferBytes(int bytes) {
    p_->sendBufBytes.store(bytes, std::memory_order_relaxed);
    std::lock_guard lock(p_->sendMtx);
    if (p_->clientSock != INVALID_SOCKET) {
        setsockopt(p_->clientSock, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&bytes), sizeof(bytes));
    }
}

QuestStreamServer::ServerStats QuestStreamServer::consumeStats() {
    ServerStats s{};
    const uint64_t cnt = p_->tcpSendCount.exchange(0, std::memory_order_relaxed);
    const uint64_t sumUs = p_->tcpSendUsSum.exchange(0, std::memory_order_relaxed);
    const uint64_t maxUs = p_->tcpSendMaxUs.exchange(0, std::memory_order_relaxed);
    s.tcpSendAvgMs = cnt ? (double(sumUs) / 1000.0) / double(cnt) : 0.0;
    s.tcpSendMaxMs = double(maxUs) / 1000.0;
    s.videoDropped = p_->videoDropped.load(std::memory_order_relaxed);
    s.maxBacklog = p_->observedMaxBacklog.exchange(0, std::memory_order_relaxed);

    const uint64_t e2eCount = p_->e2eCount.exchange(0, std::memory_order_relaxed);
    const uint64_t e2eSumUs = p_->e2eSumUs.exchange(0, std::memory_order_relaxed);
    const uint64_t e2eMinUs = p_->e2eMinUs.exchange(0, std::memory_order_relaxed);
    s.e2eSamples = e2eCount;
    s.e2eAvgMs = e2eCount ? (double(e2eSumUs) / 1000.0) / double(e2eCount) : 0.0;
    s.e2eMinMs = double(e2eMinUs) / 1000.0;
    return s;
}

uint16_t QuestStreamServer::port() const { return p_->tcpPort; }

void QuestStreamServer::stop() {
    p_->running.store(false);
    if (p_->listenSock != INVALID_SOCKET) {
        closesocket(p_->listenSock);
        p_->listenSock = INVALID_SOCKET;
    }
    if (p_->udpSock != INVALID_SOCKET) {
        closesocket(p_->udpSock);
        p_->udpSock = INVALID_SOCKET;
    }
    {
        std::lock_guard lock(p_->sendMtx);
        p_->closeClientLocked();
    }
    if (p_->sendThread.joinable()) {
        p_->sendThread.request_stop();
        p_->queueCv.notify_all();
        p_->sendThread.join();
    }
    if (p_->acceptThread.joinable()) {
        p_->acceptThread.request_stop();
        p_->acceptThread.join();
    }
    if (p_->udpThread.joinable()) {
        p_->udpThread.request_stop();
        p_->udpThread.join();
    }
    {
        std::lock_guard qlock(p_->queueMtx);
        p_->sendQueue.clear();
        p_->queuedVideoFrames.store(0, std::memory_order_relaxed);
    }
    if (p_->wsaInit) {
        WSACleanup();
        p_->wsaInit = false;
    }
}

} // namespace gta60::sink

#include "Gt60Server.hpp"

#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netpacket/packet.h>
#include <sys/socket.h>
#include <unistd.h>

#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>

namespace gta60_linux {
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

std::string primaryMacAddress() {
    struct ifaddrs* ifa = nullptr;
    if (getifaddrs(&ifa) != 0) return "00:00:00:00:00:00";

    std::string result = "00:00:00:00:00:00";
    for (auto* p = ifa; p != nullptr; p = p->ifa_next) {
        if (!p->ifa_addr || p->ifa_addr->sa_family != AF_PACKET) continue;
        if ((p->ifa_flags & IFF_LOOPBACK) != 0 || (p->ifa_flags & IFF_UP) == 0) continue;
        auto* sll = reinterpret_cast<struct sockaddr_ll*>(p->ifa_addr);
        if (sll->sll_halen != 6) continue;
        char buf[24];
        std::snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X",
                      sll->sll_addr[0], sll->sll_addr[1], sll->sll_addr[2],
                      sll->sll_addr[3], sll->sll_addr[4], sll->sll_addr[5]);
        result = buf;
        break;
    }
    freeifaddrs(ifa);
    return result;
}

std::vector<uint8_t> makeVideoConfigPayload(uint32_t w, uint32_t h, VideoCodec codec, const std::vector<uint8_t>& csd) {
    std::vector<uint8_t> out(9 + csd.size());
    std::memcpy(out.data(), &w, 4);
    std::memcpy(out.data() + 4, &h, 4);
    out[8] = static_cast<uint8_t>(codec);
    if (!csd.empty()) {
        std::memcpy(out.data() + 9, csd.data(), csd.size());
    }
    return out;
}

bool sendAll(int fd, const uint8_t* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = ::send(fd, data + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool recvAll(int fd, uint8_t* data, size_t len) {
    size_t got = 0;
    while (got < len) {
        ssize_t n = ::recv(fd, data + got, len - got, 0);
        if (n <= 0) return false;
        got += static_cast<size_t>(n);
    }
    return true;
}

struct QueuedPacket {
    uint8_t type = 0;
    std::vector<uint8_t> bytes;
};

} // namespace

struct Gt60Server::Impl {
    std::atomic<bool> running{false};
    uint16_t tcpPort = 7660;
    uint16_t udpPort = 7661;
    uint32_t width = 2560;
    uint32_t height = 1440;
    uint32_t fps = 60;
    VideoCodec codec = VideoCodec::HEVC;
    std::string macAddress;
    std::vector<uint8_t> csd;
    std::mutex cfgMtx;
    Callbacks cbs;

    int listenFd = -1;
    int udpFd = -1;
    std::atomic<int> clientFd{-1};

    std::mutex queueMtx;
    std::condition_variable queueCv;
    std::deque<QueuedPacket> queue;
    static constexpr size_t kMaxVideoBacklog = 2;
    std::atomic<uint64_t> droppedVideo{0};

    std::thread udpThread;
    std::thread acceptThread;
    std::thread sendThread;

    void enqueue(uint8_t type, uint8_t flags, uint16_t meta, uint32_t tsMs,
                 const uint8_t* data, size_t len) {
        if (clientFd.load() < 0) return;

        Gt60Header hdr{kMagic, type, flags, meta, static_cast<uint32_t>(len), tsMs};
        std::vector<uint8_t> pkt(sizeof(hdr) + len);
        std::memcpy(pkt.data(), &hdr, sizeof(hdr));
        if (len > 0) std::memcpy(pkt.data() + sizeof(hdr), data, len);

        {
            std::lock_guard lk(queueMtx);
            if (type == kTypeVideoAu) {
                size_t videoCount = 0;
                for (const auto& q : queue) {
                    if (q.type == kTypeVideoAu) ++videoCount;
                }
                if (videoCount >= kMaxVideoBacklog) {
                    // Drop the oldest queued video AU so latency never accumulates.
                    for (auto it = queue.begin(); it != queue.end(); ++it) {
                        if (it->type == kTypeVideoAu) {
                            queue.erase(it);
                            ++droppedVideo;
                            break;
                        }
                    }
                }
            }
            queue.push_back(QueuedPacket{type, std::move(pkt)});
        }
        queueCv.notify_one();
    }

    void closeClient() {
        int fd = clientFd.exchange(-1);
        if (fd >= 0) {
            ::shutdown(fd, SHUT_RDWR);
            ::close(fd);
        }
        std::lock_guard lk(queueMtx);
        queue.clear();
    }
};

Gt60Server::Gt60Server() : p_(std::make_unique<Impl>()) {}
Gt60Server::~Gt60Server() { stop(); }

bool Gt60Server::start(uint16_t tcpPort, uint16_t udpDiscoveryPort,
                       uint32_t width, uint32_t height, uint32_t fps,
                       VideoCodec codec, std::vector<uint8_t> csd, Callbacks cbs) {
    stop();

    p_->tcpPort = tcpPort;
    p_->udpPort = udpDiscoveryPort;
    p_->width = width;
    p_->height = height;
    p_->fps = fps;
    p_->codec = codec;
    p_->csd = std::move(csd);
    p_->cbs = std::move(cbs);
    p_->macAddress = primaryMacAddress();

    // TCP listen socket
    p_->listenFd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (p_->listenFd < 0) return false;
    int yes = 1;
    ::setsockopt(p_->listenFd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(tcpPort);
    if (::bind(p_->listenFd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
        ::listen(p_->listenFd, 2) != 0) {
        stop();
        return false;
    }

    // UDP discovery socket
    p_->udpFd = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (p_->udpFd < 0) {
        stop();
        return false;
    }
    ::setsockopt(p_->udpFd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in uaddr{};
    uaddr.sin_family = AF_INET;
    uaddr.sin_addr.s_addr = INADDR_ANY;
    uaddr.sin_port = htons(udpDiscoveryPort);
    if (::bind(p_->udpFd, reinterpret_cast<sockaddr*>(&uaddr), sizeof(uaddr)) != 0) {
        stop();
        return false;
    }

    p_->running.store(true);

    p_->udpThread = std::thread([this]() {
        char buf[256];
        while (p_->running.load()) {
            sockaddr_in from{};
            socklen_t fromLen = sizeof(from);
            ssize_t n = ::recvfrom(p_->udpFd, buf, sizeof(buf) - 1, 0,
                                   reinterpret_cast<sockaddr*>(&from), &fromLen);
            if (n <= 0) break;
            buf[n] = '\0';
            if (std::strncmp(buf, "GTA60_DISCOVER", 14) == 0) {
                uint32_t w, h, f;
                VideoCodec c;
                {
                    std::lock_guard lk(p_->cfgMtx);
                    w = p_->width;
                    h = p_->height;
                    f = p_->fps;
                    c = p_->codec;
                }
                char reply[160];
                int len = std::snprintf(reply, sizeof(reply), "GTA60_OFFER|%u|%u|%u|%u|%s|%s",
                                        p_->tcpPort, w, h, f, videoCodecName(c), p_->macAddress.c_str());
                if (len > 0) {
                    ::sendto(p_->udpFd, reply, static_cast<size_t>(len), 0,
                             reinterpret_cast<sockaddr*>(&from), fromLen);
                }
            }
        }
    });

    p_->sendThread = std::thread([this]() {
        while (p_->running.load()) {
            QueuedPacket pkt;
            {
                std::unique_lock lk(p_->queueMtx);
                p_->queueCv.wait(lk, [this]() {
                    return !p_->running.load() || !p_->queue.empty();
                });
                if (!p_->running.load()) break;
                pkt = std::move(p_->queue.front());
                p_->queue.pop_front();
            }
            int fd = p_->clientFd.load();
            if (fd >= 0 && !sendAll(fd, pkt.bytes.data(), pkt.bytes.size())) {
                p_->closeClient();
            }
        }
    });

    p_->acceptThread = std::thread([this]() {
        while (p_->running.load()) {
            sockaddr_in peer{};
            socklen_t peerLen = sizeof(peer);
            int c = ::accept(p_->listenFd, reinterpret_cast<sockaddr*>(&peer), &peerLen);
            if (c < 0) break;

            int one = 1;
            ::setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
            int sndBuf = 2 * 1024 * 1024;
            ::setsockopt(c, SOL_SOCKET, SO_SNDBUF, &sndBuf, sizeof(sndBuf));

            p_->closeClient();
            p_->clientFd.store(c);

            {
                std::lock_guard lk(p_->cfgMtx);
                if (!p_->csd.empty()) {
                    auto cfgPkt = makeVideoConfigPayload(p_->width, p_->height, p_->codec, p_->csd);
                    p_->enqueue(kTypeVideoConfig, 0, static_cast<uint16_t>(p_->fps), 0,
                                cfgPkt.data(), cfgPkt.size());
                }
            }

            if (p_->cbs.onClientConnected) p_->cbs.onClientConnected();
            if (p_->cbs.onIdrRequested) p_->cbs.onIdrRequested();

            std::vector<uint8_t> payload;
            while (p_->running.load() && p_->clientFd.load() == c) {
                Gt60Header hdr{};
                if (!recvAll(c, reinterpret_cast<uint8_t*>(&hdr), sizeof(hdr))) break;
                if (hdr.magic != kMagic || hdr.payloadSize > 1024 * 1024) break;
                payload.resize(hdr.payloadSize);
                if (hdr.payloadSize > 0 && !recvAll(c, payload.data(), hdr.payloadSize)) break;

                if (hdr.type == kTypeClientIdr && p_->cbs.onIdrRequested) {
                    p_->cbs.onIdrRequested();
                }
            }

            if (p_->clientFd.load() == c) {
                p_->closeClient();
            }
        }
    });

    return true;
}

void Gt60Server::stop() {
    p_->running.store(false);
    p_->closeClient();
    p_->queueCv.notify_all();

    if (p_->listenFd >= 0) {
        ::shutdown(p_->listenFd, SHUT_RDWR);
        ::close(p_->listenFd);
        p_->listenFd = -1;
    }
    if (p_->udpFd >= 0) {
        ::shutdown(p_->udpFd, SHUT_RDWR);
        ::close(p_->udpFd);
        p_->udpFd = -1;
    }
    if (p_->udpThread.joinable()) p_->udpThread.join();
    if (p_->acceptThread.joinable()) p_->acceptThread.join();
    if (p_->sendThread.joinable()) p_->sendThread.join();
}

void Gt60Server::setVideoConfig(uint32_t width, uint32_t height, uint32_t fps, const std::vector<uint8_t>& csd) {
    {
        std::lock_guard lk(p_->cfgMtx);
        p_->width = width;
        p_->height = height;
        p_->fps = fps;
        p_->csd = csd;
    }
    if (!csd.empty()) {
        auto cfgPkt = makeVideoConfigPayload(width, height, p_->codec, csd);
        p_->enqueue(kTypeVideoConfig, 0, static_cast<uint16_t>(fps), 0, cfgPkt.data(), cfgPkt.size());
    }
}

void Gt60Server::sendVideoAccessUnit(const uint8_t* data, size_t size, uint32_t timestampMs, bool isIdr) {
    p_->enqueue(kTypeVideoAu, isIdr ? kFlagIdr : 0, 0, timestampMs, data, size);
}

void Gt60Server::sendAudioPcm(const int16_t* interleavedStereo, size_t frameCount, uint32_t timestampMs) {
    if (!interleavedStereo || frameCount == 0) return;
    p_->enqueue(kTypeAudioPcm, 0, 0, timestampMs,
                reinterpret_cast<const uint8_t*>(interleavedStereo),
                frameCount * 2 * sizeof(int16_t));
}

bool Gt60Server::hasClient() const {
    return p_->clientFd.load() >= 0;
}

uint64_t Gt60Server::droppedVideoFrames() const {
    return p_->droppedVideo.load();
}

} // namespace gta60_linux

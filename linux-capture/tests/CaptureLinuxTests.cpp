#include "DuplicateDetector.hpp"
#include "Gt60Server.hpp"
#include "StreamWatchdog.hpp"
#include "VaapiEncoder.hpp"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

namespace {

#define CHECK(cond, msg)                                                                  \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__, (msg));        \
            return false;                                                                 \
        }                                                                                 \
    } while (0)

struct Gt60HeaderWire {
    uint32_t magic;
    uint8_t type;
    uint8_t flags;
    uint16_t meta;
    uint32_t payloadSize;
    uint32_t timestampMs;
};
static_assert(sizeof(Gt60HeaderWire) == 16);

bool sendAll(int fd, const uint8_t* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = ::send(fd, data + sent, len - sent, 0);
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

bool test1_DuplicateDetectorAlternationVsStatic() {
    constexpr uint32_t w = 128;
    constexpr uint32_t h = 64;
    std::vector<uint8_t> bufA(w * h, 40);
    std::vector<uint8_t> bufB(w * h, 140);
    std::vector<uint8_t> bufC(w * h, 220);

    auto frameFor = [&](const std::vector<uint8_t>& buf) {
        gta60_linux::CapturedFrame cf{};
        cf.data = buf.data();
        cf.size = buf.size();
        cf.width = w;
        cf.height = h;
        cf.stride = w;
        cf.format = gta60_linux::CapturePixelFormat::NV12;
        return cf;
    };

    gta60_linux::DuplicateDetector det;
    // 30fps alternating: A, A, B, B, C, C -> shouldTagDuplicate must alternate false, true, false, true...
    const std::vector<const std::vector<uint8_t>*> seq30 = {&bufA, &bufA, &bufB, &bufB, &bufC, &bufC};
    std::vector<bool> tags30;
    for (const auto* f : seq30) {
        bool dup = det.observe(frameFor(*f), 0.05);
        tags30.push_back(det.shouldTagDuplicate(dup, true));
    }
    CHECK(tags30.size() == 6, "size 6");
    CHECK(!tags30[0] && tags30[1] && !tags30[2] && tags30[3] && !tags30[4] && tags30[5],
          "30fps alternating cadence should tag every second frame");

    // Static menu: A, A, A, A -> only first dup (dupConsec == 1) is tagged; subsequent dupes are false.
    det.resetAll();
    std::vector<bool> tagsStatic;
    for (int i = 0; i < 4; ++i) {
        bool dup = det.observe(frameFor(bufA), 0.05);
        tagsStatic.push_back(det.shouldTagDuplicate(dup, true));
    }
    CHECK(!tagsStatic[0] && tagsStatic[1] && !tagsStatic[2] && !tagsStatic[3],
          "static menu should never tag dupConsec >= 2");

    std::printf("[PASS] Test1_DuplicateDetectorAlternationVsStatic\n");
    return true;
}

bool test2_Av1CodecConfigRecord() {
    const uint8_t rawObu[] = {0x0A, 0x0B, 0x00, 0x00, 0x12, 0x34};
    auto rec8 = gta60_linux::buildAv1CodecConfigRecord(rawObu, sizeof(rawObu), false);
    CHECK(rec8.size() == 4 + sizeof(rawObu), "8-bit av1C size mismatch");
    CHECK(rec8[0] == 0x81 && rec8[1] == 0x0D && rec8[2] == 0x0C && rec8[3] == 0x00,
          "8-bit av1C header mismatch");
    CHECK(std::memcmp(rec8.data() + 4, rawObu, sizeof(rawObu)) == 0, "8-bit av1C payload mismatch");

    auto rec10 = gta60_linux::buildAv1CodecConfigRecord(rawObu, sizeof(rawObu), true);
    CHECK(rec10.size() == 4 + sizeof(rawObu), "10-bit av1C size mismatch");
    CHECK(rec10[0] == 0x81 && rec10[1] == 0x0D && rec10[2] == 0x4C && rec10[3] == 0x00,
          "10-bit av1C header mismatch");

    // Idempotent when already prefixed with 0x81
    auto recIdemp = gta60_linux::buildAv1CodecConfigRecord(rec10.data(), rec10.size(), true);
    CHECK(recIdemp == rec10, "av1C wrapping must be idempotent");

    std::printf("[PASS] Test2_Av1CodecConfigRecord\n");
    return true;
}

bool test3_Gt60ServerDiscoveryAndDynamicConfig() {
    constexpr uint16_t tcpPort = 17660;
    constexpr uint16_t udpPort = 17661;

    std::atomic<bool> gotCfg{false};
    gta60_linux::ClientStreamConfig recvCfg{};
    std::atomic<bool> connected{false};
    std::atomic<bool> gotDump{false};
    int dumpSec = 0;
    std::string dumpPath;

    gta60_linux::Gt60Server srv;
    gta60_linux::Gt60Server::Callbacks cbs;
    cbs.onClientConnected = [&]() { connected.store(true); };
    cbs.onConfigRequested = [&](const gta60_linux::ClientStreamConfig& cfg) {
        recvCfg = cfg;
        gotCfg.store(true);
    };
    cbs.onDumpRequested = [&](int sec, const std::string& path) {
        dumpSec = sec;
        dumpPath = path;
        gotDump.store(true);
    };

    const std::vector<uint8_t> initCsd = {0x81, 0x0D, 0x0C, 0x00, 0x0A, 0x01};
    CHECK(srv.start(tcpPort, udpPort, 2560, 1440, 60,
                    gta60_linux::VideoCodec::AV1, initCsd, std::move(cbs)),
          "Gt60Server::start failed");

    // 1. UDP discovery check
    {
        int ufd = ::socket(AF_INET, SOCK_DGRAM, 0);
        CHECK(ufd >= 0, "udp socket failed");
        timeval tv{1, 0};
        ::setsockopt(ufd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(udpPort);
        ::inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
        const char* q = "GTA60_DISCOVER";
        ::sendto(ufd, q, std::strlen(q), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
        char buf[256]{};
        ssize_t n = ::recvfrom(ufd, buf, sizeof(buf) - 1, 0, nullptr, nullptr);
        ::close(ufd);
        CHECK(n > 0, "did not receive UDP offer");
        std::string offer(buf, static_cast<size_t>(n));
        CHECK(offer.find("GTA60_OFFER|17660|2560|1440|60|AV1|") == 0, "offer format mismatch");
    }

    // 1b. UDP GTA60_DUMP command check
    {
        int ufd = ::socket(AF_INET, SOCK_DGRAM, 0);
        CHECK(ufd >= 0, "udp dump socket failed");
        timeval tv{1, 0};
        ::setsockopt(ufd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(udpPort);
        ::inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
        const char* cmd = "GTA60_DUMP|12|/tmp/test_dump.hevc";
        ::sendto(ufd, cmd, std::strlen(cmd), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
        char buf[64]{};
        ssize_t n = ::recvfrom(ufd, buf, sizeof(buf) - 1, 0, nullptr, nullptr);
        ::close(ufd);
        CHECK(n > 0 && std::string(buf, static_cast<size_t>(n)) == "GTA60_DUMP_ACK", "dump ack mismatch");
        CHECK(gotDump.load() && dumpSec == 12 && dumpPath == "/tmp/test_dump.hevc", "dump callback mismatch");
    }

    // 2. TCP round-trip: initial VideoConfig, VideoAU with kFlagDuplicate, 14-byte ClientConfig -> live VideoConfig switch
    int cfd = ::socket(AF_INET, SOCK_STREAM, 0);
    CHECK(cfd >= 0, "tcp socket failed");
    timeval tv{2, 0};
    ::setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(tcpPort);
    ::inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    CHECK(::connect(cfd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0, "tcp connect failed");

    for (int i = 0; i < 50 && !connected.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    CHECK(connected.load(), "server did not observe client connect");

    Gt60HeaderWire h1{};
    CHECK(recvAll(cfd, reinterpret_cast<uint8_t*>(&h1), sizeof(h1)), "recv cfg header");
    CHECK(h1.magic == gta60_linux::Gt60Server::kMagic && h1.type == gta60_linux::Gt60Server::kTypeVideoConfig,
          "expected initial kTypeVideoConfig");
    std::vector<uint8_t> p1(h1.payloadSize);
    CHECK(recvAll(cfd, p1.data(), p1.size()), "recv cfg payload");
    CHECK(p1.size() == 9 + initCsd.size() && p1[8] == 2, "expected codecWire=2 (AV1)");

    const uint8_t fakeAu[] = {0x12, 0x34, 0x56, 0x78};
    srv.sendVideoAccessUnit(fakeAu, sizeof(fakeAu), 100, true, true);
    Gt60HeaderWire h2{};
    CHECK(recvAll(cfd, reinterpret_cast<uint8_t*>(&h2), sizeof(h2)), "recv au header");
    CHECK(h2.type == gta60_linux::Gt60Server::kTypeVideoAu, "expected kTypeVideoAu");
    CHECK((h2.flags & gta60_linux::Gt60Server::kFlagIdr) != 0, "expected IDR flag");
    CHECK((h2.flags & gta60_linux::Gt60Server::kFlagDuplicate) != 0, "expected Duplicate flag");
    std::vector<uint8_t> p2(h2.payloadSize);
    CHECK(recvAll(cfd, p2.data(), p2.size()), "recv au payload");

    // Send 14-byte ClientStreamConfig requesting 160 Mbps HEVC 10-bit
    uint8_t cfgReq[sizeof(Gt60HeaderWire) + 14]{};
    Gt60HeaderWire reqHdr{gta60_linux::Gt60Server::kMagic, gta60_linux::Gt60Server::kTypeClientConfig, 0, 0, 14, 0};
    std::memcpy(cfgReq, &reqHdr, sizeof(reqHdr));
    uint32_t rw = 2560, rh = 1440;
    uint16_t rfps = 60, rmbps = 160;
    std::memcpy(cfgReq + 16 + 0, &rw, 4);
    std::memcpy(cfgReq + 16 + 4, &rh, 4);
    std::memcpy(cfgReq + 16 + 8, &rfps, 2);
    std::memcpy(cfgReq + 16 + 10, &rmbps, 2);
    cfgReq[16 + 12] = 1;    // HEVC
    cfgReq[16 + 13] = 0x81; // valid + 10-bit
    CHECK(sendAll(cfd, cfgReq, sizeof(cfgReq)), "send 14B client config");

    for (int i = 0; i < 50 && !gotCfg.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    CHECK(gotCfg.load(), "onConfigRequested did not fire");
    CHECK(recvCfg.bitrateMbps == 160 && recvCfg.codecWire == 1 && recvCfg.flags == 0x81,
          "decoded ClientStreamConfig mismatch");

    const std::vector<uint8_t> hevcCsd = {0x00, 0x00, 0x00, 0x01, 0x40, 0x01};
    srv.setVideoConfig(2560, 1440, 60, gta60_linux::VideoCodec::HEVC, hevcCsd);
    Gt60HeaderWire h3{};
    CHECK(recvAll(cfd, reinterpret_cast<uint8_t*>(&h3), sizeof(h3)), "recv switched cfg header");
    CHECK(h3.type == gta60_linux::Gt60Server::kTypeVideoConfig, "expected switched VideoConfig");
    std::vector<uint8_t> p3(h3.payloadSize);
    CHECK(recvAll(cfd, p3.data(), p3.size()), "recv switched cfg payload");
    CHECK(p3[8] == 1, "expected switched codecWire=1 (HEVC)");

    ::close(cfd);
    srv.stop();
    std::printf("[PASS] Test3_Gt60ServerDiscoveryAndDynamicConfig\n");
    return true;
}

bool test4_VaapiHardwareMatrix() {
    if (::access("/dev/dri/renderD128", R_OK | W_OK) != 0) {
        std::printf("[SKIP] Test4_VaapiHardwareMatrix (/dev/dri/renderD128 not accessible)\n");
        return true;
    }

    struct Case { gta60_linux::VideoCodec codec; bool tenBit; };
    const Case cases[] = {
        {gta60_linux::VideoCodec::HEVC, false},
        {gta60_linux::VideoCodec::HEVC, true},
        {gta60_linux::VideoCodec::AV1, false},
        {gta60_linux::VideoCodec::H264, false},
    };

    for (const auto& c : cases) {
        gta60_linux::EncoderConfig cfg{};
        cfg.width = 1280;
        cfg.height = 720;
        cfg.fps = 60;
        cfg.bitrateMbps = 50;
        cfg.codec = c.codec;
        cfg.tenBit = c.tenBit;
        cfg.rcMode = gta60_linux::RateControlMode::QVBR;
        cfg.quality = 20;

        gta60_linux::VaapiEncoder enc;
        CHECK(enc.init(cfg), "VaapiEncoder::init failed");
        CHECK(!enc.csd().empty(), "csd empty");
        if (c.codec == gta60_linux::VideoCodec::AV1) {
            CHECK(enc.csd().size() >= 4 && enc.csd()[0] == 0x81, "AV1 csd must have av1C header");
        }

        std::vector<uint8_t> y(cfg.width * cfg.height, 100);
        std::vector<uint8_t> uv(cfg.width * (cfg.height / 2), 128);
        int pkts = 0;
        for (int i = 0; i < 4; ++i) {
            std::memset(y.data(), 60 + i * 20, cfg.width * 8);
            CHECK(enc.encodeNv12(y.data(), static_cast<int>(cfg.width),
                                 uv.data(), static_cast<int>(cfg.width),
                                 i, [&](const uint8_t*, size_t sz, bool) {
                                     if (sz > 0) ++pkts;
                                 }),
                  "encodeNv12 failed");
        }
        CHECK(pkts > 0, "no encoded packets produced");
    }

    std::printf("[PASS] Test4_VaapiHardwareMatrix\n");
    return true;
}

bool test5_StreamWatchdogStalls() {
    using namespace std::chrono_literals;
    using Watchdog = gta60_linux::StreamWatchdog;

    const auto t0 = Watchdog::Clock::now();
    Watchdog::Config cfg;
    cfg.frameStall = 2000ms;
    cfg.encodeStall = 2000ms;
    cfg.cooldown = 1000ms;
    cfg.maxConsecutiveFailures = 2;

    // No viewer: a silent card is normal, never a fault.
    {
        Watchdog idle(cfg);
        idle.reset(t0);
        CHECK(idle.poll(t0 + 10s, false) == Watchdog::Action::None, "idle must not trip");
        CHECK(idle.poll(t0 + 30s, false) == Watchdog::Action::None, "idle must stay quiet");
    }

    Watchdog wd(cfg);
    wd.reset(t0);

    // Viewer attached, frames stop -> capture recovery, rate limited by cooldown.
    CHECK(wd.poll(t0 + 1500ms, true) == Watchdog::Action::None, "1.5s must not trip yet");
    CHECK(wd.poll(t0 + 2500ms, true) == Watchdog::Action::RecoverCapture,
          "2.5s without frames must trip capture recovery");
    CHECK(wd.poll(t0 + 2600ms, true) == Watchdog::Action::None, "cooldown must suppress a repeat action");

    // Frames resume: failure streak cleared, healthy pipe stays quiet.
    wd.noteFrame(t0 + 3000ms);
    wd.notePacket(t0 + 3000ms);
    CHECK(wd.frameFailures() == 0, "noteFrame must reset the frame failure count");
    CHECK(wd.poll(t0 + 4200ms, true) == Watchdog::Action::None, "healthy frames must not trip");

    // Frames arrive but the encoder produces nothing -> encoder recovery.
    wd.reset(t0);
    wd.noteFrame(t0 + 2200ms);
    CHECK(wd.poll(t0 + 2300ms, true) == Watchdog::Action::RecoverEncoder,
          "frames without encoded packets must trip encoder recovery");
    wd.notePacket(t0 + 2400ms);
    CHECK(wd.encodeFailures() == 0, "notePacket must reset the encode failure count");

    // Permanent failure escalates instead of spinning forever.
    wd.reset(t0);
    int recoveries = 0;
    auto t = t0;
    for (int i = 0; i < 10; ++i) {
        t += 2500ms;
        if (wd.poll(t, true) == Watchdog::Action::RecoverCapture) ++recoveries;
        t += 1100ms; // step past the cooldown window
    }
    CHECK(recoveries >= 3, "watchdog must keep retrying a dead capture");
    CHECK(wd.exhausted(), "watchdog must report exhaustion after maxConsecutiveFailures");

    // The viewer leaving resets the escalation so a later session gets a clean slate.
    wd.poll(t + 10s, false);
    CHECK(!wd.exhausted(), "disconnect must clear exhaustion");

    std::printf("[PASS] Test5_StreamWatchdogStalls\n");
    return true;
}

} // namespace

int main() {
    if (!test1_DuplicateDetectorAlternationVsStatic()) return 1;
    if (!test2_Av1CodecConfigRecord()) return 1;
    if (!test3_Gt60ServerDiscoveryAndDynamicConfig()) return 1;
    if (!test4_VaapiHardwareMatrix()) return 1;
    if (!test5_StreamWatchdogStalls()) return 1;
    std::printf("\nALL LINUX CAPTURE TESTS PASSED\n");
    return 0;
}

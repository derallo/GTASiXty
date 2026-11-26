#include "AlsaCapture.hpp"
#include "DuplicateDetector.hpp"
#include "Gt60Server.hpp"
#include "StreamWatchdog.hpp"
#include "V4l2Capture.hpp"
#include "VaapiEncoder.hpp"

#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

std::atomic<bool> gRunning{true};

void handleSignal(int) {
    gRunning.store(false);
}

struct Options {
    std::string videoDevice = "/dev/video0";
    std::string audioDevice = "default";
    std::string drmDevice = "/dev/dri/renderD128";
    uint32_t width = 2560;
    uint32_t height = 1440;
    uint32_t fps = 60;
    uint32_t bitrateMbps = 100;
    gta60_linux::VideoCodec codec = gta60_linux::VideoCodec::HEVC;
    bool tenBit = false;
    gta60_linux::RateControlMode rcMode = gta60_linux::RateControlMode::QVBR;
    int quality = 15;
    uint16_t tcpPort = 7660;
    uint16_t udpPort = 7661;
    double dupThreshold = 0.05;
    bool tagDuplicates = true;
    int watchdogStallMs = 3000;
    bool watchdogEnabled = true;
    std::string dumpBitstreamPath;
    int dumpSeconds = 15;
    bool selfTest = false;
    bool selfTestAll = false;
};

bool parseCodecArg(const std::string& c, gta60_linux::VideoCodec& outCodec, bool& outTenBit) {
    if (c == "h264" || c == "H264" || c == "avc") {
        outCodec = gta60_linux::VideoCodec::H264;
        outTenBit = false;
        return true;
    }
    if (c == "hevc" || c == "HEVC" || c == "h265") {
        outCodec = gta60_linux::VideoCodec::HEVC;
        return true;
    }
    if (c == "hevc10" || c == "HEVC10" || c == "h265-10") {
        outCodec = gta60_linux::VideoCodec::HEVC;
        outTenBit = true;
        return true;
    }
    if (c == "av1" || c == "AV1") {
        outCodec = gta60_linux::VideoCodec::AV1;
        return true;
    }
    if (c == "av1-10" || c == "AV1-10" || c == "av1_10") {
        outCodec = gta60_linux::VideoCodec::AV1;
        outTenBit = true;
        return true;
    }
    return false;
}

bool parseRcModeArg(const std::string& m, gta60_linux::RateControlMode& outRc) {
    if (m == "qvbr" || m == "QVBR") { outRc = gta60_linux::RateControlMode::QVBR; return true; }
    if (m == "vbr" || m == "VBR") { outRc = gta60_linux::RateControlMode::VBR; return true; }
    if (m == "cbr" || m == "CBR") { outRc = gta60_linux::RateControlMode::CBR; return true; }
    if (m == "cqp" || m == "CQP") { outRc = gta60_linux::RateControlMode::CQP; return true; }
    if (m == "auto" || m == "AUTO") { outRc = gta60_linux::RateControlMode::Auto; return true; }
    return false;
}

bool parseArgs(int argc, char** argv, Options& opt) {
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto needVal = [&](const char* flag) -> const char* {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "Missing value for %s\n", flag);
                return nullptr;
            }
            return argv[++i];
        };
        if (a == "--device") {
            const char* v = needVal("--device");
            if (!v) return false;
            opt.videoDevice = v;
        } else if (a == "--audio") {
            const char* v = needVal("--audio");
            if (!v) return false;
            opt.audioDevice = v;
        } else if (a == "--drm") {
            const char* v = needVal("--drm");
            if (!v) return false;
            opt.drmDevice = v;
        } else if (a == "--width") {
            const char* v = needVal("--width");
            if (!v) return false;
            opt.width = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--height") {
            const char* v = needVal("--height");
            if (!v) return false;
            opt.height = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--fps") {
            const char* v = needVal("--fps");
            if (!v) return false;
            opt.fps = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--bitrate") {
            const char* v = needVal("--bitrate");
            if (!v) return false;
            opt.bitrateMbps = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--codec") {
            const char* v = needVal("--codec");
            if (!v) return false;
            if (!parseCodecArg(v, opt.codec, opt.tenBit)) {
                std::fprintf(stderr, "Unknown codec '%s' (use hevc, hevc10, av1, av1-10, or h264)\n", v);
                return false;
            }
        } else if (a == "--10bit") {
            opt.tenBit = true;
        } else if (a == "--rc-mode") {
            const char* v = needVal("--rc-mode");
            if (!v) return false;
            if (!parseRcModeArg(v, opt.rcMode)) {
                std::fprintf(stderr, "Unknown rc-mode '%s' (use qvbr, vbr, cbr, or cqp)\n", v);
                return false;
            }
        } else if (a == "--quality") {
            const char* v = needVal("--quality");
            if (!v) return false;
            opt.quality = std::clamp(std::stoi(v), 1, 51);
        } else if (a == "--port") {
            const char* v = needVal("--port");
            if (!v) return false;
            opt.tcpPort = static_cast<uint16_t>(std::stoul(v));
        } else if (a == "--dup-threshold") {
            const char* v = needVal("--dup-threshold");
            if (!v) return false;
            opt.dupThreshold = std::stod(v);
        } else if (a == "--no-dup-flag") {
            opt.tagDuplicates = false;
        } else if (a == "--watchdog-stall-ms") {
            const char* v = needVal("--watchdog-stall-ms");
            if (!v) return false;
            opt.watchdogStallMs = std::clamp(std::stoi(v), 250, 60000);
        } else if (a == "--no-watchdog") {
            opt.watchdogEnabled = false;
        } else if (a == "--dump-bitstream") {
            const char* v = needVal("--dump-bitstream");
            if (!v) return false;
            opt.dumpBitstreamPath = v;
        } else if (a == "--dump-seconds") {
            const char* v = needVal("--dump-seconds");
            if (!v) return false;
            opt.dumpSeconds = std::clamp(std::stoi(v), 1, 120);
        } else if (a == "--selftest") {
            opt.selfTest = true;
        } else if (a == "--selftest-all") {
            opt.selfTest = true;
            opt.selfTestAll = true;
        } else if (a == "--help" || a == "-h") {
            std::printf("Usage: gta60-capture [options]\n"
                        "  --device <path>         V4L2 device (default /dev/video0)\n"
                        "  --audio <alsa>          ALSA capture device or 'none' (default default)\n"
                        "  --drm <path>            VA-API DRM render node (default /dev/dri/renderD128)\n"
                        "  --width <px>            Target width (default 2560)\n"
                        "  --height <px>           Target height (default 1440)\n"
                        "  --fps <hz>              Target fps (default 60)\n"
                        "  --bitrate <mbps>        Target bitrate in Mbps (default 100)\n"
                        "  --codec <name>          hevc|hevc10|av1|av1-10|h264 (default hevc)\n"
                        "  --10bit                 Enable 10-bit P010 encoding (HEVC Main10 / AV1)\n"
                        "  --rc-mode <mode>        qvbr|vbr|cbr|cqp (default qvbr)\n"
                        "  --quality <1..51>       QVBR global_quality / CQP qp (default 15)\n"
                        "  --port <tcp>            GT60 TCP port (default 7660)\n"
                        "  --dup-threshold <mad>   Luma MAD threshold for duplicate flag (default 0.05)\n"
                        "  --no-dup-flag           Log duplicate stats without setting GT60 bit2 flag\n"
                        "  --watchdog-stall-ms <ms> Reopen capture after this long with no frame (default 3000)\n"
                        "  --no-watchdog           Disable the stalled-pipeline watchdog\n"
                        "  --dump-bitstream <path> Dump encoded bitstream + .csv for --dump-seconds\n"
                        "  --dump-seconds <sec>    Duration in seconds for --dump-bitstream (default 15)\n"
                        "  --selftest              Run VA-API (+ V4L2 if present) self-test and exit\n"
                        "  --selftest-all          Test HEVC QVBR, HEVC 10-bit, AV1, and H264 VA-API\n");
            std::exit(0);
        } else {
            std::fprintf(stderr, "Unknown argument: %s\n", a.c_str());
            return false;
        }
    }
    return true;
}

bool runSingleVaapiSelfTest(const gta60_linux::EncoderConfig& cfg) {
    std::printf("[selftest] Testing VA-API %s%s (%s q=%d) %ux%u@%u (%u Mbps) on %s...\n",
                gta60_linux::videoCodecName(cfg.codec),
                cfg.tenBit ? " 10-bit" : "",
                gta60_linux::rateControlModeName(cfg.rcMode),
                cfg.quality,
                cfg.width, cfg.height, cfg.fps, cfg.bitrateMbps, cfg.drmDevice.c_str());

    gta60_linux::VaapiEncoder enc;
    if (!enc.init(cfg)) {
        std::fprintf(stderr, "[selftest] FAIL: VaapiEncoder::init failed\n");
        return false;
    }
    if (enc.csd().empty()) {
        std::fprintf(stderr, "[selftest] FAIL: encoder produced empty CSD/extradata\n");
        return false;
    }

    std::vector<uint8_t> yPlane(cfg.width * cfg.height, 128);
    std::vector<uint8_t> uvPlane(cfg.width * (cfg.height / 2), 128);

    constexpr int kFrames = 15;
    int encodedPackets = 0;
    int idrCount = 0;
    size_t totalBytes = 0;
    const auto t0 = std::chrono::steady_clock::now();

    for (int i = 0; i < kFrames; ++i) {
        std::memset(yPlane.data(), static_cast<int>((16 + i * 12) & 0xFF), cfg.width * 16);
        if (!enc.encodeNv12(yPlane.data(), static_cast<int>(cfg.width),
                            uvPlane.data(), static_cast<int>(cfg.width),
                            i, [&](const uint8_t*, size_t sz, bool isIdr) {
                                ++encodedPackets;
                                if (isIdr) ++idrCount;
                                totalBytes += sz;
                            })) {
            std::fprintf(stderr, "[selftest] FAIL: encodeNv12 failed at frame %d\n", i);
            return false;
        }
    }

    const double elapsedMs = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - t0).count();
    if (encodedPackets == 0 || idrCount == 0 || totalBytes == 0) {
        std::fprintf(stderr, "[selftest] FAIL: packets=%d idr=%d bytes=%zu\n",
                     encodedPackets, idrCount, totalBytes);
        return false;
    }

    std::printf("[selftest] VA-API PASS (%s%s rc=%s): csd=%zuB packets=%d idr=%d total=%zuB avg=%.2f ms/frame\n",
                gta60_linux::videoCodecName(cfg.codec),
                cfg.tenBit ? " 10-bit" : "",
                enc.activeRcModeName(),
                enc.csd().size(), encodedPackets, idrCount, totalBytes, elapsedMs / kFrames);
    return true;
}

int runSelfTest(const Options& opt) {
    auto makeCfg = [&](gta60_linux::VideoCodec codec, bool tenBit) {
        gta60_linux::EncoderConfig cfg{};
        cfg.width = opt.width;
        cfg.height = opt.height;
        cfg.fps = opt.fps;
        cfg.bitrateMbps = opt.bitrateMbps;
        cfg.codec = codec;
        cfg.tenBit = tenBit;
        cfg.rcMode = opt.rcMode;
        cfg.quality = opt.quality;
        cfg.drmDevice = opt.drmDevice;
        return cfg;
    };

    if (opt.selfTestAll) {
        const struct { gta60_linux::VideoCodec codec; bool tenBit; } cases[] = {
            {gta60_linux::VideoCodec::HEVC, false},
            {gta60_linux::VideoCodec::HEVC, true},
            {gta60_linux::VideoCodec::AV1, false},
            {gta60_linux::VideoCodec::H264, false},
        };
        for (const auto& c : cases) {
            if (!runSingleVaapiSelfTest(makeCfg(c.codec, c.tenBit))) return 1;
        }
    } else {
        if (!runSingleVaapiSelfTest(makeCfg(opt.codec, opt.tenBit))) return 1;
    }

    if (::access(opt.videoDevice.c_str(), F_OK) == 0) {
        gta60_linux::V4l2Capture cap;
        if (!cap.open(opt.videoDevice, opt.width, opt.height, opt.fps)) {
            std::fprintf(stderr, "[selftest] FAIL: V4L2 device %s exists but failed to open\n",
                         opt.videoDevice.c_str());
            return 1;
        }
        int grabbed = 0;
        for (int attempt = 0; attempt < 20 && grabbed < 5; ++attempt) {
            cap.grabFrame(250, [&](const gta60_linux::CapturedFrame&) { ++grabbed; });
        }
        if (grabbed == 0) {
            std::fprintf(stderr, "[selftest] FAIL: V4L2 opened %s but no frames arrived\n",
                         opt.videoDevice.c_str());
            return 1;
        }
        std::printf("[selftest] V4L2 PASS: %s %ux%u@%u grabbed=%d\n",
                    opt.videoDevice.c_str(), cap.width(), cap.height(), cap.fps(), grabbed);
    } else {
        std::printf("[selftest] V4L2 skipped (no device at %s yet)\n", opt.videoDevice.c_str());
    }

    return 0;
}

struct BitstreamDumper {
    std::mutex mtx;
    bool pendingStart = false;
    bool active = false;
    std::string targetPath;
    int durationSec = 15;
    FILE* fp = nullptr;
    FILE* csvFp = nullptr;
    gta60_linux::VideoCodec activeCodec = gta60_linux::VideoCodec::HEVC;
    uint64_t frameIdx = 0;
    std::chrono::steady_clock::time_point deadline{};

    ~BitstreamDumper() { stop(); }

    static void writeLe16(uint8_t* p, uint16_t v) {
        p[0] = static_cast<uint8_t>(v & 0xFF);
        p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    }
    static void writeLe32(uint8_t* p, uint32_t v) {
        p[0] = static_cast<uint8_t>(v & 0xFF);
        p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
        p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
    }
    static void writeLe64(uint8_t* p, uint64_t v) {
        for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>((v >> (8 * i)) & 0xFF);
    }

    void closeLocked() {
        if (fp) {
            std::fclose(fp);
            fp = nullptr;
        }
        if (csvFp) {
            std::fclose(csvFp);
            csvFp = nullptr;
        }
        active = false;
    }

    bool isPendingOrActive() {
        std::lock_guard lk(mtx);
        return pendingStart || active;
    }

    void stop() {
        std::lock_guard lk(mtx);
        pendingStart = false;
        if (active) {
            std::printf("[dump] saved %llu frames to %s\n",
                        static_cast<unsigned long long>(frameIdx), targetPath.c_str());
            std::fflush(stdout);
        }
        closeLocked();
    }

    void arm(const std::string& path, int seconds) {
        std::lock_guard lk(mtx);
        closeLocked();
        targetPath = path;
        durationSec = std::clamp(seconds, 1, 120);
        frameIdx = 0;
        pendingStart = true;
        std::printf("[dump] armed %s (%ds, waiting for IDR)\n", targetPath.c_str(), durationSec);
        std::fflush(stdout);
    }

    void onPacket(gta60_linux::VideoCodec codec, const std::vector<uint8_t>& csd,
                  uint32_t w, uint32_t h, uint32_t fps,
                  const uint8_t* data, size_t size, uint32_t tsMs, bool isIdr, bool isDup) {
        std::lock_guard lk(mtx);
        if (!pendingStart && !active) return;

        if (pendingStart) {
            if (!isIdr) return;
            fp = std::fopen(targetPath.c_str(), "wb");
            const std::string csvPath = targetPath + ".csv";
            csvFp = std::fopen(csvPath.c_str(), "w");
            if (!fp || !csvFp) {
                std::fprintf(stderr, "[dump] failed to open %s\n", targetPath.c_str());
                pendingStart = false;
                closeLocked();
                return;
            }
            std::fprintf(csvFp, "frameIdx,timestampMs,isIdr,isDup,bytes\n");
            activeCodec = codec;
            frameIdx = 0;
            if (codec == gta60_linux::VideoCodec::AV1) {
                uint8_t hdr[32]{};
                std::memcpy(hdr, "DKIF", 4);
                writeLe16(hdr + 4, 0);
                writeLe16(hdr + 6, 32);
                std::memcpy(hdr + 8, "AV01", 4);
                writeLe16(hdr + 12, static_cast<uint16_t>(w));
                writeLe16(hdr + 14, static_cast<uint16_t>(h));
                writeLe32(hdr + 16, fps > 0 ? fps : 60);
                writeLe32(hdr + 20, 1);
                std::fwrite(hdr, 1, sizeof(hdr), fp);
            } else if (!csd.empty()) {
                std::fwrite(csd.data(), 1, csd.size(), fp);
            }
            pendingStart = false;
            active = true;
            deadline = std::chrono::steady_clock::now() + std::chrono::seconds(durationSec);
        }

        if (!active || !fp) return;

        if (activeCodec == gta60_linux::VideoCodec::AV1) {
            const uint8_t* seq = nullptr;
            size_t seqLen = 0;
            if (frameIdx == 0 && csd.size() > 4 && csd[0] == 0x81) {
                seq = csd.data() + 4;
                seqLen = csd.size() - 4;
            }
            uint8_t fhdr[12]{};
            writeLe32(fhdr, static_cast<uint32_t>(seqLen + size));
            writeLe64(fhdr + 4, frameIdx);
            std::fwrite(fhdr, 1, sizeof(fhdr), fp);
            if (seqLen > 0) std::fwrite(seq, 1, seqLen, fp);
            std::fwrite(data, 1, size, fp);
        } else {
            std::fwrite(data, 1, size, fp);
        }
        if (csvFp) {
            std::fprintf(csvFp, "%llu,%u,%d,%d,%zu\n",
                         static_cast<unsigned long long>(frameIdx), tsMs,
                         isIdr ? 1 : 0, isDup ? 1 : 0, size);
        }
        ++frameIdx;

        if (std::chrono::steady_clock::now() >= deadline) {
            std::printf("[dump] saved %llu frames to %s\n",
                        static_cast<unsigned long long>(frameIdx), targetPath.c_str());
            std::fflush(stdout);
            closeLocked();
        }
    }
};

} // namespace

int main(int argc, char** argv) {
    Options opt;
    if (!parseArgs(argc, argv, opt)) return 2;

    if (opt.selfTest) {
        return runSelfTest(opt);
    }

    std::signal(SIGINT, handleSignal);
    std::signal(SIGTERM, handleSignal);

    gta60_linux::V4l2Capture cap;
    if (!cap.open(opt.videoDevice, opt.width, opt.height, opt.fps)) {
        return 1;
    }

    gta60_linux::EncoderConfig activeCfg{};
    activeCfg.width = cap.width();
    activeCfg.height = cap.height();
    activeCfg.fps = cap.fps();
    activeCfg.bitrateMbps = opt.bitrateMbps;
    activeCfg.codec = opt.codec;
    activeCfg.tenBit = opt.tenBit;
    activeCfg.rcMode = opt.rcMode;
    activeCfg.quality = opt.quality;
    activeCfg.drmDevice = opt.drmDevice;

    gta60_linux::VaapiEncoder enc;
    if (!enc.init(activeCfg)) {
        return 1;
    }
    activeCfg = enc.config();

    struct PendingReconfig {
        std::mutex mtx;
        bool pending = false;
        uint32_t bitrateMbps = 100;
        gta60_linux::VideoCodec codec = gta60_linux::VideoCodec::HEVC;
        bool tenBit = false;
    } reconfig;
    reconfig.bitrateMbps = activeCfg.bitrateMbps;
    reconfig.codec = activeCfg.codec;
    reconfig.tenBit = activeCfg.tenBit;

    BitstreamDumper dumper;
    if (!opt.dumpBitstreamPath.empty()) {
        dumper.arm(opt.dumpBitstreamPath, opt.dumpSeconds);
    }

    gta60_linux::Gt60Server server;
    gta60_linux::Gt60Server::Callbacks cbs;
    cbs.onClientConnected = []() {
        std::printf("[gt60] Quest client connected\n");
        std::fflush(stdout);
    };
    cbs.onIdrRequested = [&enc]() {
        enc.requestIdr();
    };
    cbs.onDumpRequested = [&dumper, &enc](int seconds, const std::string& path) {
        dumper.arm(path, seconds);
        enc.requestIdr();
    };
    cbs.onConfigRequested = [&](const gta60_linux::ClientStreamConfig& cfg) {
        std::lock_guard lk(reconfig.mtx);
        if (cfg.bitrateMbps >= 10 && cfg.bitrateMbps <= 250) {
            reconfig.bitrateMbps = cfg.bitrateMbps;
        }
        if ((cfg.flags & 0x80) != 0) {
            if (cfg.codecWire == 0) reconfig.codec = gta60_linux::VideoCodec::H264;
            else if (cfg.codecWire == 1) reconfig.codec = gta60_linux::VideoCodec::HEVC;
            else if (cfg.codecWire == 2) reconfig.codec = gta60_linux::VideoCodec::AV1;
            reconfig.tenBit = (cfg.flags & 0x01) != 0 && reconfig.codec != gta60_linux::VideoCodec::H264;
        }
        reconfig.pending = true;
    };

    if (!server.start(opt.tcpPort, opt.udpPort, cap.width(), cap.height(), cap.fps(),
                      activeCfg.codec, enc.csd(), std::move(cbs))) {
        std::fprintf(stderr, "[gt60] failed to bind TCP %u / UDP %u\n", opt.tcpPort, opt.udpPort);
        return 1;
    }

    gta60_linux::AlsaCapture audio;
    audio.start(opt.audioDevice, [&server](const int16_t* pcm, size_t frames, uint32_t tsMs) {
        if (server.hasClient()) {
            server.sendAudioPcm(pcm, frames, tsMs);
        }
    });

    std::printf("[gta60-capture] streaming %s %ux%u@%u %s%s (%u Mbps %s q=%d, dupThresh=%.2f tagDup=%s) on TCP %u / UDP %u\n",
                opt.videoDevice.c_str(), cap.width(), cap.height(), cap.fps(),
                gta60_linux::videoCodecName(activeCfg.codec),
                activeCfg.tenBit ? " 10-bit" : "",
                activeCfg.bitrateMbps,
                enc.activeRcModeName(),
                activeCfg.quality,
                opt.dupThreshold, opt.tagDuplicates ? "on" : "off",
                opt.tcpPort, opt.udpPort);
    std::fflush(stdout);

    gta60_linux::DuplicateDetector dupDet;
    int64_t pts = 0;

    // The Elgato can re-enumerate on USB while ALSA audio keeps running, which leaves the Quest
    // connected to a live audio stream with a dead video pipe (black panel, sound only) that
    // never recovers on its own. Reopen the capture device when frames stop arriving.
    gta60_linux::StreamWatchdog watchdog(gta60_linux::StreamWatchdog::Config{
        .frameStall = std::chrono::milliseconds(opt.watchdogStallMs),
        .encodeStall = std::chrono::milliseconds(opt.watchdogStallMs),
        .cooldown = std::chrono::milliseconds(std::max(500, opt.watchdogStallMs / 2)),
        .maxConsecutiveFailures = 3,
    });
    watchdog.reset(std::chrono::steady_clock::now());

    auto rearmEncoder = [&](uint32_t w, uint32_t h, uint32_t fps) {
        gta60_linux::EncoderConfig next = activeCfg;
        next.width = w;
        next.height = h;
        next.fps = fps;
        if (enc.init(next)) {
            activeCfg = enc.config();
        } else {
            std::fprintf(stderr, "[watchdog] encoder re-init kept previous %s config\n",
                         gta60_linux::videoCodecName(activeCfg.codec));
        }
        server.setVideoConfig(w, h, fps, activeCfg.codec, enc.csd());
        enc.requestIdr();
    };

    auto recoverCapture = [&]() -> bool {
        std::printf("[watchdog] no video frame for %lld ms; reopening %s\n",
                    watchdog.lastStallMs(), opt.videoDevice.c_str());
        std::fflush(stdout);
        cap.close();
        for (int attempt = 1; attempt <= 6 && gRunning.load(); ++attempt) {
            if (cap.open(opt.videoDevice, opt.width, opt.height, opt.fps)) {
                dupDet.resetAll();
                rearmEncoder(cap.width(), cap.height(), cap.fps());
                std::printf("[watchdog] capture reopened (%ux%u@%u); encoder re-armed, IDR requested\n",
                            cap.width(), cap.height(), cap.fps());
                std::fflush(stdout);
                return true;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
        std::fprintf(stderr, "[watchdog] capture reopen failed after 6 attempts\n");
        return false;
    };

    auto recoverEncoder = [&]() -> bool {
        std::fprintf(stderr, "[watchdog] encoder silent for %lld ms; reinitializing %s\n",
                     watchdog.lastStallMs(), gta60_linux::videoCodecName(activeCfg.codec));
        if (!enc.init(activeCfg)) return false;
        server.setVideoConfig(cap.width(), cap.height(), cap.fps(), activeCfg.codec, enc.csd());
        enc.requestIdr();
        return true;
    };

    int exitCode = 0;
    while (gRunning.load()) {
        const bool gotFrame = cap.grabFrame(250, [&](const gta60_linux::CapturedFrame& cf) {
            watchdog.noteFrame(std::chrono::steady_clock::now());
            const bool dumping = dumper.isPendingOrActive();
            if (!server.hasClient() && !dumping) {
                dupDet.resetAll();
                return;
            }

            {
                bool needReinit = false;
                gta60_linux::EncoderConfig nextCfg = activeCfg;
                {
                    std::lock_guard lk(reconfig.mtx);
                    if (reconfig.pending) {
                        reconfig.pending = false;
                        nextCfg.bitrateMbps = reconfig.bitrateMbps;
                        nextCfg.codec = reconfig.codec;
                        nextCfg.tenBit = reconfig.tenBit;
                        needReinit = (nextCfg.bitrateMbps != activeCfg.bitrateMbps ||
                                      nextCfg.codec != activeCfg.codec ||
                                      nextCfg.tenBit != activeCfg.tenBit);
                    }
                }
                if (needReinit) {
                    dumper.stop();
                    const auto prevCfg = activeCfg;
                    if (enc.init(nextCfg)) {
                        activeCfg = enc.config();
                        server.setVideoConfig(cap.width(), cap.height(), cap.fps(), activeCfg.codec, enc.csd());
                        enc.requestIdr();
                        std::printf("[gt60] reconfigured encoder -> %s%s %u Mbps (%s)\n",
                                    gta60_linux::videoCodecName(activeCfg.codec),
                                    activeCfg.tenBit ? " 10-bit" : "",
                                    activeCfg.bitrateMbps,
                                    enc.activeRcModeName());
                        std::fflush(stdout);
                    } else {
                        std::fprintf(stderr, "[gt60] encoder reconfig failed; rolling back to %s\n",
                                     gta60_linux::videoCodecName(prevCfg.codec));
                        if (enc.init(prevCfg)) {
                            activeCfg = enc.config();
                        }
                    }
                }
            }

            const bool isDup = dupDet.observe(cf, opt.dupThreshold);
            dupDet.maybeLog(server.droppedVideoFrames());

            const bool tagDup = dupDet.shouldTagDuplicate(isDup, opt.tagDuplicates);

            auto onEncoded = [&](const uint8_t* data, size_t size, bool isIdr) {
                watchdog.notePacket(std::chrono::steady_clock::now());
                if (server.hasClient()) {
                    server.sendVideoAccessUnit(data, size, cf.timestampMs, isIdr, tagDup);
                }
                dumper.onPacket(activeCfg.codec, enc.csd(), cap.width(), cap.height(), cap.fps(),
                                data, size, cf.timestampMs, isIdr, tagDup);
            };

            if (cf.format == gta60_linux::CapturePixelFormat::NV12) {
                const uint8_t* y = cf.data;
                const uint8_t* uv = cf.data + static_cast<size_t>(cf.stride) * cf.height;
                enc.encodeNv12(y, static_cast<int>(cf.stride),
                               uv, static_cast<int>(cf.stride),
                               pts++, onEncoded);
            } else {
                enc.encodeYuyv(cf.data, static_cast<int>(cf.stride), pts++, onEncoded);
            }
        });

        if (!gotFrame) {
            // grabFrame returns immediately while the device is closed between reopen attempts;
            // keep that window cheap instead of spinning the CPU.
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }

        if (!opt.watchdogEnabled) continue;

        const auto action = watchdog.poll(std::chrono::steady_clock::now(), server.hasClient());
        if (action == gta60_linux::StreamWatchdog::Action::None) continue;

        const bool recovered = action == gta60_linux::StreamWatchdog::Action::RecoverCapture
                                   ? recoverCapture()
                                   : recoverEncoder();
        if (recovered || !watchdog.exhausted()) continue;

        const int failures = action == gta60_linux::StreamWatchdog::Action::RecoverCapture
                                 ? watchdog.frameFailures()
                                 : watchdog.encodeFailures();
        std::fprintf(stderr,
                     "[watchdog] pipeline recovery failed %d time(s); exiting so systemd restarts the unit\n",
                     failures);
        exitCode = 1;
        break;
    }

    audio.stop();
    server.stop();
    enc.shutdown();
    cap.close();
    return exitCode;
}

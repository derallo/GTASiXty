#include "AlsaCapture.hpp"
#include "Gt60Server.hpp"
#include "V4l2Capture.hpp"
#include "VaapiEncoder.hpp"

#include <unistd.h>

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
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
    uint32_t bitrateMbps = 50;
    gta60_linux::VideoCodec codec = gta60_linux::VideoCodec::HEVC;
    uint16_t tcpPort = 7660;
    uint16_t udpPort = 7661;
    bool selfTest = false;
};

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
            std::string c = v;
            if (c == "h264" || c == "H264") opt.codec = gta60_linux::VideoCodec::H264;
            else if (c == "hevc" || c == "HEVC" || c == "h265") opt.codec = gta60_linux::VideoCodec::HEVC;
            else {
                std::fprintf(stderr, "Unknown codec '%s' (use h264 or hevc)\n", v);
                return false;
            }
        } else if (a == "--port") {
            const char* v = needVal("--port");
            if (!v) return false;
            opt.tcpPort = static_cast<uint16_t>(std::stoul(v));
        } else if (a == "--selftest") {
            opt.selfTest = true;
        } else if (a == "--help" || a == "-h") {
            std::printf("Usage: gta60-capture [options]\n"
                        "  --device <path>     V4L2 device (default /dev/video0)\n"
                        "  --audio <alsa>      ALSA capture device or 'none' (default default)\n"
                        "  --drm <path>        VA-API DRM render node (default /dev/dri/renderD128)\n"
                        "  --width <px>        Target width (default 2560)\n"
                        "  --height <px>       Target height (default 1440)\n"
                        "  --fps <hz>          Target fps (default 60)\n"
                        "  --bitrate <mbps>    CBR bitrate in Mbps (default 50)\n"
                        "  --codec <hevc|h264> Codec (default hevc)\n"
                        "  --port <tcp>        GT60 TCP port (default 7660)\n"
                        "  --selftest          Run VA-API (+ V4L2 if present) self-test and exit\n");
            std::exit(0);
        } else {
            std::fprintf(stderr, "Unknown argument: %s\n", a.c_str());
            return false;
        }
    }
    return true;
}

int runSelfTest(const Options& opt) {
    std::printf("[selftest] Testing VA-API %s %ux%u@%u (%u Mbps) on %s...\n",
                gta60_linux::videoCodecName(opt.codec),
                opt.width, opt.height, opt.fps, opt.bitrateMbps, opt.drmDevice.c_str());

    gta60_linux::VaapiEncoder enc;
    if (!enc.init(opt.width, opt.height, opt.fps, opt.bitrateMbps, opt.codec, opt.drmDevice)) {
        std::fprintf(stderr, "[selftest] FAIL: VaapiEncoder::init failed\n");
        return 1;
    }
    if (enc.csd().empty()) {
        std::fprintf(stderr, "[selftest] FAIL: encoder produced empty CSD/extradata\n");
        return 1;
    }

    std::vector<uint8_t> yPlane(opt.width * opt.height, 128);
    std::vector<uint8_t> uvPlane(opt.width * (opt.height / 2), 128);

    constexpr int kFrames = 15;
    int encodedPackets = 0;
    int idrCount = 0;
    size_t totalBytes = 0;
    const auto t0 = std::chrono::steady_clock::now();

    for (int i = 0; i < kFrames; ++i) {
        // Vary luma slightly so rate control exercises real motion.
        std::memset(yPlane.data(), static_cast<int>((16 + i * 12) & 0xFF), opt.width * 16);
        if (!enc.encodeNv12(yPlane.data(), static_cast<int>(opt.width),
                            uvPlane.data(), static_cast<int>(opt.width),
                            i, [&](const uint8_t*, size_t sz, bool isIdr) {
                                ++encodedPackets;
                                if (isIdr) ++idrCount;
                                totalBytes += sz;
                            })) {
            std::fprintf(stderr, "[selftest] FAIL: encodeNv12 failed at frame %d\n", i);
            return 1;
        }
    }

    const double elapsedMs = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - t0).count();
    if (encodedPackets == 0 || idrCount == 0 || totalBytes == 0) {
        std::fprintf(stderr, "[selftest] FAIL: packets=%d idr=%d bytes=%zu\n",
                     encodedPackets, idrCount, totalBytes);
        return 1;
    }

    std::printf("[selftest] VA-API PASS: csd=%zuB packets=%d idr=%d total=%zuB avg=%.2f ms/frame\n",
                enc.csd().size(), encodedPackets, idrCount, totalBytes, elapsedMs / kFrames);

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

    gta60_linux::VaapiEncoder enc;
    if (!enc.init(cap.width(), cap.height(), cap.fps(), opt.bitrateMbps, opt.codec, opt.drmDevice)) {
        return 1;
    }

    gta60_linux::Gt60Server server;
    gta60_linux::Gt60Server::Callbacks cbs;
    cbs.onClientConnected = []() {
        std::printf("[gt60] Quest client connected\n");
    };
    cbs.onIdrRequested = [&enc]() {
        enc.requestIdr();
    };

    if (!server.start(opt.tcpPort, opt.udpPort, cap.width(), cap.height(), cap.fps(),
                      opt.codec, enc.csd(), std::move(cbs))) {
        std::fprintf(stderr, "[gt60] failed to bind TCP %u / UDP %u\n", opt.tcpPort, opt.udpPort);
        return 1;
    }

    gta60_linux::AlsaCapture audio;
    audio.start(opt.audioDevice, [&server](const int16_t* pcm, size_t frames, uint32_t tsMs) {
        if (server.hasClient()) {
            server.sendAudioPcm(pcm, frames, tsMs);
        }
    });

    std::printf("[gta60-capture] streaming %s %ux%u@%u %s (%u Mbps) on TCP %u / UDP %u\n",
                opt.videoDevice.c_str(), cap.width(), cap.height(), cap.fps(),
                gta60_linux::videoCodecName(opt.codec), opt.bitrateMbps,
                opt.tcpPort, opt.udpPort);

    int64_t pts = 0;
    while (gRunning.load()) {
        cap.grabFrame(250, [&](const gta60_linux::CapturedFrame& cf) {
            if (!server.hasClient()) return;

            auto onEncoded = [&](const uint8_t* data, size_t size, bool isIdr) {
                server.sendVideoAccessUnit(data, size, cf.timestampMs, isIdr);
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
    }

    audio.stop();
    server.stop();
    enc.shutdown();
    cap.close();
    return 0;
}

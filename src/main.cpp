#include "gta60/Log.h"
#include "gta60/media/MfH264Decoder.h"
#include "gta60/media/OpusWasapiAudio.h"
#include "gta60/media/SyntheticGameSource.h"
#include "gta60/media/VideoCodec.h"
#include "gta60/pipeline/ContentDedup.h"
#include "gta60/pipeline/D3D12Context.h"
#include "gta60/pipeline/EnhancementPipeline.h"
#include "gta60/pipeline/PictureCompare.h"
#include "gta60/sink/NvencD3D12Encoder.h"
#include "gta60/sink/QuestStreamServer.h"
#include "gta60/xbox/Account.h"
#include "gta60/xbox/Https.h"
#include "gta60/xbox/Protocol.h"
#include "gta60/xbox/StreamApi.h"
#include "gta60/xbox/WebRtcSession.h"
#include "gta60/xr/OpenXrQuadSink.h"

#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <mmsystem.h>
#include <objbase.h>
#include <shellapi.h>
#include <xinput.h>
#include <opus.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <filesystem>
#include <format>
#include <fstream>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

using namespace gta60;

namespace {

std::atomic<bool> g_stopRequested{false};

BOOL WINAPI consoleCtrlHandler(DWORD ctrlType) {
    if (ctrlType == CTRL_C_EVENT || ctrlType == CTRL_BREAK_EVENT || ctrlType == CTRL_CLOSE_EVENT) {
        g_stopRequested.store(true);
        return TRUE;
    }
    return FALSE;
}

struct CliOptions {
    enum class Mode { Help, Login, ListConsoles, Connect, SyntheticDemo, Agent };
    Mode mode = Mode::Help;
    std::string serverId = "auto";
    uint32_t targetFps = 60;
    uint32_t outWidth = 2560;
    uint32_t outHeight = 1440;
    float screenWidthMeters = 3.6f;
    float screenDistanceMeters = 2.5f;
    bool forwardController = false;
    bool headless = false;
    bool useNvofa = true;
    bool allowFrameGen = true;
    bool questServer = false;
    uint16_t questPort = 7660;
    uint32_t bitrateMbps = 50;
    uint32_t maxBitrateMbps = 0; // 0 = honour whatever the Quest client asks for
    // Never encode more pixels than this by default. Encoding 2160p costs ~10.8 ms of NVENC per
    // frame versus ~5.4 ms at 1440p, which takes a 16.7 ms budget to ~90% duty and starts dropping
    // frames - and the Xbox source is rarely above 1440p anyway.
    uint32_t maxOutputHeight = 1440;
    // RCAS sharpening amount. RCAS is edge-aware but it still amplifies the Xbox encoder's
    // quantization noise, which is most visible as grain during fast motion on a ~15 Mbps source.
    float rcasSharpness = 0.25f;
    // REMB target advertised to the console, in Mbps. Without this the PC sends no REMB at all and
    // the console falls back to ~14-15 Mbps; advertising more lifts it to its own ceiling (~24 Mbps),
    // which is a ~60% bitrate increase for the source with no latency cost. Measured: 150 Mbps
    // advertised still yields ~24 Mbps, so the console is the limiter, not our feedback.
    uint32_t xboxBitrateMbps = 50;
    // NVENC preset 1 (fastest) .. 7 (best quality). Measured at 1440p: P4 5.18 ms, P2 3.93 ms,
    // P1 3.88 ms. P2 wins ~1.25 ms of encode for no measurable quality cost at 80 Mbps, and P1
    // buys only 0.05 ms more, so P2 is the default.
    uint32_t nvencPreset = 2;
    // H.264 keeps the measured baseline. HEVC roughly halves the bitrate for equal quality, so the
    // same headset cap carries visibly more detail. Opt-in while it is new: --codec hevc.
    media::VideoCodec codec = media::VideoCodec::H264;
    uint64_t syntheticFrames = 180;
    // Diagnostic: write per-frame luma-difference histograms here (empty = off).
    std::string picDumpPath;
    // Mean-absolute-difference threshold for treating a frame as a repeat. Measured luma MAD is
    // strongly bimodal on real content: repeats cluster at 0.0-0.4 and genuine new frames at
    // 1.5-100, with a thin valley between. 1.0 sits in that valley, which also catches repeats whose
    // quantization noise rises during fast motion (0.5 was too tight there and made the FG gate
    // flap). 0 disables the test and restores the old strict behaviour.
    float dedupMad = 1.0f;
};

void printUsage() {
    std::printf(
        "GTA-Sixty - Zero-DWM Xbox Series X -> D3D12 (60->30 Dedup + Optical Flow FG + FSR Upscale) -> Quest 3\n\n"
        "Usage:\n"
        "  GTA-Sixty.exe --login\n"
        "  GTA-Sixty.exe --list-consoles\n"
        "  GTA-Sixty.exe --connect <serverId|auto> [options]\n"
        "  GTA-Sixty.exe --synthetic-demo [options]\n"
        "  GTA-Sixty.exe --agent     (listen on UDP 7662 so the headset can start a stream)\n\n"
        "Options:\n"
        "  --fps <60|90>             Output frame rate (2x or 3x from 30 FPS) [default: 60]\n"
        "  --upscale <1080p|1440p|4k> Target output resolution [default: 1440p]\n"
        "  --quest-server            Encode via zero-copy D3D12 NVENC & stream to GTA-Sixty Quest 3 APK\n"
        "  --port <port>             Quest server TCP port [default: 7660]\n"
        "  --bitrate <Mbps>          NVENC ultra-low-latency CBR bitrate [default: 50]\n"
        "  --codec <h264|hevc>       Video codec for the PC->headset stream. HEVC roughly halves the\n"
        "                            bitrate needed for the same quality [default: h264]\n"
        "  --max-bitrate <Mbps>      Cap the bitrate the Quest client may request (0 = no cap) [default: 0]\n"
        "  --max-output-height <px>  Cap the encode height the client may request (0 = no cap) [default: 1440]\n"
        "  --sharpness <0..2>        RCAS sharpening; lower = less grain amplification [default: 0.25]\n"
        "  --preset <1..7>           NVENC preset, 1 = fastest .. 7 = best quality [default: 2]\n"
        "  --dedup-mad <value>       Luma MAD below which a frame counts as a repeat of the previous one\n"
        "                            (0 = disable; raise if 30 fps content is not detected) [default: 0.5]\n"
        "  --xbox-bitrate <Mbps>     Bandwidth advertised to the console via REMB (0 = disabled, which\n"
        "                            pins the console near ~15 Mbps) [default: 50, console caps ~24]\n"
        "  --screen-width <meters>   OpenXR virtual cinema screen width [default: 3.6]\n"
        "  --screen-distance <meters> OpenXR screen distance in meters [default: 2.5]\n"
        "  --forward-controller      Forward PC XInput controller 0 to Xbox\n"
        "  --headless                Run D3D12 GPU pipeline without OpenXR headset\n"
        "  --no-nvofa                Force D3D12 compute optical flow instead of NVOFA\n"
        "  --no-fg                   Disable optical-flow frame generation (lowest latency passthrough)\n"
        "  --frames <N>              Frame count for --synthetic-demo (0 = run until Ctrl+C) [default: 180]\n");
}

bool parseArgs(int argc, char** argv, CliOptions& opts) {
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto nextVal = [&](std::string& out) -> bool {
            if (i + 1 >= argc) return false;
            out = argv[++i];
            return true;
        };
        if (a == "--help" || a == "-h") {
            opts.mode = CliOptions::Mode::Help;
            return true;
        } else if (a == "--login") {
            opts.mode = CliOptions::Mode::Login;
        } else if (a == "--list-consoles") {
            opts.mode = CliOptions::Mode::ListConsoles;
        } else if (a == "--connect") {
            opts.mode = CliOptions::Mode::Connect;
            if (!nextVal(opts.serverId)) return false;
        } else if (a == "--synthetic-demo") {
            opts.mode = CliOptions::Mode::SyntheticDemo;
        } else if (a == "--agent") {
            opts.mode = CliOptions::Mode::Agent;
        } else if (a == "--fps") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.targetFps = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--upscale") {
            std::string v;
            if (!nextVal(v)) return false;
            if (v == "1080p") {
                opts.outWidth = 1920;
                opts.outHeight = 1080;
            } else if (v == "1440p") {
                opts.outWidth = 2560;
                opts.outHeight = 1440;
            } else if (v == "4k" || v == "2160p") {
                opts.outWidth = 3840;
                opts.outHeight = 2160;
            } else {
                return false;
            }
        } else if (a == "--screen-width") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.screenWidthMeters = std::stof(v);
        } else if (a == "--screen-distance") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.screenDistanceMeters = std::stof(v);
        } else if (a == "--forward-controller") {
            opts.forwardController = true;
        } else if (a == "--headless") {
            opts.headless = true;
        } else if (a == "--no-nvofa") {
            opts.useNvofa = false;
        } else if (a == "--no-fg") {
            opts.allowFrameGen = false;
        } else if (a == "--quest-server") {
            opts.questServer = true;
        } else if (a == "--port") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.questPort = static_cast<uint16_t>(std::stoul(v));
        } else if (a == "--bitrate") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.bitrateMbps = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--max-bitrate") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.maxBitrateMbps = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--max-output-height") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.maxOutputHeight = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--xbox-bitrate") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.xboxBitrateMbps = static_cast<uint32_t>(std::stoul(v));
        } else if (a == "--sharpness") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.rcasSharpness = std::clamp(std::stof(v), 0.0f, 2.0f);
        } else if (a == "--dedup-mad") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.dedupMad = std::clamp(std::stof(v), 0.0f, 64.0f);
        } else if (a == "--dump-pic") {
            if (!nextVal(opts.picDumpPath)) return false;
        } else if (a == "--preset") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.nvencPreset = std::clamp(static_cast<uint32_t>(std::stoul(v)), 1u, 7u);
        } else if (a == "--codec") {
            std::string v;
            if (!nextVal(v)) return false;
            if (v == "h264" || v == "H264" || v == "avc" || v == "AVC") {
                opts.codec = media::VideoCodec::H264;
            } else if (v == "hevc" || v == "HEVC" || v == "h265" || v == "H265") {
                opts.codec = media::VideoCodec::HEVC;
            } else {
                return false;
            }
        } else if (a == "--frames") {
            std::string v;
            if (!nextVal(v)) return false;
            opts.syntheticFrames = std::stoull(v);
        } else {
            return false;
        }
    }
    return opts.mode != CliOptions::Mode::Help;
}

int runLogin() {
    auto transport = xbox::makeWinHttpTransport(&g_stopRequested);
    const auto tokenFile = xbox::dataDirectory() / L"tokens.bin";
    xbox::Account account(transport, tokenFile);

    std::printf("Requesting Microsoft device code...\n");
    const auto code = account.beginSignIn();
    std::printf("\n============================================================\n");
    std::printf("  Sign in to Xbox Live:\n");
    std::printf("  1. Open:  %s\n", code.verificationUri.c_str());
    std::printf("  2. Enter: %s\n", code.userCode.c_str());
    std::printf("============================================================\n\n");
    std::fflush(stdout);
    ShellExecuteW(nullptr, L"open", log::widen(code.verificationUri).c_str(), nullptr, nullptr, SW_SHOWNORMAL);

    int interval = code.intervalSeconds;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(code.expiresInSeconds);
    while (!g_stopRequested.load() && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::seconds(interval));
        std::string detail;
        const auto poll = account.pollSignIn(code, &detail);
        if (poll == xbox::SignInPoll::Done) {
            // Do not claim success on the strength of the poll alone: if the token could not be
            // written (read-only profile, denied directory) the sign-in is thrown away and the user
            // is asked again on the next launch, which looks like the login simply did not work.
            std::error_code ec;
            if (std::filesystem::exists(tokenFile, ec)) {
                std::printf("Sign-in succeeded! Encrypted token saved to %s\n", log::narrow(tokenFile.wstring()).c_str());
                return 0;
            }
            std::fprintf(stderr,
                         "Sign-in succeeded, but the token could NOT be saved to:\n  %s\n"
                         "Set GTA60_XBOX_DATA to a writable directory and try again.\n",
                         log::narrow(tokenFile.wstring()).c_str());
            return 1;
        }
        if (poll == xbox::SignInPoll::SlowDown) interval += 5;
        else if (poll == xbox::SignInPoll::Expired) {
            std::fprintf(stderr, "Device code expired.\n");
            return 1;
        } else if (poll == xbox::SignInPoll::Declined || poll == xbox::SignInPoll::Failed) {
            std::fprintf(stderr, "Sign-in failed (%s).\n", detail.c_str());
            return 1;
        }
    }
    return 1;
}

int runListConsoles() {
    auto transport = xbox::makeWinHttpTransport(&g_stopRequested);
    xbox::Account account(transport, xbox::dataDirectory() / L"tokens.bin");
    if (!account.signedIn()) {
        std::fprintf(stderr, "Not signed in. Run: GTA-Sixty.exe --login\n");
        return 1;
    }
    const auto access = account.streamingAccess();
    xbox::StreamApi api(transport, access.host, access.gsToken);
    const auto list = api.consoles();
    std::printf("Found %zu Xbox console(s) (region: %s):\n", list.size(), access.regionName.c_str());
    for (const auto& c : list) {
        std::printf("  - Name: %-20s  ID: %-18s  Type: %-14s  Power: %s\n",
                    c.name.c_str(), c.serverId.c_str(), c.consoleType.c_str(), c.powerState.c_str());
    }
    return 0;
}

bool initPipelineAndOpenXr(const CliOptions& opts,
                           uint32_t inW,
                           uint32_t inH,
                           xr::OpenXrQuadSink& xrSink,
                           pipeline::D3D12Context& d3d,
                           pipeline::EnhancementPipeline& pipe,
                           bool& xrActive) {
    xrActive = false;
    LUID targetLuid{};

    if (!opts.headless && !opts.questServer) {
        const auto probe = xrSink.probeAndCreateInstance();
        if (probe.headsetFound) {
            targetLuid = probe.requiredAdapterLuid;
            xrActive = true;
            log::info("app", std::format("OpenXR active on '{}' -> initializing D3D12 on required LUID",
                                         probe.runtimeName));
        } else {
            log::warn("app", std::format("OpenXR headset unavailable ({}); running headless D3D12 mode",
                                         probe.statusMessage));
        }
    }

    if (!d3d.initialize(targetLuid)) {
        log::error("app", "D3D12Context initialization failed");
        return false;
    }

    bool bgra = false;
    if (xrActive) {
        xr::QuadScreenConfig qcfg;
        qcfg.width = opts.outWidth;
        qcfg.height = opts.outHeight;
        qcfg.screenWidthMeters = opts.screenWidthMeters;
        qcfg.screenDistanceMeters = opts.screenDistanceMeters;
        if (!xrSink.attachDevice(d3d.device(), d3d.queue(), qcfg)) {
            log::warn("app", "OpenXR attachDevice failed; continuing headless");
            xrSink.shutdown();
            xrActive = false;
        } else {
            bgra = xrSink.prefersBgra();
        }
    }

    pipeline::PipelineConfig pcfg;
    pcfg.inputWidth = inW;
    pcfg.inputHeight = inH;
    pcfg.outputWidth = opts.outWidth;
    pcfg.outputHeight = opts.outHeight;
    pcfg.targetFps = opts.targetFps;
    pcfg.useNvofa = opts.useNvofa;
    pcfg.rcasSharpness = opts.rcasSharpness;
    pcfg.outputFormat = bgra ? DXGI_FORMAT_B8G8R8A8_UNORM : DXGI_FORMAT_R8G8B8A8_UNORM;

    if (!pipe.initialize(d3d, pcfg)) {
        log::error("app", "EnhancementPipeline initialization failed");
        return false;
    }
    return true;
}

int runSyntheticDemo(const CliOptions& opts) {
    xr::OpenXrQuadSink xrSink;
    pipeline::D3D12Context d3d;
    pipeline::EnhancementPipeline pipe;
    bool xrActive = false;

    constexpr uint32_t kInW = 1920, kInH = 1080;
    if (!initPipelineAndOpenXr(opts, kInW, kInH, xrSink, d3d, pipe, xrActive)) return 1;

    sink::NvencD3D12Encoder nvenc;
    sink::QuestStreamServer questServer;
    if (opts.questServer) {
        sink::NvencConfig ncfg{opts.outWidth, opts.outHeight, opts.targetFps, opts.bitrateMbps, opts.nvencPreset, opts.codec};
        if (!nvenc.initialize(d3d, ncfg)) {
            log::error("demo", "NvencD3D12Encoder initialization failed");
            return 1;
        }
        sink::QuestStreamServer::Callbacks qcb;
        qcb.onIdrRequested = [&]() { nvenc.requestIdr(); };
        if (!questServer.start(opts.questPort, opts.outWidth, opts.outHeight, opts.targetFps, nvenc.spsPps(), opts.codec, std::move(qcb))) {
            return 1;
        }
        log::info("demo", std::format("Quest 3 NVENC stream server active on TCP {} (Ctrl+C to stop)", opts.questPort));
    }

    media::SyntheticGameSource source(kInW, kInH, true);
    pipeline::PictureReference picRef;
    pipeline::ContentDedup dedup;

    uint64_t outputFramesTotal = 0;
    uint64_t generatedFramesTotal = 0;
    double sumUploadMs = 0.0, sumFlowMs = 0.0, sumRefineMs = 0.0, sumFgUpscaleMs = 0.0, sumTotalMs = 0.0;
    uint64_t fgBatchCount = 0;

    const auto framePeriod = std::chrono::microseconds(1000000 / std::max(1u, opts.targetFps));
    const auto streamOrigin = std::chrono::steady_clock::now();

    for (uint64_t i = 0; (opts.syntheticFrames == 0 || i < opts.syntheticFrames) && !g_stopRequested.load(); ++i) {
        const auto containerStart = std::chrono::steady_clock::now();
        const auto nv12 = source.generateContainerFrame(i);

        const pipeline::PicturePlane planes[2] = {
            {nv12.yPlane.data(), nv12.stride, nv12.width, nv12.height},
            {nv12.uvPlane.data(), nv12.stride, nv12.width, nv12.height / 2},
        };
        const auto pic = picRef.observe(planes, 2, pipeline::PictureComponents::Bytes);
        const auto dec = dedup.observe(&pic.id, nv12.pts100ns, 166667, i == 0, 333333);

        if (!dec.keep) {
            if (opts.questServer) {
                std::this_thread::sleep_until(containerStart + std::chrono::microseconds(16667));
            }
            continue;
        }

        pipeline::ProcessedFrameBatch batch;
        if (!pipe.processUniqueFrame(nv12, batch)) {
            log::error("demo", "processUniqueFrame failed");
            return 1;
        }

        for (size_t k = 0; k < batch.frames.size(); ++k) {
            const auto tSubmitStart = std::chrono::steady_clock::now();
            if (xrActive) {
                if (!xrSink.submitFrame(d3d, batch.frames[k])) {
                    xrActive = false;
                }
            } else if (opts.questServer && questServer.hasClient()) {
                const uint32_t tsMs = static_cast<uint32_t>(
                    std::chrono::duration_cast<std::chrono::milliseconds>(tSubmitStart - streamOrigin).count());
                nvenc.encodeFrame(batch.frames[k], tsMs, batch.isGenerated[k],
                                  [&](const uint8_t* data, size_t sz, uint32_t ts, bool idr, bool gen) {
                                      questServer.sendVideoAccessUnit(data, sz, ts, idr, gen);
                                  });
            }
            ++outputFramesTotal;
            if (batch.isGenerated[k]) ++generatedFramesTotal;
            if ((xrActive || opts.questServer) && k + 1 < batch.frames.size()) {
                std::this_thread::sleep_until(tSubmitStart + framePeriod);
            }
        }

        sumUploadMs += batch.timings.nv12UploadAndConvertMs;
        sumFlowMs += batch.timings.opticalFlowMs;
        sumRefineMs += batch.timings.flowRefineAndFillMs;
        sumFgUpscaleMs += batch.timings.frameGenAndUpscaleMs;
        sumTotalMs += batch.timings.totalGpuMs;
        ++fgBatchCount;
    }

    const auto& dstats = dedup.stats();
    const double avgBatchMs = fgBatchCount ? sumTotalMs / double(fgBatchCount) : 0.0;
    const double avgPerOutMs = outputFramesTotal ? sumTotalMs / double(outputFramesTotal) : 0.0;

    std::printf(
        "\n=== Synthetic 60->30 Dedup + %u FPS GPU Pipeline Summary ===\n"
        "Adapter:               %s\n"
        "Optical Flow Backend:  %s\n"
        "Input 60Hz Frames:     %llu\n"
        "Kept Unique 30FPS:     %llu\n"
        "Stripped Duplicates:   %llu\n"
        "Total Output Frames:   %llu (%llu generated + %llu real @ %ux%u)\n"
        "Avg GPU/Upload+Conv:   %.2f ms / batch\n"
        "Avg GPU/FlowRefine:    %.2f ms / batch\n"
        "Avg GPU/FG+EASU+RCAS:  %.2f ms / batch\n"
        "Avg Total Time/Batch:  %.2f ms (%.2f ms per output frame; budget %.2f ms)\n",
        opts.targetFps,
        log::narrow(d3d.adapterName()).c_str(),
        pipe.usingHardwareNvof() ? "NVIDIA NVOFA Hardware (nvofapi64.dll)" : "D3D12 Compute DisOpticalFlow",
        static_cast<unsigned long long>(dstats.observed),
        static_cast<unsigned long long>(dstats.kept),
        static_cast<unsigned long long>(dstats.repeats),
        static_cast<unsigned long long>(outputFramesTotal),
        static_cast<unsigned long long>(generatedFramesTotal),
        static_cast<unsigned long long>(outputFramesTotal - generatedFramesTotal),
        opts.outWidth, opts.outHeight,
        fgBatchCount ? sumUploadMs / double(fgBatchCount) : 0.0,
        fgBatchCount ? sumRefineMs / double(fgBatchCount) : 0.0,
        fgBatchCount ? sumFgUpscaleMs / double(fgBatchCount) : 0.0,
        avgBatchMs,
        avgPerOutMs,
        1000.0 / double(opts.targetFps));

    questServer.stop();
    nvenc.shutdown();
    xrSink.shutdown();
    pipe.shutdown();
    d3d.shutdown();
    return 0;
}

class StreamApiSignaling final : public xbox::Signaling {
public:
    StreamApiSignaling(xbox::StreamApi& api, std::string sessionId)
        : api_(api), sessionId_(std::move(sessionId)) {}

    std::string exchangeSdp(const std::string& offerSdp) override {
        return api_.exchangeSdp(sessionId_, offerSdp);
    }

    std::vector<xbox::IceCandidate> exchangeIce(const std::vector<xbox::IceCandidate>& local) override {
        return api_.exchangeIce(sessionId_, local);
    }

private:
    xbox::StreamApi& api_;
    std::string sessionId_;
};

bool hasIdrOrSps(const std::vector<uint8_t>& u) {
    for (size_t i = 0; i + 3 < u.size(); ++i) {
        if (u[i] == 0 && u[i + 1] == 0 && u[i + 2] == 1) {
            const uint8_t nal = u[i + 3] & 0x1F;
            if (nal == 5 || nal == 7) return true;
            i += 2;
        }
    }
    return false;
}

int runConnect(const CliOptions& initialOpts) {
    CliOptions opts = initialOpts;
    auto transport = xbox::makeWinHttpTransport(&g_stopRequested);
    xbox::Account account(transport, xbox::dataDirectory() / L"tokens.bin");
    if (!account.signedIn()) {
        std::fprintf(stderr, "Not signed in. Run: GTA-Sixty.exe --login\n");
        return 1;
    }

    const auto access = account.streamingAccess();
    xbox::StreamApi api(transport, access.host, access.gsToken);
    const auto consoles = api.consoles();
    if (consoles.empty()) {
        std::fprintf(stderr, "No Xbox consoles registered for home streaming on this account.\n");
        return 1;
    }

    xbox::Console target = consoles[0];
    if (opts.serverId != "auto") {
        bool found = false;
        for (const auto& c : consoles) {
            if (c.serverId == opts.serverId || c.name == opts.serverId) {
                target = c;
                found = true;
                break;
            }
        }
        if (!found) {
            std::fprintf(stderr, "Console '%s' not found.\n", opts.serverId.c_str());
            return 1;
        }
    }

    log::info("xbox", std::format("Connecting to console '{}' ({}) [{}]",
                                  target.name, target.serverId, target.powerState));

    // Provisioning a second session while the console still holds one takes it over and drops it to
    // the dashboard, which interrupts an in-progress game. So if the session this PC created last
    // time is still alive, reattach to it. The id is persisted because the streamer is short-lived:
    // it exits whenever the console ends the session.
    const auto sessionFile = xbox::dataDirectory() / L"last-session.txt";
    std::string sessionId;
    bool sessionReused = false;
    {
        std::ifstream in(sessionFile);
        std::string saved;
        if (in && std::getline(in, saved) && !saved.empty()) {
            try {
                const auto st = api.state(saved);
                if (!st.state.empty() && st.state != "Failed") {
                    sessionId = saved;
                    sessionReused = true;
                    log::info("xbox", std::format("Reusing live session {} (state={}); not provisioning a new one",
                                                  saved, st.state));
                } else {
                    log::info("xbox", std::format("Saved session {} is gone (state={}); provisioning a new one",
                                                  saved, st.state));
                }
            } catch (const std::exception& e) {
                log::warn("xbox", std::format("saved session {} is not usable ({}); provisioning a new one",
                                              saved, e.what()));
            }
        }
        if (sessionId.empty()) {
            sessionId = api.play(target.serverId, "en-US");
            log::info("xbox", std::format("Session provisioned: {}", sessionId));
            std::error_code sec;
            if (!sessionFile.parent_path().empty()) std::filesystem::create_directories(sessionFile.parent_path(), sec);
            std::ofstream out(sessionFile, std::ios::trunc);
            if (out) {
                out << sessionId;
            } else {
                // Not fatal, but it silently costs the reuse above on the next start.
                std::fprintf(stderr, "xbox: cannot remember the session id in %ls (errno=%d); "
                                     "the next start will provision a new session\n",
                             sessionFile.c_str(), errno);
            }
        }
    }

    // Attach to the session exactly once. `connect` is what makes the console announce that a
    // remote client started playing, so calling it on every poll iteration (the previous
    // behaviour: connect, sleep, connect, ... until the state flipped) produced the repeated
    // "started playing remotely" notifications.
    bool connectSent = false;
    if (sessionReused) {
        api.connect(sessionId, account.transferToken());
        connectSent = true;
        log::info("xbox", "Reattached to the existing session (console left undisturbed)");
    } else {
        for (int attempt = 0; attempt < 60 && !g_stopRequested.load(); ++attempt) {
            const auto st = api.state(sessionId);
            if (st.state == "Provisioned") {
                break;
            } else if (st.state == "Failed") {
                log::error("xbox", std::format("Session provisioning failed: {} {}", st.errorCode, st.errorMessage));
                api.stop(sessionId);
                return 1;
            } else if (st.state == "ReadyToConnect" && !connectSent) {
                api.connect(sessionId, account.transferToken());
                connectSent = true;
                log::info("xbox", "Session connected (client attached once)");
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(1000));
        }
    }
    if (g_stopRequested.load()) {
        api.stop(sessionId);
        return 0;
    }

    struct VideoUnit {
        std::vector<uint8_t> data;
        uint32_t rtpTimestamp = 0;
        int64_t arrival100ns = 0;
        std::chrono::steady_clock::time_point arrivalTime{};
        bool inputLoss = false;
    };

    std::mutex videoMtx;
    std::condition_variable videoCv;
    std::deque<VideoUnit> videoQueue;
    std::atomic<bool> streamEnded{false};
    std::atomic<uint32_t> streamW{1920}, streamH{1080};
    std::atomic<uint64_t> perfInUnits{0};
    std::atomic<uint64_t> perfQueueDrops{0};
    std::atomic<int64_t> lastXboxVideoUs{
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count()};

    media::OpusWasapiAudio audio;
    if (!opts.questServer) audio.start();

    int opusErr = OPUS_OK;
    OpusDecoder* questOpus = opts.questServer ? opus_decoder_create(48000, 2, &opusErr) : nullptr;
    std::vector<int16_t> questPcmBuf(5760 * 2);
    sink::QuestStreamServer questServer;
    sink::NvencD3D12Encoder nvenc;
    const auto streamOrigin = std::chrono::steady_clock::now();

    xbox::WebRtcSession rtc;
    xbox::WebRtcCallbacks cbs;
    cbs.video = [&](std::vector<uint8_t>&& unit, uint32_t rtp, int64_t arrival) {
        const auto arrived = std::chrono::steady_clock::now();
        perfInUnits.fetch_add(1, std::memory_order_relaxed);
        lastXboxVideoUs.store(
            std::chrono::duration_cast<std::chrono::microseconds>(arrived.time_since_epoch()).count(),
            std::memory_order_relaxed);
        {
            std::lock_guard lock(videoMtx);
            // Tight 6-frame cap (~100 ms max burst) prevents decoder queue buildup
            const bool dropped = (videoQueue.size() >= 6);
            if (dropped) {
                videoQueue.clear();
                perfQueueDrops.fetch_add(1, std::memory_order_relaxed);
            }
            videoQueue.push_back({std::move(unit), rtp, arrival, arrived, dropped});
        }
        videoCv.notify_one();
    };
    cbs.audio = [&](const uint8_t* data, size_t size, uint32_t) {
        if (opts.questServer && questOpus && questServer.hasClient()) {
            const int frames = opus_decode(questOpus, data, static_cast<opus_int32>(size),
                                           questPcmBuf.data(), 5760, 0);
            if (frames > 0) {
                const uint32_t tsMs = static_cast<uint32_t>(
                    std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - streamOrigin)
                        .count());
                questServer.sendAudioPcm(questPcmBuf.data(), static_cast<size_t>(frames), tsMs);
            }
        } else {
            audio.pushOpusPacket(data, size);
        }
    };
    cbs.serverVideoSize = [&](uint32_t w, uint32_t h) {
        streamW.store(w);
        streamH.store(h);
    };
    cbs.vibration = [&](const xbox::Vibration& vib) {
        if (opts.forwardController) {
            XINPUT_VIBRATION xv{};
            xv.wLeftMotorSpeed = static_cast<WORD>(vib.leftMotor * 655u);
            xv.wRightMotorSpeed = static_cast<WORD>(vib.rightMotor * 655u);
            XInputSetState(0, &xv);
        }
    };
    cbs.ended = [&](const std::string& reason) {
        log::warn("xbox", std::format("Session ended: {}", reason));
        streamEnded.store(true);
        videoCv.notify_all();
    };
    rtc.setCallbacks(std::move(cbs));
    if (opts.xboxBitrateMbps > 0) {
        rtc.setVideoBitrateRequest(opts.xboxBitrateMbps * 1000000u);
        log::info("xbox", std::format("advertising {} Mbps to the console via REMB", opts.xboxBitrateMbps));
    }

    StreamApiSignaling signaling(api, sessionId);
    std::string rtcError;
    if (!rtc.start(signaling, &rtcError) || !rtc.waitReady(std::chrono::seconds(15))) {
        log::error("xbox", std::format("WebRTC connection failed: {}", rtcError));
        api.stop(sessionId);
        audio.stop();
        return 1;
    }
    log::info("xbox", "WebRTC stream live! Requesting keyframe...");
    rtc.requestKeyframe();

    // Keepalive & XInput forwarding thread
    std::jthread controlThread([&](std::stop_token st) {
        auto lastKeepalive = std::chrono::steady_clock::now();
        xbox::GamepadFrame lastPad{};
        auto lastPadSent = std::chrono::steady_clock::now();
        while (!st.stop_requested() && !g_stopRequested.load() && !streamEnded.load()) {
            const auto now = std::chrono::steady_clock::now();
            if (now - lastKeepalive > std::chrono::seconds(30)) {
                try {
                    api.keepalive(sessionId);
                } catch (...) {}
                lastKeepalive = now;
            }
            if (opts.forwardController) {
                XINPUT_STATE xs{};
                if (XInputGetState(0, &xs) == ERROR_SUCCESS) {
                    const auto pad = xbox::gamepadFromXInput(
                        xs.Gamepad.wButtons, xs.Gamepad.bLeftTrigger, xs.Gamepad.bRightTrigger,
                        xs.Gamepad.sThumbLX, xs.Gamepad.sThumbLY, xs.Gamepad.sThumbRX, xs.Gamepad.sThumbRY, 0);
                    if (!(pad == lastPad) || now - lastPadSent >= std::chrono::milliseconds(33)) {
                        rtc.sendGamepad(pad);
                        lastPad = pad;
                        lastPadSent = now;
                    }
                }
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }
    });

    media::MfH264Decoder decoder;
    constexpr uint32_t kInitDecW = 1920;
    constexpr uint32_t kInitDecH = 1080;
    if (!decoder.initialize(kInitDecW, kInitDecH)) {
        log::error("media", "MfH264Decoder initialization failed");
        rtc.close();
        api.stop(sessionId);
        return 1;
    }

    xr::OpenXrQuadSink xrSink;
    pipeline::D3D12Context d3d;
    pipeline::EnhancementPipeline pipe;
    bool xrActive = false;
    if (!initPipelineAndOpenXr(opts, kInitDecW, kInitDecH, xrSink, d3d, pipe, xrActive)) {
        decoder.shutdown();
        rtc.close();
        api.stop(sessionId);
        if (questOpus) opus_decoder_destroy(questOpus);
        return 1;
    }

    std::atomic<bool> forceImmediateFrame{false};
    std::atomic<bool> needXboxKeyframe{false};
    std::mutex cfgMtx;
    std::optional<sink::QuestStreamServer::ClientStreamConfig> pendingConfig;

    struct MailboxItem {
        std::shared_ptr<media::DecodedNv12Frame> frame;
        std::chrono::steady_clock::time_point arrivalTime{};
        double contentFps = 60.0;
        int64_t interval100ns = 166667;
    };

    std::mutex mailboxMtx;
    std::condition_variable mailboxCv;
    MailboxItem mailboxItem;
    uint64_t mailboxSeq = 0;
    std::atomic<uint64_t> mailboxLatestSeq{0};

    if (opts.questServer) {
        sink::NvencConfig ncfg{opts.outWidth, opts.outHeight, opts.targetFps, opts.bitrateMbps, opts.nvencPreset, opts.codec};
        if (!nvenc.initialize(d3d, ncfg)) {
            log::error("app", "NvencD3D12Encoder initialization failed");
            pipe.shutdown();
            d3d.shutdown();
            decoder.shutdown();
            rtc.close();
            api.stop(sessionId);
            if (questOpus) opus_decoder_destroy(questOpus);
            return 1;
        }
        questServer.setMaxVideoBacklog(3);
        // 384 KB absorbs a ~38 ms headset-side hiccup at 80 Mbps instead of blocking the send
        // thread and back-pressuring the encoder (measured 17 ms stalls with a 128 KB buffer).
        questServer.setSendBufferBytes(384 * 1024);
        sink::QuestStreamServer::Callbacks qcb;
        qcb.onClientConnected = [&]() {
            nvenc.requestIdr();
            needXboxKeyframe.store(true, std::memory_order_relaxed);
            forceImmediateFrame.store(true, std::memory_order_relaxed);
            mailboxCv.notify_one();
        };
        qcb.onIdrRequested = [&]() {
            nvenc.requestIdr();
            needXboxKeyframe.store(true, std::memory_order_relaxed);
            forceImmediateFrame.store(true, std::memory_order_relaxed);
            mailboxCv.notify_one();
        };
        qcb.onGamepad = [&](const xbox::GamepadFrame& gf) { rtc.sendGamepad(gf); };
        qcb.onConfigRequested = [&](const sink::QuestStreamServer::ClientStreamConfig& cfg) {
            {
                std::lock_guard lk(cfgMtx);
                pendingConfig = cfg;
            }
            mailboxCv.notify_one();
        };
        if (!questServer.start(opts.questPort, opts.outWidth, opts.outHeight, opts.targetFps, nvenc.spsPps(), opts.codec, std::move(qcb))) {
            nvenc.shutdown();
            pipe.shutdown();
            d3d.shutdown();
            decoder.shutdown();
            rtc.close();
            api.stop(sessionId);
            if (questOpus) opus_decoder_destroy(questOpus);
            return 1;
        }
    }

    auto framePeriod = std::chrono::microseconds(1000000 / std::max(1u, opts.targetFps));
    std::shared_ptr<media::DecodedNv12Frame> lastProcessedNv12;
    auto lastSentFrameTime = std::chrono::steady_clock::now();

    std::filesystem::path perfLogPath = "build/perf-telemetry.log";
    {
        wchar_t exePath[MAX_PATH]{};
        if (GetModuleFileNameW(nullptr, exePath, MAX_PATH) > 0) {
            perfLogPath = std::filesystem::path(exePath).parent_path() / "perf-telemetry.log";
        }
    }
    std::ofstream perfFile(perfLogPath, std::ios::app);

    auto lastPerfTime = std::chrono::steady_clock::now();
    uint64_t lastXboxVideoBytes = 0;
    std::atomic<uint64_t> perfUniqueFrames{0};
    std::atomic<uint64_t> perfDecodeCount{0};
    std::atomic<uint64_t> perfDecodeUsSum{0};
    // How far behind the submitted access unit the frame that actually came out of the decoder is.
    // pcLat tags frames with the arrival time of the unit being submitted, so any internal MFT
    // buffering silently subtracts from the reported latency; this exposes it.
    std::atomic<uint64_t> perfDecLagUsSum{0};
    std::atomic<uint64_t> perfDecLagMaxUs{0};
    std::atomic<uint64_t> perfDecLagCount{0};
    uint64_t perfOutFrames = 0;
    uint64_t perfBytesSent = 0;
    uint64_t perfGpuCount = 0;
    double perfGpuTotalMsSum = 0.0;
    double perfGpuUploadMsSum = 0.0;
    double perfGpuFlowMsSum = 0.0;
    double perfGpuFgUpscaleMsSum = 0.0;
    uint64_t perfEncCount = 0;
    double perfNvencMsSum = 0.0;
    double perfPcLatMsSum = 0.0;
    uint64_t perfPcLatCount = 0;

    bool fgActive = false;
    // Smoothed content-rate estimate. The per-frame figure fed by the dedup is noisy (compression
    // noise makes some duplicates look unique and vice versa), so gating decisions use an EMA and a
    // wide dead band - otherwise the mode flips back and forth, and every flip costs a pipeline
    // reset plus a latency spike.
    double contentFpsSmoothed = 60.0;
    int lowFpsStreak = 0;
    int highFpsStreak = 0;
    const char* activeModeTag = "NV12";

    auto now100ns = []() -> int64_t {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
                   .count() /
               100;
    };

    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);

    std::jthread decodeThread([&](std::stop_token st) {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
        pipeline::PictureReference picRef;
        pipeline::ContentDedup dedup;
        bool seenKeyframe = false;
        uint32_t lastRtp = 0;
        int64_t rtpHigh = 0;
        bool haveRtp = false;
        int64_t lastPts100ns = 0;
        bool hasLastPts = false;
        uint32_t lastDecW = 0, lastDecH = 0;
    // Diagnostic state for --dump-pic (per-frame quantization-noise histogram).
    std::ofstream picDumpFile;
    if (!opts.picDumpPath.empty()) {
        picDumpFile.open(opts.picDumpPath, std::ios::trunc);
        if (picDumpFile.is_open()) {
            picDumpFile << "id,ptsMs,picOver,picMax8,keep,intervalMs,madY,gt1,gt2,gt3,gt4,gt6,gt8,gt12,gt16,gt24,gt32\n";
            log::info("dedup", "dumping per-frame luma difference histogram to " + opts.picDumpPath);
        }
    }
    std::vector<uint8_t> prevSig, curSig;
    int64_t lastForwardPts = 0;

        // Pre-allocated 4-frame pool eliminates 5.5 MB VirtualAlloc/page-fault churn per frame
        std::array<std::shared_ptr<media::DecodedNv12Frame>, 4> framePool;
        for (auto& slot : framePool) slot = std::make_shared<media::DecodedNv12Frame>();

        auto acquireFrameBuf = [&]() -> std::shared_ptr<media::DecodedNv12Frame> {
            for (auto& slot : framePool) {
                if (slot.use_count() == 1) return slot;
            }
            return std::make_shared<media::DecodedNv12Frame>();
        };

        while (!st.stop_requested() && !g_stopRequested.load() && !streamEnded.load()) {
            VideoUnit unit;
            {
                std::unique_lock lock(videoMtx);
                videoCv.wait_for(lock, std::chrono::milliseconds(50),
                                 [&] { return st.stop_requested() || !videoQueue.empty() || g_stopRequested.load() || streamEnded.load(); });
                if (st.stop_requested() || g_stopRequested.load() || streamEnded.load()) break;
                if (videoQueue.empty()) continue;
                unit = std::move(videoQueue.front());
                videoQueue.pop_front();
            }

            if (unit.inputLoss) {
                decoder.flush();
                seenKeyframe = false;
                needXboxKeyframe.store(true, std::memory_order_relaxed);
                mailboxCv.notify_one();
            }
            if (!seenKeyframe) {
                if (!hasIdrOrSps(unit.data)) {
                    needXboxKeyframe.store(true, std::memory_order_relaxed);
                    mailboxCv.notify_one();
                    continue;
                }
                seenKeyframe = true;
            }

            if (haveRtp && unit.rtpTimestamp < lastRtp && (lastRtp - unit.rtpTimestamp) > 0x80000000u) {
                rtpHigh += (int64_t(1) << 32);
            }
            lastRtp = unit.rtpTimestamp;
            haveRtp = true;
            int64_t rtpPts100ns = (rtpHigh + static_cast<int64_t>(unit.rtpTimestamp)) * 1000 / 9;
            if (hasLastPts && rtpPts100ns <= lastPts100ns) {
                rtpPts100ns = lastPts100ns + 166667;
            }
            hasLastPts = true;
            lastPts100ns = rtpPts100ns;

            auto nv12Ptr = acquireFrameBuf();
            const int64_t submitted = now100ns();
            const bool decodedOk = decoder.decode(unit.data.data(), unit.data.size(), rtpPts100ns, *nv12Ptr);
            const int64_t decoded = now100ns();
            perfDecodeUsSum.fetch_add(static_cast<uint64_t>(std::max<int64_t>(0, decoded - submitted) / 10),
                                      std::memory_order_relaxed);
            perfDecodeCount.fetch_add(1, std::memory_order_relaxed);

            // Report presentation immediately after MFT decode (~1-2 ms) so Xbox congestion control stays saturated
            rtc.videoPresented(unit.rtpTimestamp, unit.arrival100ns, submitted, decoded, decoded);
            if (!decodedOk) continue;

            // The MFT may hand back a frame we submitted several AUs ago. Measure that gap so the
            // true age of the picture is visible instead of being hidden inside pcLat.
            if (nv12Ptr->pts100ns > 0) {
                const int64_t lag100ns = rtpPts100ns - nv12Ptr->pts100ns;
                if (lag100ns > 0) {
                    const uint64_t lagUs = static_cast<uint64_t>(lag100ns / 10);
                    perfDecLagUsSum.fetch_add(lagUs, std::memory_order_relaxed);
                    perfDecLagCount.fetch_add(1, std::memory_order_relaxed);
                    uint64_t prevLag = perfDecLagMaxUs.load(std::memory_order_relaxed);
                    while (lagUs > prevLag &&
                           !perfDecLagMaxUs.compare_exchange_weak(prevLag, lagUs, std::memory_order_relaxed)) {}
                }
            }

            if (nv12Ptr->width != lastDecW || nv12Ptr->height != lastDecH) {
                lastDecW = nv12Ptr->width;
                lastDecH = nv12Ptr->height;
                picRef.reset();
                dedup.reset();
            }

            const pipeline::PicturePlane planes[2] = {
                {nv12Ptr->yPlane.data(), nv12Ptr->stride, nv12Ptr->width, nv12Ptr->height},
                {nv12Ptr->uvPlane.data(), nv12Ptr->stride, nv12Ptr->width, nv12Ptr->height / 2},
            };
            // Defer the 5.5 MB reference copy until AFTER waking render/NVENC thread so they overlap in parallel!
            const auto pic = picRef.observe(planes, 2, pipeline::PictureComponents::Bytes,
                                            pipeline::PictureReference::kBand8, true);
            const auto dec = dedup.observe(&pic.id, rtpPts100ns, 166667, false, 333333);

            // Sample the luma plane and measure how far this frame is from the previous one. On a
            // compressed 30 fps game inside a 60 Hz stream the duplicated frames differ ONLY by the
            // console encoder's quantization noise, which the strict band-3 / near-zero-tolerance
            // PictureReference test always reports as "different" - so dedup never fires and frame
            // generation never engages. Measured on real content: duplicates sit around madY 0.19
            // and genuine new frames around 2.19, a >10x separation, so mean-absolute-difference is
            // the signal that actually works here.
            double madY = 0.0;
            bool madValid = false;
            {
                constexpr uint32_t stepX = 8, stepY = 4;
                const uint32_t sw = (nv12Ptr->width + stepX - 1) / stepX;
                const uint32_t sh = (nv12Ptr->height + stepY - 1) / stepY;
                curSig.resize(static_cast<size_t>(sw) * sh);
                size_t idx = 0;
                for (uint32_t y = 0; y < sh; ++y) {
                    const uint8_t* row = nv12Ptr->yPlane.data() + static_cast<size_t>(y * stepY) * nv12Ptr->stride;
                    for (uint32_t x = 0; x < sw; ++x) curSig[idx++] = row[x * stepX];
                }
                if (prevSig.size() == curSig.size()) {
                    unsigned over[10] = {};
                    uint64_t sad = 0;
                    const int kThresh[] = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32};
                    for (size_t i = 0; i < curSig.size(); ++i) {
                        const int a = int(curSig[i]), b = int(prevSig[i]);
                        const int d = a > b ? a - b : b - a;
                        sad += static_cast<uint64_t>(d);
                        if (picDumpFile.is_open()) {
                            for (int k = 0; k < 10; ++k) {
                                if (d > kThresh[k]) ++over[k];
                            }
                        }
                    }
                    madY = static_cast<double>(sad) / static_cast<double>(curSig.size());
                    madValid = true;
                    if (picDumpFile.is_open()) {
                        picDumpFile << pic.id << ',' << rtpPts100ns / 10000 << ',' << pic.over << ',' << pic.max8
                                    << ',' << (dec.keep ? 1 : 0) << ',' << (dec.interval / 10000)
                                    << ',' << std::format("{:.3f}", madY);
                        for (int k = 0; k < 10; ++k) picDumpFile << ',' << over[k];
                        picDumpFile << '\n';
                    }
                }
                prevSig = curSig;
            }

            // A frame that is visually identical to the previous one carries no information, so
            // dropping it is invisible - whether it is a true duplicate or just a static scene.
            // BUT never let the picture starve: in a genuinely static scene every frame looks like a
            // repeat, and dropping all of them freezes the output (and stalls the gate). Force one
            // through at least every 50 ms so the encoder always has something to send.
            const int64_t sinceForward = lastForwardPts > 0 ? (rtpPts100ns - lastForwardPts) : INT64_MAX;
            const bool starving = sinceForward > 500000; // 50 ms in 100 ns units
            const bool madDuplicate = madValid && opts.dedupMad > 0.0f && madY < opts.dedupMad;
            if ((!dec.keep || madDuplicate) && !starving &&
                mailboxLatestSeq.load(std::memory_order_relaxed) > 0) {
                if (pic.commit) picRef.commit(planes, 2);
                continue;
            }

            // Derive the content rate from the gap between frames we actually forwarded. With the
            // console repeating frames this is what tells the adaptive gate the source is ~30 fps,
            // which is the precondition for frame generation to engage at all.
            const int64_t forwardInterval = lastForwardPts > 0 ? (rtpPts100ns - lastForwardPts) : 166667;
            lastForwardPts = rtpPts100ns;
            const double cfps = forwardInterval > 0 ? 1e7 / double(forwardInterval) : 60.0;
            {
                std::lock_guard mlock(mailboxMtx);
                mailboxItem.frame = nv12Ptr;
                mailboxItem.arrivalTime = unit.arrivalTime;
                mailboxItem.contentFps = cfps;
                mailboxItem.interval100ns = forwardInterval;
                const uint64_t seq = ++mailboxSeq;
                mailboxLatestSeq.store(seq, std::memory_order_release);
            }
            mailboxCv.notify_one();
            perfUniqueFrames.fetch_add(1, std::memory_order_relaxed);

            if (pic.commit) {
                picRef.commit(planes, 2);
            }
        }
        CoUninitialize();
    });

    struct PreEncodedAu {
        std::vector<uint8_t> data;
        uint32_t tsMs = 0;
        bool isIdr = false;
        bool isGenerated = false;
        bool valid = false;
    };

    auto sendEncodedAu = [&](const uint8_t* data, size_t sz, uint32_t ts, bool idr, bool gen,
                             std::chrono::steady_clock::time_point arrivalTime, bool recordPcLat) {
        if (questServer.sendVideoAccessUnit(data, sz, ts, idr, gen)) {
            perfBytesSent += sz;
            const auto tSent = std::chrono::steady_clock::now();
            if (recordPcLat && arrivalTime.time_since_epoch().count() > 0) {
                perfPcLatMsSum += std::chrono::duration<double, std::milli>(tSent - arrivalTime).count();
                ++perfPcLatCount;
            }
            lastSentFrameTime = tSent;
        }
        ++perfOutFrames;
    };

    auto encodeAndSendFrame = [&](ID3D12Resource* tex, bool isGenerated,
                                  std::chrono::steady_clock::time_point arrivalTime, bool recordPcLat) {
        const auto tStart = std::chrono::steady_clock::now();
        if (xrActive) {
            if (!xrSink.submitFrame(d3d, tex)) {
                xrActive = false;
            }
        } else if (opts.questServer && questServer.hasClient()) {
            const uint32_t tsMs = static_cast<uint32_t>(
                std::chrono::duration_cast<std::chrono::milliseconds>(tStart - streamOrigin).count());
            nvenc.encodeFrame(tex, tsMs, isGenerated,
                              [&](const uint8_t* data, size_t sz, uint32_t ts, bool idr, bool gen) {
                                  sendEncodedAu(data, sz, ts, idr, gen, arrivalTime, recordPcLat);
                              });
            const auto tEnd = std::chrono::steady_clock::now();
            perfNvencMsSum += std::chrono::duration<double, std::milli>(tEnd - tStart).count();
            ++perfEncCount;
        }
    };

    auto encodeFrameToBuffer = [&](ID3D12Resource* tex, bool isGenerated, PreEncodedAu& outAu) -> bool {
        outAu.valid = false;
        if (!opts.questServer || !questServer.hasClient()) return false;
        const auto tStart = std::chrono::steady_clock::now();
        const uint32_t tsMs = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(tStart - streamOrigin).count());
        const bool ok = nvenc.encodeFrame(tex, tsMs, isGenerated,
                                          [&](const uint8_t* data, size_t sz, uint32_t ts, bool idr, bool gen) {
                                              outAu.data.assign(data, data + sz);
                                              outAu.tsMs = ts;
                                              outAu.isIdr = idr;
                                              outAu.isGenerated = gen;
                                              outAu.valid = true;
                                          });
        const auto tEnd = std::chrono::steady_clock::now();
        perfNvencMsSum += std::chrono::duration<double, std::milli>(tEnd - tStart).count();
        ++perfEncCount;
        return ok && outAu.valid;
    };

    auto maybeEmitPerf = [&]() {
        const auto now = std::chrono::steady_clock::now();
        const double dt = std::chrono::duration<double>(now - lastPerfTime).count();
        if (dt < 2.0) return;

        const uint64_t inUnits = perfInUnits.exchange(0, std::memory_order_relaxed);
        const uint64_t uniqCnt = perfUniqueFrames.exchange(0, std::memory_order_relaxed);
        const uint64_t decCnt = perfDecodeCount.exchange(0, std::memory_order_relaxed);
        const uint64_t decUs = perfDecodeUsSum.exchange(0, std::memory_order_relaxed);
        const uint64_t decLagUsSum = perfDecLagUsSum.exchange(0, std::memory_order_relaxed);
        const uint64_t decLagMaxUs = perfDecLagMaxUs.exchange(0, std::memory_order_relaxed);
        const uint64_t decLagCnt = perfDecLagCount.exchange(0, std::memory_order_relaxed);
        const double decLagMs = decLagCnt ? (double(decLagUsSum) / 1000.0) / double(decLagCnt) : 0.0;
        const double decLagMaxMs = double(decLagMaxUs) / 1000.0;
        const uint64_t qDrop = perfQueueDrops.load(std::memory_order_relaxed);

        const double inFps = double(inUnits) / dt;
        const double uniqFps = double(uniqCnt) / dt;
        const double outFps = double(perfOutFrames) / dt;
        const double decMs = decCnt ? (double(decUs) / 1000.0) / double(decCnt) : 0.0;
        const double gpuMs = perfGpuCount ? perfGpuTotalMsSum / double(perfGpuCount) : 0.0;
        const double upMs = perfGpuCount ? perfGpuUploadMsSum / double(perfGpuCount) : 0.0;
        const double flowMs = perfGpuCount ? perfGpuFlowMsSum / double(perfGpuCount) : 0.0;
        const double fgUpMs = perfGpuCount ? perfGpuFgUpscaleMsSum / double(perfGpuCount) : 0.0;
        const double nvencMs = perfEncCount ? perfNvencMsSum / double(perfEncCount) : 0.0;
        const double pcLatMs = perfPcLatCount ? perfPcLatMsSum / double(perfPcLatCount) : 0.0;
        const double mbps = (double(perfBytesSent) * 8.0) / (dt * 1e6);

        const auto qstats = questServer.consumeStats();
        const auto enc = nvenc.consumeTimings();
        const auto rstats = rtc.stats();
        const uint64_t deltaXb = rstats.videoBytes >= lastXboxVideoBytes ? (rstats.videoBytes - lastXboxVideoBytes) : 0;
        lastXboxVideoBytes = rstats.videoBytes;
        const double xboxMbps = (double(deltaXb) * 8.0) / (dt * 1e6);

        const uint32_t inW = lastProcessedNv12 ? lastProcessedNv12->width : pipe.config().inputWidth;
        const uint32_t inH = lastProcessedNv12 ? lastProcessedNv12->height : pipe.config().inputHeight;
        const std::string summary = std::format(
            "in={:.1f} ({:.1f}M {}x{}) uniq={:.1f} out={:.1f} fps [{}] -> {}x{}@{} | pcLat={:.2f}ms dec={:.2f}ms decLag={:.1f}/{:.1f}ms gpu={:.2f}ms(up={:.2f},flow={:.2f},fg+up={:.2f}) nvenc={:.2f}ms tcp={:.2f}/{:.2f}ms | out={:.1f} Mbps qDrop={} tcpDrop={} qMax={} rtt={:.1f}ms | quest={:.2f}/{:.2f}ms enc[s={:.2f} p={:.2f} w={:.2f} l={:.2f} c={:.2f}]",
            inFps, xboxMbps, inW, inH, uniqFps, outFps, activeModeTag, opts.outWidth, opts.outHeight, opts.targetFps,
            pcLatMs, decMs, decLagMs, decLagMaxMs, gpuMs, upMs, flowMs, fgUpMs, nvencMs, qstats.tcpSendAvgMs, qstats.tcpSendMaxMs,
            mbps, qDrop, qstats.videoDropped, qstats.maxBacklog, rstats.rttMs,
            qstats.e2eMinMs, qstats.e2eAvgMs,
            enc.submitMs, enc.picMs, enc.waitMs, enc.lockMs, enc.cbMs);
        log::info("perf", summary);
        if (perfFile.is_open()) {
            perfFile << summary << '\n';
            perfFile.flush();
        }

        lastPerfTime = now;
        perfOutFrames = 0;
        perfBytesSent = 0;
        perfGpuCount = 0;
        perfGpuTotalMsSum = 0.0;
        perfGpuUploadMsSum = 0.0;
        perfGpuFlowMsSum = 0.0;
        perfGpuFgUpscaleMsSum = 0.0;
        perfEncCount = 0;
        perfNvencMsSum = 0.0;
        perfPcLatMsSum = 0.0;
        perfPcLatCount = 0;
    };

    uint64_t consumedSeq = 0;
    auto lastKeyframeSentTime = std::chrono::steady_clock::now() - std::chrono::seconds(10);

    while (!g_stopRequested.load() && !streamEnded.load()) {
        if (needXboxKeyframe.load(std::memory_order_relaxed)) {
            const auto now = std::chrono::steady_clock::now();
            if (now - lastKeyframeSentTime >= std::chrono::milliseconds(500)) {
                needXboxKeyframe.store(false, std::memory_order_relaxed);
                lastKeyframeSentTime = now;
                rtc.requestKeyframe();
            }
        }
        maybeEmitPerf();
        rtc.pumpBitrateRequest();

        if (opts.questServer) {
            std::optional<sink::QuestStreamServer::ClientStreamConfig> reqCfg;
            {
                std::lock_guard lk(cfgMtx);
                if (pendingConfig.has_value()) {
                    reqCfg = pendingConfig;
                    pendingConfig.reset();
                }
            }
            if (reqCfg.has_value()) {
                const auto& c = *reqCfg;
                const bool valid = c.width >= 1280 && c.width <= 3840 && (c.width & 1u) == 0 &&
                                   c.height >= 720 && c.height <= 2160 && (c.height & 1u) == 0 &&
                                   (c.fps == 60 || c.fps == 90) &&
                                   c.bitrateMbps >= 10 && c.bitrateMbps <= 200;
                // The Quest asks for 30/50/80/120 Mbps. Honour that, but never exceed the local cap:
                // NVENC's cost (and therefore PC-side latency) grows with bitrate, so an over-large
                // request just burns milliseconds encoding detail the Xbox source never contained.
                const uint32_t cappedMbps =
                    (opts.maxBitrateMbps > 0 && c.bitrateMbps > opts.maxBitrateMbps) ? opts.maxBitrateMbps
                                                                                    : c.bitrateMbps;
                // Clamp the requested encode size, keeping the aspect ratio. Encoding 2160p roughly
                // doubles NVENC time and pushes the per-frame budget to ~90% duty, which shows up as
                // dropped frames rather than any real detail (the console source is usually <=1440p).
                uint32_t cappedW = c.width;
                uint32_t cappedH = c.height;
                if (opts.maxOutputHeight > 0 && cappedH > opts.maxOutputHeight) {
                    cappedW = ((cappedW * opts.maxOutputHeight) / c.height) & ~1u;
                    cappedH = opts.maxOutputHeight;
                }
                const bool resCapped = cappedW != c.width || cappedH != c.height;
                if (valid && (cappedW != opts.outWidth || cappedH != opts.outHeight ||
                              c.fps != opts.targetFps || cappedMbps != opts.bitrateMbps)) {
                    log::info("app", std::format("Applying Quest client config: {}x{} @ {}fps {} Mbps{}{}",
                                                 cappedW, cappedH, c.fps, cappedMbps,
                                                 resCapped ? std::format(" (client asked {}x{}, height capped)",
                                                                         c.width, c.height)
                                                           : std::string(),
                                                 cappedMbps != c.bitrateMbps
                                                     ? std::format(" (client asked {} Mbps, capped)", c.bitrateMbps)
                                                     : std::string()));
                    opts.outWidth = cappedW;
                    opts.outHeight = cappedH;
                    opts.targetFps = c.fps;
                    opts.bitrateMbps = cappedMbps;
                    framePeriod = std::chrono::microseconds(1000000 / std::max(1u, opts.targetFps));

                    auto newPcfg = pipe.config();
                    newPcfg.outputWidth = opts.outWidth;
                    newPcfg.outputHeight = opts.outHeight;
                    newPcfg.targetFps = opts.targetFps;
                    newPcfg.rcasSharpness = opts.rcasSharpness;
                    if (!pipe.initialize(d3d, newPcfg) ||
                        !nvenc.initialize(d3d, sink::NvencConfig{opts.outWidth, opts.outHeight, opts.targetFps, opts.bitrateMbps, opts.nvencPreset, opts.codec})) {
                        log::error("app", "Live stream reconfig failed");
                        break;
                    }
                    questServer.setVideoConfig(opts.outWidth, opts.outHeight, opts.targetFps, nvenc.spsPps());
                    nvenc.requestIdr();
                    forceImmediateFrame.store(true, std::memory_order_relaxed);
                } else if (valid) {
                    nvenc.requestIdr();
                    forceImmediateFrame.store(true, std::memory_order_relaxed);
                }
            }
        }

        MailboxItem item;
        bool hasNewUnique = false;
        {
            std::unique_lock mlock(mailboxMtx);
            mailboxCv.wait_for(mlock, std::chrono::milliseconds(20), [&] {
                return mailboxSeq > consumedSeq || forceImmediateFrame.load(std::memory_order_relaxed) ||
                       g_stopRequested.load() || streamEnded.load();
            });
            if (mailboxSeq > consumedSeq) {
                item = mailboxItem;
                consumedSeq = mailboxSeq;
                hasNewUnique = (item.frame != nullptr);
            }
        }

        if (!hasNewUnique) {
            if (opts.questServer && questServer.hasClient() && lastProcessedNv12 && lastProcessedNv12->width > 0) {
                const auto now = std::chrono::steady_clock::now();
                const bool immediate = forceImmediateFrame.exchange(false, std::memory_order_relaxed);
                const bool stale = (now - lastSentFrameTime) > std::chrono::milliseconds(200);
                if (immediate || stale) {
                    if (immediate) nvenc.requestIdr();
                    if (lastProcessedNv12->width != pipe.config().inputWidth || lastProcessedNv12->height != pipe.config().inputHeight) {
                        auto newCfg = pipe.config();
                        newCfg.inputWidth = lastProcessedNv12->width;
                        newCfg.inputHeight = lastProcessedNv12->height;
                        pipe.initialize(d3d, newCfg);
                    }
                    pipeline::ProcessedFrameBatch batch;
                    if (pipe.processUniqueFrame(*lastProcessedNv12, batch, 1u, false) && !batch.frames.empty()) {
                        encodeAndSendFrame(batch.frames.back(), false, {}, false);
                    }
                }
                const int64_t nowUs = std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count();
                if ((nowUs - lastXboxVideoUs.load(std::memory_order_relaxed)) > 1000000) {
                    needXboxKeyframe.store(true, std::memory_order_relaxed);
                    lastXboxVideoUs.store(nowUs, std::memory_order_relaxed);
                }
            }
            continue;
        }

        lastProcessedNv12 = std::move(item.frame);

        // Adaptive Frame-Gen Gate: NEVER double-generate 60fps content into 110fps/220Mbps!
        const bool prevFgActive = fgActive;
        if (!opts.allowFrameGen) {
            fgActive = false;
        } else {
            if (item.contentFps > 1.0 && item.contentFps < 200.0) {
                contentFpsSmoothed = contentFpsSmoothed * 0.90 + item.contentFps * 0.10;
            }
            // Enable only on a sustained, clearly-low rate; disable only on a sustained high one.
            // The 34..46 band is a deliberate dead zone so a noisy sample cannot invert the mode.
            if (contentFpsSmoothed < 34.0) {
                ++lowFpsStreak;
                highFpsStreak = 0;
                if (lowFpsStreak >= 12) fgActive = true;
            } else if (contentFpsSmoothed > 46.0) {
                ++highFpsStreak;
                lowFpsStreak = 0;
                // A long streak on purpose: brief motion bursts can momentarily make every frame
                // look unique, and each mode flip costs a pipeline reset plus a latency spike.
                if (highFpsStreak >= 40) fgActive = false;
            }
        }
        if (fgActive != prevFgActive) {
            if (fgActive) {
                pipe.resetTemporalHistory();
                log::info("app", std::format("Adaptive FG: ENABLED (input ~{:.1f} fps -> {} fps)",
                                             item.contentFps, opts.targetFps));
            } else {
                log::info("app", std::format("Adaptive FG: BYPASSED (native ~{:.1f} fps low-latency passthrough)",
                                             item.contentFps));
            }
        }

        if (forceImmediateFrame.exchange(false, std::memory_order_relaxed)) {
            nvenc.requestIdr();
        }

        // Skip GPU enhancement work when no client is connected in Quest server mode (saves power & keeps decoder hot)
        if (opts.questServer && !xrActive && !questServer.hasClient()) {
            activeModeTag = fgActive ? "FG-IDLE" : "SHARP-IDLE";
            continue;
        }

        if (lastProcessedNv12->width != pipe.config().inputWidth || lastProcessedNv12->height != pipe.config().inputHeight) {
            auto newCfg = pipe.config();
            newCfg.inputWidth = lastProcessedNv12->width;
            newCfg.inputHeight = lastProcessedNv12->height;
            pipe.initialize(d3d, newCfg);
        }

        // Fast GPU-chained path:
        //   - When FG bypassed & in == out: Nv12ToRgb -> RCAS sharpen (~0.45ms, NO NVOFA, NO EASU) -> NVENC!
        //   - When FG bypassed & in != out: Nv12ToRgb -> EASU -> RCAS (~0.7ms, NO NVOFA) -> NVENC!
        //   - When FG active (30fps source): NVOFA + FlowRefine + FlowFill + FrameGen + EASU/RCAS -> pre-encoded pacing!
        // Emit enough frames per input frame that the OUTPUT lands on targetFps, whatever rate the
        // content happens to be changing at. A fixed 2x/3x multiplier under-delivers whenever the
        // measured rate dips (static scenes, slow pans): 2 x 20 fps is 40 fps, not 60. Clamped to
        // 1..4 because the pipeline only carries a handful of interpolated frames per real one.
        uint32_t fgMult = 1;
        if (fgActive) {
            const double cf = std::clamp(item.contentFps, 5.0, double(opts.targetFps));
            fgMult = static_cast<uint32_t>(std::clamp<long long>(std::llround(double(opts.targetFps) / cf), 1, 4));
        }
        activeModeTag = fgActive ? "FG" : ((lastProcessedNv12->width == opts.outWidth && lastProcessedNv12->height == opts.outHeight) ? "RCAS" : "UPSCALE");
        const bool gpuChainToNvenc = (opts.questServer && !xrActive);

        pipeline::ProcessedFrameBatch batch;
        if (!pipe.processUniqueFrame(*lastProcessedNv12, batch, fgMult, !gpuChainToNvenc)) continue;

        const auto batchStart = std::chrono::steady_clock::now();
        if (gpuChainToNvenc && batch.frames.size() > 1) {
            // 30->60/90 FG mode: encode & send frame[0] immediately, then PRE-ENCODE subsequent frames
            // during the pacing window so the real frame transmits at 0.05ms when targetTime hits!
            encodeAndSendFrame(batch.frames[0], batch.isGenerated[0], item.arrivalTime, false);
            const auto gpuT = pipe.readLastGpuTimings(batch.didFrameGen);
            perfGpuTotalMsSum += gpuT.nv12UploadAndConvertMs + gpuT.opticalFlowMs + gpuT.flowRefineAndFillMs + gpuT.frameGenAndUpscaleMs;
            perfGpuUploadMsSum += gpuT.nv12UploadAndConvertMs;
            perfGpuFlowMsSum += gpuT.opticalFlowMs + gpuT.flowRefineAndFillMs;
            perfGpuFgUpscaleMsSum += gpuT.frameGenAndUpscaleMs;
            ++perfGpuCount;

            for (size_t k = 1; k < batch.frames.size(); ++k) {
                const bool isLast = (k + 1 == batch.frames.size());
                if (!isLast && questServer.sendBacklogFrames() > 1) {
                    continue;
                }
                PreEncodedAu pre;
                const bool encoded = encodeFrameToBuffer(batch.frames[k], batch.isGenerated[k], pre);
                const auto targetTime = batchStart + framePeriod * static_cast<long long>(k);
                {
                    std::unique_lock mlock(mailboxMtx);
                    mailboxCv.wait_until(mlock, targetTime, [&] {
                        return mailboxLatestSeq.load(std::memory_order_acquire) > consumedSeq ||
                               forceImmediateFrame.load(std::memory_order_relaxed) ||
                               g_stopRequested.load() || streamEnded.load();
                    });
                }
                if (encoded) {
                    const uint32_t sendTsMs = static_cast<uint32_t>(
                        std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - streamOrigin)
                            .count());
                    sendEncodedAu(pre.data.data(), pre.data.size(), sendTsMs, pre.isIdr, pre.isGenerated,
                                  item.arrivalTime, isLast);
                }
            }
        } else {
            for (size_t k = 0; k < batch.frames.size(); ++k) {
                if (opts.questServer && k + 1 < batch.frames.size() && questServer.sendBacklogFrames() > 1) {
                    continue;
                }
                encodeAndSendFrame(batch.frames[k], batch.isGenerated[k], item.arrivalTime, k + 1 == batch.frames.size());
                if (k == 0 && gpuChainToNvenc) {
                    const auto gpuT = pipe.readLastGpuTimings(batch.didFrameGen);
                    perfGpuTotalMsSum += gpuT.nv12UploadAndConvertMs + gpuT.opticalFlowMs + gpuT.flowRefineAndFillMs + gpuT.frameGenAndUpscaleMs;
                    perfGpuUploadMsSum += gpuT.nv12UploadAndConvertMs;
                    perfGpuFlowMsSum += gpuT.opticalFlowMs + gpuT.flowRefineAndFillMs;
                    perfGpuFgUpscaleMsSum += gpuT.frameGenAndUpscaleMs;
                    ++perfGpuCount;
                }
                if ((xrActive || opts.questServer) && k + 1 < batch.frames.size()) {
                    const auto targetTime = batchStart + framePeriod * static_cast<long long>(k + 1);
                    std::unique_lock mlock(mailboxMtx);
                    mailboxCv.wait_until(mlock, targetTime, [&] {
                        return mailboxLatestSeq.load(std::memory_order_acquire) > consumedSeq ||
                               forceImmediateFrame.load(std::memory_order_relaxed) ||
                               g_stopRequested.load() || streamEnded.load();
                    });
                }
            }
            if (!gpuChainToNvenc) {
                perfGpuTotalMsSum += batch.timings.totalGpuMs;
                perfGpuUploadMsSum += batch.timings.nv12UploadAndConvertMs;
                perfGpuFlowMsSum += batch.timings.opticalFlowMs + batch.timings.flowRefineAndFillMs;
                perfGpuFgUpscaleMsSum += batch.timings.frameGenAndUpscaleMs;
                ++perfGpuCount;
            }
        }
    }

    streamEnded.store(true);
    decodeThread.request_stop();
    videoCv.notify_all();
    if (decodeThread.joinable()) decodeThread.join();
    controlThread.request_stop();
    questServer.stop();
    nvenc.shutdown();
    if (questOpus) opus_decoder_destroy(questOpus);
    xrSink.shutdown();
    pipe.shutdown();
    d3d.shutdown();
    decoder.shutdown();
    rtc.close();
    audio.stop();
    try {
        api.stop(sessionId);
    } catch (...) {}
    return 0;
}

} // namespace

// Tiny always-on helper so the headset can start the streamer remotely. Wake-on-LAN can power the
// machine on, but only something already running on it can launch the streaming app.
int runAgent() {
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 1;
    const SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == INVALID_SOCKET) return 1;
    BOOL reuse = TRUE;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(7662);
    addr.sin_addr.s_addr = INADDR_ANY;
    if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        log::error("agent", "could not bind UDP 7662");
        closesocket(s);
        return 1;
    }
    DWORD timeoutMs = 1000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeoutMs), sizeof(timeoutMs));
    log::info("agent", "listening on UDP 7662 for GTA60_START (headset can launch the streamer)");

    const auto streamerRunning = []() {
        const SOCKET t = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (t == INVALID_SOCKET) return false;
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_port = htons(7660);
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        const bool up = connect(t, reinterpret_cast<sockaddr*>(&a), sizeof(a)) == 0;
        closesocket(t);
        return up;
    };

    char buf[128];
    // The streamer needs ~14 s to reach the point where it binds TCP 7660 (Xbox handshake, WebRTC,
    // NVENC init), and the socket check below can only see it after that. Without this cooldown a
    // burst of start requests - two datagrams, or the headset retrying - launches several streamers
    // at once: duplicate Xbox sessions and two processes fighting over the encoder and the port.
    auto lastLaunch = std::chrono::steady_clock::time_point{};
    constexpr auto kLaunchCooldown = std::chrono::seconds(30);
    while (!g_stopRequested.load(std::memory_order_relaxed)) {
        sockaddr_in from{};
        int fromLen = sizeof(from);
        const int n = recvfrom(s, buf, sizeof(buf) - 1, 0, reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n <= 0) continue;
        buf[n] = '\0';
        if (std::strncmp(buf, "GTA60_START", 11) != 0) continue;
        if (streamerRunning()) {
            log::info("agent", "start request ignored: streamer already running");
            continue;
        }
        const auto now = std::chrono::steady_clock::now();
        if (lastLaunch.time_since_epoch().count() != 0 && now - lastLaunch < kLaunchCooldown) {
            log::info("agent", "start request ignored: a streamer was launched moments ago");
            continue;
        }
        wchar_t exe[MAX_PATH]{};
        if (!GetModuleFileNameW(nullptr, exe, MAX_PATH)) continue;
        std::wstring cmd = L"\"" + std::wstring(exe) + L"\" --connect auto --quest-server --bitrate 120";

        // Give the child its own log instead of a console window: an agent-launched streamer used to
        // write nowhere at all, which made "it did not come back" impossible to diagnose.
        std::wstring logPath = std::filesystem::path(exe).parent_path().wstring() + L"\\stream-agent.log";
        SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
        HANDLE logFile = CreateFileW(logPath.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                     &sa, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        STARTUPINFOW si{};
        si.cb = sizeof(si);
        if (logFile != INVALID_HANDLE_VALUE) {
            si.dwFlags = STARTF_USESTDHANDLES;
            si.hStdOutput = logFile;
            si.hStdError = logFile;
            si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        }
        PROCESS_INFORMATION pi{};
        const DWORD flags = (logFile != INVALID_HANDLE_VALUE) ? CREATE_NO_WINDOW : CREATE_NEW_CONSOLE;
        const BOOL spawned = CreateProcessW(exe, cmd.data(), nullptr, nullptr,
                                            logFile != INVALID_HANDLE_VALUE, flags, nullptr, nullptr, &si, &pi);
        if (logFile != INVALID_HANDLE_VALUE) CloseHandle(logFile);
        if (spawned) {
            lastLaunch = now;
            log::info("agent", std::format("start request accepted: launching streaming app (log: {})",
                                           log::narrow(logPath)));
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
        } else {
            log::error("agent", std::format("failed to launch streamer err={}", GetLastError()));
        }
    }
    closesocket(s);
    return 0;
}

int main(int argc, char** argv) {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    SetConsoleCtrlHandler(consoleCtrlHandler, TRUE);
    timeBeginPeriod(1);
    SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS);

    CliOptions opts;
    if (!parseArgs(argc, argv, opts)) {
        printUsage();
        timeEndPeriod(1);
        CoUninitialize();
        return (opts.mode == CliOptions::Mode::Help) ? 0 : 1;
    }

    int rc = 0;
    try {
        switch (opts.mode) {
        case CliOptions::Mode::Login:
            rc = runLogin();
            break;
        case CliOptions::Mode::ListConsoles:
            rc = runListConsoles();
            break;
        case CliOptions::Mode::Connect:
            rc = runConnect(opts);
            break;
        case CliOptions::Mode::SyntheticDemo:
            rc = runSyntheticDemo(opts);
            break;
        case CliOptions::Mode::Agent:
            rc = runAgent();
            break;
        case CliOptions::Mode::Help:
            printUsage();
            rc = 0;
            break;
        }
    } catch (const std::exception& e) {
        log::error("fatal", e.what());
        rc = 1;
    }

    timeEndPeriod(1);
    CoUninitialize();
    return rc;
}

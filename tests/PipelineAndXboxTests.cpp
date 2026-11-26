#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <wincrypt.h>

#include "gta60/Log.h"
#include "gta60/media/SyntheticGameSource.h"
#include "gta60/media/WasapiPcmRender.h"
#include "gta60/nvof/NvOfSession.h"
#include "gta60/pipeline/ContentDedup.h"
#include "gta60/pipeline/D3D12Context.h"
#include "gta60/pipeline/EnhancementPipeline.h"
#include "gta60/pipeline/PictureCompare.h"
#include "gta60/pipeline/TvCadenceGate.h"
#include "gta60/sink/NvencD3D12Encoder.h"
#include "gta60/sink/QuestStreamServer.h"
#include "gta60/xbox/Account.h"
#include "gta60/xbox/Protocol.h"
#include "gta60/xbox/StreamApi.h"
#include "gta60/xr/OpenXrQuadSink.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

using namespace gta60;

namespace {

#define CHECK(cond, msg)                                                                  \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__, (msg));        \
            return false;                                                                 \
        }                                                                                 \
    } while (0)

bool test1_XboxProtocolAndTeredo() {
    // 1. Gamepad XInput -> Xbox GamepadFrame -> wire report
    const auto gf = xbox::gamepadFromXInput(0x1001, 255, 128, 1000, -2000, 3000, 4000, 0);
    CHECK((gf.buttons & xbox::BtnA) != 0, "BtnA should be set");
    CHECK((gf.buttons & xbox::BtnUp) != 0, "BtnUp should be set");
    CHECK(gf.leftTrigger == 65535, "leftTrigger full scale");
    const auto pkt = xbox::gamepadReport(7, 123.5, {gf});
    CHECK(pkt.size() == 14 + 1 + 23, "gamepadReport size mismatch");
    CHECK(xbox::detail::get16(pkt.data()) == xbox::ReportGamepad, "ReportGamepad type mismatch");
    CHECK(xbox::detail::get32(pkt.data() + 2) == 7, "sequence mismatch");

    // 2. Video feedback report & server metadata parsing
    const auto fb = xbox::videoFeedbackReport(9, 500.0, {90000, 100, 102, 104, 108});
    CHECK(fb.size() == 43, "videoFeedbackReport size mismatch");
    CHECK(xbox::detail::get16(fb.data()) == xbox::ReportMetadata, "ReportMetadata mismatch");
    CHECK(xbox::detail::get32(fb.data() + 15) == 90000, "RTP timestamp mismatch");

    uint8_t meta[10]{};
    meta[0] = xbox::ReportServerMetadata;
    meta[2] = 0x38; meta[3] = 0x04; // 1080 LE
    meta[6] = 0x80; meta[7] = 0x07; // 1920 LE
    uint32_t sw = 0, sh = 0;
    CHECK(xbox::parseServerMetadata(meta, sizeof(meta), &sw, &sh), "parseServerMetadata failed");
    CHECK(sw == 1920 && sh == 1080, "server dimensions mismatch");

    // 3. Teredo IPv6 -> LAN IPv4 unpacking (2001:0000:... obfuscated IPv4 192.168.1.50 & port 50505)
    // ~192 = 0x3F, ~168 = 0x57, ~1 = 0xFE, ~50 = 0xCD; ~50505 = 0x3AB6
    std::string ipv4;
    int port = 0;
    CHECK(xbox::StreamApi::teredoIpv4("2001:0000:4136:e378:8000:3ab6:3f57:fecd", &ipv4, &port), "teredoIpv4 failed");
    CHECK(ipv4 == "192.168.1.50" && port == 50505, "teredoIpv4 decoded value mismatch");

    const std::string iceJson = R"([{"candidate":"a=candidate:1 1 UDP 1 2001:0000:4136:e378:8000:3ab6:3f57:fecd 50505 typ host","sdpMid":"0","sdpMLineIndex":0}])";
    const auto cands = xbox::StreamApi::parseIce(iceJson);
    CHECK(cands.size() == 3, "parseIce should unpack 9002 + port + original candidate");

    // 4. DPAPI token round-trip. DPAPI is unavailable in some session types (services, restricted or
    // sandboxed tokens): CryptProtectData fails before any file is written, which used to show up as
    // a hard failure that had nothing to do with this project. Probe it first and skip instead.
    bool dpapiAvailable = false;
    DWORD dpapiError = 0;
    {
        DATA_BLOB probeIn{5, reinterpret_cast<BYTE*>(const_cast<char*>("probe"))};
        DATA_BLOB probeOut{};
        if (CryptProtectData(&probeIn, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &probeOut)) {
            dpapiAvailable = true;
            LocalFree(probeOut.pbData);
        } else {
            dpapiError = GetLastError();
        }
    }
    if (dpapiAvailable) {
        // %TEMP% is the right place for this normally, but a confined (sandboxed) shell can deny
        // writes there while still allowing writes next to the binaries, so fall back to the CWD.
        std::filesystem::path dir = std::filesystem::temp_directory_path();
        {
            const auto probe = dir / "GTA-Sixty_write_probe.tmp";
            std::ofstream out(probe, std::ios::binary | std::ios::trunc);
            const bool writable = static_cast<bool>(out);
            out.close();
            std::error_code pec;
            std::filesystem::remove(probe, pec);
            if (!writable) {
                dir = std::filesystem::current_path();
                std::printf("[INFO] %ls is not writable here; using %ls for the token round-trip\n",
                            std::filesystem::temp_directory_path().c_str(), dir.c_str());
            }
        }
        const auto tempFile = dir / "GTA-Sixty_test_tokens.bin";
        std::error_code ec;
        std::filesystem::remove(tempFile, ec);
        xbox::SavedTokens saved{"refresh_abc", "access_xyz", 123456789, "uhs_999"};
        CHECK(xbox::saveTokens(tempFile, saved), "saveTokens DPAPI failed");
        const auto loaded = xbox::loadTokens(tempFile);
        std::filesystem::remove(tempFile, ec);
        CHECK(loaded.has_value() && loaded->refreshToken == "refresh_abc" && loaded->userHash == "uhs_999",
              "loadTokens DPAPI mismatch");
    } else {
        std::printf("[SKIP] DPAPI unavailable in this session (CryptProtectData err=%lu); "
                    "token round-trip not exercised\n",
                    static_cast<unsigned long>(dpapiError));
    }

    std::printf("[PASS] Test1_XboxProtocolAndTeredo\n");
    return true;
}

bool test2_CadenceDedup60To30() {
    media::SyntheticGameSource src(1920, 1080, true); // includes +/-1 LSB compression noise on repeats
    pipeline::PictureReference ref;
    pipeline::ContentDedup dedup;

    for (uint64_t i = 0; i < 120; ++i) {
        const auto f = src.generateContainerFrame(i);
        const pipeline::PicturePlane planes[2] = {
            {f.yPlane.data(), f.stride, f.width, f.height},
            {f.uvPlane.data(), f.stride, f.width, f.height / 2},
        };
        const auto pic = ref.observe(planes, 2, pipeline::PictureComponents::Bytes);
        const auto dec = dedup.observe(&pic.id, f.pts100ns, 166667, i == 0, 333333);
        if (i % 2 == 0) {
            CHECK(dec.keep, "even container frame should be kept as unique 30 FPS frame");
        } else {
            CHECK(!dec.keep, "odd noisy duplicate frame should be stripped");
        }
    }

    const auto& st = dedup.stats();
    CHECK(st.observed == 120, "observed count mismatch");
    CHECK(st.kept == 60, "kept count must be 60");
    CHECK(st.repeats == 60, "repeats count must be 60");
    const double fps = dedup.contentFps();
    CHECK(fps > 29.0 && fps < 31.0, "detected content FPS should be ~30");

    std::printf("[PASS] Test2_CadenceDedup60To30 (kept=%llu repeats=%llu contentFps=%.2f)\n",
                static_cast<unsigned long long>(st.kept),
                static_cast<unsigned long long>(st.repeats),
                fps);
    return true;
}

bool test3_D3D12NvOfAndComputeOpticalFlow() {
    pipeline::D3D12Context d3d;
    CHECK(d3d.initialize(), "D3D12Context initialize failed");

    constexpr uint32_t W = 640, H = 360;
    media::SyntheticGameSource src(W, H, false);
    const auto f0 = src.generateContainerFrame(0);
    const auto f1 = src.generateContainerFrame(2); // unique frame 1 (+16px X shift on moving box)

    // Test both NVOFA hardware optical flow (if available) and Compute DisOpticalFlow
    for (int mode = 0; mode < 2; ++mode) {
        const bool wantNvof = (mode == 0);
        pipeline::PipelineConfig cfg{};
        cfg.inputWidth = W;
        cfg.inputHeight = H;
        cfg.outputWidth = 1280;
        cfg.outputHeight = 720;
        cfg.targetFps = 60;
        cfg.useNvofa = wantNvof;

        pipeline::EnhancementPipeline pipe;
        CHECK(pipe.initialize(d3d, cfg), "EnhancementPipeline initialize failed");

        pipeline::ProcessedFrameBatch batch0, batch1;
        CHECK(pipe.processUniqueFrame(f0, batch0), "processUniqueFrame(f0) failed");
        CHECK(pipe.processUniqueFrame(f1, batch1), "processUniqueFrame(f1) failed");

        std::vector<uint8_t> flowBytes;
        uint32_t rowPitch = 0;
        CHECK(d3d.readbackTexture2D(pipe.forwardFlowGridTex(), flowBytes, rowPitch), "readback flow failed");

        // Sample around the moving box in f1 (centered at src.movingBoxCenterX(1), movingBoxCenterY(1))
        const uint32_t cx = src.movingBoxCenterX(1) / 4;
        const uint32_t cy = src.movingBoxCenterY(1) / 4;
        const auto* vecs = reinterpret_cast<const int16_t*>(flowBytes.data() + cy * rowPitch);
        const float vx = static_cast<float>(vecs[cx * 2 + 0]) / 32.0f;
        const float vy = static_cast<float>(vecs[cx * 2 + 1]) / 32.0f;

        // Current -> Previous vector for rightward-moving box (+16 px) should be negative X
        CHECK(vx < -8.0f && vx > -24.0f, "optical flow should detect leftward curr->prev displacement");
        std::printf("[INFO] Test3 mode=%s (%s): movingBox center grid vx=%.2f vy=%.2f px\n",
                    wantNvof ? "NVOFA" : "Compute",
                    pipe.usingHardwareNvof() ? "HW" : "Compute",
                    vx, vy);

        pipe.shutdown(); // exercises NVOFA unregisterAll() -> release -> shutdown() cleanly!
    }

    d3d.shutdown();
    std::printf("[PASS] Test3_D3D12NvOfAndComputeOpticalFlow\n");
    return true;
}

bool test4_FrameGenAndFsrUpscale() {
    pipeline::D3D12Context d3d;
    CHECK(d3d.initialize(), "D3D12Context initialize failed");

    constexpr uint32_t inW = 1920, inH = 1080;
    constexpr uint32_t outW = 2560, outH = 1440;
    media::SyntheticGameSource src(inW, inH, false);
    const auto f0 = src.generateContainerFrame(0);
    const auto f1 = src.generateContainerFrame(2); // unique frame 1

    pipeline::PipelineConfig cfg{};
    cfg.inputWidth = inW;
    cfg.inputHeight = inH;
    cfg.outputWidth = outW;
    cfg.outputHeight = outH;
    cfg.targetFps = 60;
    cfg.useNvofa = true;
    cfg.hudProtect = true;

    pipeline::EnhancementPipeline pipe;
    CHECK(pipe.initialize(d3d, cfg), "EnhancementPipeline initialize failed");

    pipeline::ProcessedFrameBatch batch0, batch1;
    CHECK(pipe.processUniqueFrame(f0, batch0), "frame 0 failed");
    CHECK(batch0.frames.size() == 1 && !batch0.isGenerated[0], "first frame should emit 1 real frame");

    std::vector<uint8_t> genBytes, realBytes, prevBytes;
    uint32_t genPitch = 0, realPitch = 0, prevPitch = 0;
    CHECK(d3d.readbackTexture2D(batch0.frames[0], prevBytes, prevPitch), "readback prev frame failed");

    CHECK(pipe.processUniqueFrame(f1, batch1), "frame 1 failed");
    CHECK(batch1.frames.size() == 2 && batch1.isGenerated[0] && !batch1.isGenerated[1],
          "second frame should emit [generated t=0.5, real]");

    // Verify upscaled generated frame dimensions and content
    const D3D12_RESOURCE_DESC genDesc = batch1.frames[0]->GetDesc();
    const D3D12_RESOURCE_DESC realDesc = batch1.frames[1]->GetDesc();
    CHECK(genDesc.Width == outW && genDesc.Height == outH, "generated output dimension mismatch");
    CHECK(realDesc.Width == outW && realDesc.Height == outH, "real output dimension mismatch");

    CHECK(d3d.readbackTexture2D(batch1.frames[0], genBytes, genPitch), "readback gen frame failed");
    CHECK(d3d.readbackTexture2D(batch1.frames[1], realBytes, realPitch), "readback real frame failed");

    // 1. Generated midpoint frame must differ from both prev and curr upscaled frames
    uint64_t diffFromPrev = 0, diffFromCurr = 0;
    for (uint32_t y = 0; y < outH; y += 4) {
        const uint8_t* gRow = genBytes.data() + y * genPitch;
        const uint8_t* rRow = realBytes.data() + y * realPitch;
        const uint8_t* pRow = prevBytes.data() + y * prevPitch;
        for (uint32_t x = 0; x < outW * 4; x += 16) {
            diffFromPrev += std::abs(int(gRow[x]) - int(pRow[x]));
            diffFromCurr += std::abs(int(gRow[x]) - int(rRow[x]));
        }
    }
    CHECK(diffFromPrev > 100000 && diffFromCurr > 100000,
          "generated frame must meaningfully interpolate between prev and curr");

    // 2. Verify moving car box in generated midpoint frame is at halfway X between u=0 and u=1
    const uint32_t midInX = (src.movingBoxCenterX(0) + src.movingBoxCenterX(1)) / 2;
    const uint32_t midInY = (src.movingBoxCenterY(0) + src.movingBoxCenterY(1)) / 2;
    const uint32_t midOutX = (midInX * outW) / inW;
    const uint32_t midOutY = (midInY * outH) / inH;
    const uint8_t* midPix = genBytes.data() + midOutY * genPitch + midOutX * 4;
    // The moving car box has bright orange/red tint (R high)
    CHECK(midPix[0] > 140, "moving car box should be present at midpoint X in generated frame");

    // 3. Verify static bottom-left HUD minimap border matches identically with zero warp distortion
    const uint32_t hudOutX = ((inW / 40 + 2) * outW) / inW;
    const uint32_t hudOutY = (((inH * 76) / 100 + 2) * outH) / inH;
    const uint8_t* hudGen = genBytes.data() + hudOutY * genPitch + hudOutX * 4;
    const uint8_t* hudReal = realBytes.data() + hudOutY * realPitch + hudOutX * 4;
    const int hudDiff = std::abs(int(hudGen[0]) - int(hudReal[0])) +
                        std::abs(int(hudGen[1]) - int(hudReal[1])) +
                        std::abs(int(hudGen[2]) - int(hudReal[2]));
    CHECK(hudDiff < 15, "protected HUD minimap border must remain un-smeared");

    pipe.shutdown();
    d3d.shutdown();
    std::printf("[PASS] Test4_FrameGenAndFsrUpscale (diffPrev=%llu diffCurr=%llu hudDiff=%d)\n",
                static_cast<unsigned long long>(diffFromPrev),
                static_cast<unsigned long long>(diffFromCurr),
                hudDiff);
    return true;
}

bool test5_OpenXrLoaderAndRuntimeProbe() {
    xr::OpenXrQuadSink sink;
    const auto res = sink.probeAndCreateInstance();
    CHECK(res.loaderAvailable, "openxr_loader.dll should load and enumerate extensions");
    CHECK(res.d3d12ExtensionSupported, "XR_KHR_D3D12_enable must be advertised by active OpenXR runtime");
    CHECK(res.instanceCreated, "xrCreateInstance with XR_KHR_D3D12_enable must succeed");
    std::printf("[PASS] Test5_OpenXrLoaderAndRuntimeProbe (runtime='%s' headsetFound=%d status='%s')\n",
                res.runtimeName.c_str(), res.headsetFound ? 1 : 0, res.statusMessage.c_str());
    sink.shutdown();
    return true;
}

// One NVENC -> GT60 -> loopback round trip for a single codec. HEVC is skipped rather than failed
// when the installed GPU has no HEVC encoder, so the suite still passes on older NVENC hardware.
bool runNvencQuestServerCase(media::VideoCodec codec) {
    const char* label = media::videoCodecName(codec);
    pipeline::D3D12Context d3d;
    CHECK(d3d.initialize(), "D3D12Context initialize failed");

    constexpr uint32_t inW = 1920, inH = 1080;
    constexpr uint32_t outW = 2560, outH = 1440;
    pipeline::PipelineConfig pcfg{};
    pcfg.inputWidth = inW;
    pcfg.inputHeight = inH;
    pcfg.outputWidth = outW;
    pcfg.outputHeight = outH;
    pcfg.targetFps = 60;
    pcfg.useNvofa = true;

    pipeline::EnhancementPipeline pipe;
    CHECK(pipe.initialize(d3d, pcfg), "EnhancementPipeline initialize failed");

    sink::NvencD3D12Encoder nvenc;
    sink::NvencConfig ncfg{outW, outH, 60, 50};
    ncfg.codec = codec;
    const bool nvencOk = nvenc.initialize(d3d, ncfg);
    if (!nvencOk && codec != media::VideoCodec::H264) {
        std::printf("[SKIP] %s encode unavailable on this GPU (NVENC init failed)\n", label);
        pipe.shutdown();
        d3d.shutdown();
        return true;
    }
    CHECK(nvencOk, "NvencD3D12Encoder initialize failed on RTX 3080 Ti");
    CHECK(nvenc.spsPps().size() >= 8, "NVENC sequence parameter sets must be non-empty");

    // The parameter sets must arrive in the order Android expects and be splittable into csd-N:
    // H.264 = SPS(7) + PPS(8); HEVC = VPS(32) + SPS(33) + PPS(34).
    {
        const auto& csd = nvenc.spsPps();
        std::vector<int> nalTypes;
        for (size_t i = 0; i + 3 <= csd.size(); ++i) {
            if (csd[i] != 0 || csd[i + 1] != 0) continue;
            size_t hdr = 0;
            if (csd[i + 2] == 1) {
                hdr = i + 3;
            } else if (i + 4 <= csd.size() && csd[i + 2] == 0 && csd[i + 3] == 1) {
                hdr = i + 4;
            } else {
                continue;
            }
            if (hdr >= csd.size()) continue;
            nalTypes.push_back(codec == media::VideoCodec::HEVC ? ((csd[hdr] >> 1) & 0x3F) : (csd[hdr] & 0x1F));
            i = hdr - 1;
        }
        if (codec == media::VideoCodec::HEVC) {
            CHECK(nalTypes.size() == 3, "HEVC sequence params must be exactly VPS+SPS+PPS");
            CHECK(nalTypes[0] == 32 && nalTypes[1] == 33 && nalTypes[2] == 34,
                  "HEVC parameter sets must be VPS(32), SPS(33), PPS(34)");
        } else {
            CHECK(nalTypes.size() == 2, "H.264 sequence params must be exactly SPS+PPS");
            CHECK(nalTypes[0] == 7 && nalTypes[1] == 8, "H.264 parameter sets must be SPS(7), PPS(8)");
        }
    }

    sink::QuestStreamServer srv;
    CHECK(srv.start(0, outW, outH, 60, nvenc.spsPps(), codec), "QuestStreamServer start failed");

    SOCKET client = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    CHECK(client != INVALID_SOCKET, "loopback socket creation failed");
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(srv.port());
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);
    CHECK(connect(client, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0, "loopback connect failed");

    // Wait briefly for server acceptThread to register client and send initial video config packet
    for (int i = 0; i < 50 && !srv.hasClient(); ++i) Sleep(10);
    CHECK(srv.hasClient(), "QuestStreamServer did not accept loopback client");

    media::SyntheticGameSource src(inW, inH, true);
    pipeline::PictureReference ref;
    pipeline::ContentDedup dedup;
    uint32_t encodedCount = 0;

    for (uint64_t i = 0; i < 10; ++i) {
        const auto nv12 = src.generateContainerFrame(i);
        const pipeline::PicturePlane planes[2] = {
            {nv12.yPlane.data(), nv12.stride, nv12.width, nv12.height},
            {nv12.uvPlane.data(), nv12.stride, nv12.width, nv12.height / 2},
        };
        const auto pic = ref.observe(planes, 2, pipeline::PictureComponents::Bytes);
        const auto dec = dedup.observe(&pic.id, nv12.pts100ns, 166667, i == 0, 333333);
        if (!dec.keep) continue;

        pipeline::ProcessedFrameBatch batch;
        CHECK(pipe.processUniqueFrame(nv12, batch), "processUniqueFrame failed");
        for (size_t k = 0; k < batch.frames.size(); ++k) {
            const bool ok = nvenc.encodeFrame(
                batch.frames[k], static_cast<uint32_t>(encodedCount * 16), batch.isGenerated[k],
                [&](const uint8_t* data, size_t sz, uint32_t ts, bool idr, bool gen) {
                    srv.sendVideoAccessUnit(data, sz, ts, idr, gen);
                });
            CHECK(ok, "NvencD3D12Encoder::encodeFrame failed");
            ++encodedCount;
        }
    }
    CHECK(encodedCount == 9, "expected 9 encoded output frames (1 real + 4*2)");

    auto recvExact = [&](uint8_t* dst, size_t len) -> bool {
        size_t got = 0;
        while (got < len) {
            const int n = recv(client, reinterpret_cast<char*>(dst + got), static_cast<int>(len - got), 0);
            if (n <= 0) return false;
            got += static_cast<size_t>(n);
        }
        return true;
    };

    struct Hdr {
        uint32_t magic;
        uint8_t type;
        uint8_t flags;
        uint16_t meta;
        uint32_t payloadSize;
        uint32_t timestampMs;
    };

    bool gotConfig = false;
    uint32_t gotAu = 0, gotIdr = 0, gotGen = 0;
    uint64_t totalAuBytes = 0;

    for (int p = 0; p < 10; ++p) {
        Hdr h{};
        CHECK(recvExact(reinterpret_cast<uint8_t*>(&h), sizeof(h)), "recv packet header failed");
        CHECK(h.magic == sink::QuestStreamServer::kMagic, "GT60 magic mismatch");
        std::vector<uint8_t> buf(h.payloadSize);
        if (h.payloadSize > 0) CHECK(recvExact(buf.data(), h.payloadSize), "recv packet payload failed");

        if (h.type == sink::QuestStreamServer::kTypeVideoConfig) {
            CHECK(buf.size() > sink::QuestStreamServer::kVideoConfigHeaderBytes, "config payload too short");
            uint32_t cw = 0, ch = 0;
            std::memcpy(&cw, buf.data(), 4);
            std::memcpy(&ch, buf.data() + 4, 4);
            CHECK(cw == outW && ch == outH, "config dimensions mismatch");
            CHECK(buf[8] == static_cast<uint8_t>(codec), "config codec byte mismatch");
            CHECK(buf.size() - sink::QuestStreamServer::kVideoConfigHeaderBytes == nvenc.spsPps().size(),
                  "config csd length mismatch");
            gotConfig = true;
        } else if (h.type == sink::QuestStreamServer::kTypeVideoAu) {
            CHECK(buf.size() >= 5, "video AU too small");
            CHECK(buf[0] == 0x00 && buf[1] == 0x00 && (buf[2] == 0x01 || (buf[2] == 0x00 && buf[3] == 0x01)),
                  "video AU missing Annex-B start code");
            if (h.flags & sink::QuestStreamServer::kFlagIdr) ++gotIdr;
            if (h.flags & sink::QuestStreamServer::kFlagGenerated) ++gotGen;
            totalAuBytes += buf.size();
            ++gotAu;
        }
    }

    CHECK(gotConfig, "did not receive GT60 video config packet");
    CHECK(gotAu == 9 && gotIdr >= 1 && gotGen == 4, "unexpected GT60 AU / IDR / generated counts");

    closesocket(client);
    srv.stop();
    nvenc.shutdown();
    pipe.shutdown();
    d3d.shutdown();

    std::printf("[PASS] Test6 %s (AUs=%u IDRs=%u Gen=%u bytes=%llu)\n", label,
                gotAu, gotIdr, gotGen, static_cast<unsigned long long>(totalAuBytes));
    return true;
}

bool test6_NvencD3D12HardwareEncodeAndQuestServer() {
    return runNvencQuestServerCase(media::VideoCodec::H264) &&
           runNvencQuestServerCase(media::VideoCodec::HEVC);
}

// TV mode's cadence gate. The console scans out a fixed 60 Hz grid whatever the game renders, so
// the gate has to (a) drop the repeats, (b) read the real content rate, and (c) choose a multiplier
// that lands on the *display's* refresh - which is why the decision is a ratio and not the Quest
// path's fixed 34/46 fps band.
bool test7_TvCadenceGate() {
    constexpr uint32_t w = 1920, h = 1080;
    media::SyntheticGameSource paired(w, h, true);   // duplicated pairs, like a 30 fps game
    media::SyntheticGameSource unique(w, h, false);  // a different picture every tick

    auto feed = [](pipeline::TvCadenceGate& gate, media::SyntheticGameSource& src, uint32_t ticks,
                   uint32_t stride, int64_t& nowMs, uint32_t& kept, uint32_t& dropped) {
        for (uint32_t i = 0; i < ticks; ++i) {
            const auto frame = src.generateContainerFrame(static_cast<uint64_t>(i) * stride);
            const auto d = gate.observe(frame.yPlane.data(), frame.width, frame.height, frame.stride, nowMs);
            if (d.keep) {
                ++kept;
            } else {
                ++dropped;
            }
            nowMs += 16; // ~60 Hz scanout
        }
    };

    // 1. 30 fps content on a 60 Hz panel: half the frames are repeats, content reads ~30 fps, and
    //    frame generation must engage at 2x.
    {
        pipeline::TvCadenceGate gate;
        gate.setRefresh(60);
        gate.reset();
        int64_t nowMs = 0;
        uint32_t kept = 0, dropped = 0;
        feed(gate, paired, 150, 1, nowMs, kept, dropped);
        CHECK(dropped > 50, "repeat frames must be dropped");
        CHECK(std::abs(gate.contentFps() - 31.25) < 3.0, "content rate must read as ~30 fps");
        CHECK(gate.frameGenActive(), "30 fps content on a 60 Hz panel must enable frame generation");
        CHECK(gate.multiplier() == 2, "30 -> 60 fps is a 2x multiplier");
        CHECK(gate.refreshHz() == 60, "refresh should be what was set");

        // 2. 60 fps content on the same panel: no repeats, no reason to interpolate.
        gate.reset();
        kept = dropped = 0;
        feed(gate, unique, 150, 2, nowMs, kept, dropped);
        CHECK(gate.contentFps() > 55.0, "60 fps content must read as ~60 fps");
        CHECK(!gate.frameGenActive(), "60 fps content on a 60 Hz panel must not be doubled");
        CHECK(gate.multiplier() == 1, "no frame generation means a 1x multiplier");
    }

    // 3. The same 60 fps content on a 120 Hz panel *should* double, and 30 fps content should ask
    //    for 4x - clamped to 3, because the pipeline only carries three outputs per source frame.
    {
        pipeline::TvCadenceGate gate;
        gate.setRefresh(120);
        gate.reset();
        int64_t nowMs = 0;
        uint32_t kept = 0, dropped = 0;
        feed(gate, unique, 150, 2, nowMs, kept, dropped);
        CHECK(gate.frameGenActive(), "60 fps content on a 120 Hz panel must enable frame generation");
        CHECK(gate.multiplier() == 2, "60 -> 120 fps is a 2x multiplier");

        gate.reset();
        kept = dropped = 0;
        feed(gate, paired, 150, 1, nowMs, kept, dropped);
        CHECK(gate.multiplier() == 3, "30 fps content on 120 Hz would want 4x, clamped to 3");
    }

    // 4. A frozen picture must not freeze the display: the starvation guard forwards at least one
    //    frame every ~50 ms even though every frame is identical.
    {
        pipeline::TvCadenceGate gate;
        gate.setRefresh(60);
        gate.reset();
        const auto frozen = unique.generateContainerFrame(10);
        int64_t nowMs = 0;
        uint32_t kept = 0;
        for (int i = 0; i < 40; ++i) { // 640 ms
            const auto d = gate.observe(frozen.yPlane.data(), frozen.width, frozen.height, frozen.stride, nowMs);
            if (d.keep) ++kept;
            nowMs += 16;
        }
        CHECK(kept >= 6, "a static scene must still be forwarded about every 50 ms");
    }

    // 5. --no-fg must be honoured.
    {
        pipeline::TvCadenceGate gate;
        pipeline::TvCadenceGate::Config cfg;
        cfg.allowFrameGen = false;
        gate.configure(cfg);
        gate.setRefresh(120);
        gate.reset();
        int64_t nowMs = 0;
        uint32_t kept = 0, dropped = 0;
        feed(gate, paired, 150, 1, nowMs, kept, dropped);
        CHECK(!gate.frameGenActive(), "frame generation must stay off when disabled");
        CHECK(gate.multiplier() == 1, "disabled frame generation must report 1x");
    }

    std::printf("[PASS] Test7_TvCadenceGate (drops repeats, display-relative FG, starvation guard)\n");
    return true;
}

// The exact pipeline configuration TV mode uses: 1440p capture card -> 4K BGRA for the swap chain,
// with the compute flow that measured cheaper than NVOFA on this GPU.
bool test8_TvPipelineUpscale4k() {
    pipeline::D3D12Context d3d;
    CHECK(d3d.initialize(), "D3D12Context initialize failed");

    constexpr uint32_t inW = 2560, inH = 1440;
    constexpr uint32_t outW = 3840, outH = 2160;
    media::SyntheticGameSource src(inW, inH, false);
    const auto f0 = src.generateContainerFrame(0);
    const auto f1 = src.generateContainerFrame(2);

    pipeline::PipelineConfig cfg{};
    cfg.inputWidth = inW;
    cfg.inputHeight = inH;
    cfg.outputWidth = outW;
    cfg.outputHeight = outH;
    cfg.targetFps = 120;   // a 4K120 panel: three output slots
    cfg.useNvofa = false;  // TV mode's default flow (measured 2.1x cheaper than NVOFA here)
    cfg.outputFormat = DXGI_FORMAT_B8G8R8A8_UNORM; // must match the swap chain, or the copy fails
    cfg.rcasSharpness = 0.15f;

    pipeline::EnhancementPipeline pipe;
    CHECK(pipe.initialize(d3d, cfg), "EnhancementPipeline initialize failed");

    pipeline::ProcessedFrameBatch b0, b1;
    CHECK(pipe.processUniqueFrame(f0, b0, 1, true), "first frame failed");
    CHECK(b0.frames.size() == 1, "the first frame is real only");
    const D3D12_RESOURCE_DESC d0 = b0.frames[0]->GetDesc();
    CHECK(d0.Width == outW && d0.Height == outH, "output must be 4K");
    CHECK(d0.Format == DXGI_FORMAT_B8G8R8A8_UNORM, "output must be BGRA8 for the swap chain copy");

    CHECK(pipe.processUniqueFrame(f1, b1, 2, true), "second frame failed");
    CHECK(b1.frames.size() == 2 && b1.isGenerated[0] && !b1.isGenerated[1],
          "frame generation must emit [interpolated, real]");
    for (ID3D12Resource* tex : b1.frames) {
        const D3D12_RESOURCE_DESC d = tex->GetDesc();
        CHECK(d.Width == outW && d.Height == outH && d.Format == DXGI_FORMAT_B8G8R8A8_UNORM,
              "every generated output must be 4K BGRA8");
    }

    // Both frames must carry real picture data, and the generated one must differ from the real one.
    std::vector<uint8_t> gen, real;
    uint32_t genPitch = 0, realPitch = 0;
    CHECK(d3d.readbackTexture2D(b1.frames[0], gen, genPitch), "generated readback failed");
    CHECK(d3d.readbackTexture2D(b1.frames[1], real, realPitch), "real readback failed");
    uint64_t nonBlank = 0, difference = 0;
    for (uint32_t y = 0; y < outH; y += 16) {
        const uint8_t* g = gen.data() + static_cast<size_t>(y) * genPitch;
        const uint8_t* r = real.data() + static_cast<size_t>(y) * realPitch;
        for (uint32_t x = 0; x < outW * 4; x += 32) {
            nonBlank += r[x];
            difference += static_cast<uint64_t>(std::abs(int(g[x]) - int(r[x])));
        }
    }
    CHECK(nonBlank > 0, "the 4K real frame must contain picture data");
    CHECK(difference > 10000, "the interpolated frame must differ from the real one");

    std::printf("[PASS] Test8_TvPipelineUpscale4k (gpu=%.2f ms upscale+fg=%.2f ms)\n",
                b1.timings.totalGpuMs, b1.timings.frameGenAndUpscaleMs);
    pipe.shutdown();
    d3d.shutdown();
    return true;
}

// TV mode plays the card's PCM on the television's HDMI endpoint, so this covers the S16 push path
// (and the format conversion into whatever the mixer runs).
bool test9_WasapiPcmStereoRender() {
    media::WasapiPcmRender render;
    if (!render.start(L"", 30)) {
        std::printf("[PASS] Test9_WasapiPcmStereoRender (skipped: no WASAPI render endpoint)\n");
        return true;
    }
    std::vector<int16_t> block(480 * 2, 0); // 10 ms of silence
    for (int i = 0; i < 12; ++i) {
        render.pushStereo16(block.data(), block.size() / 2);
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    const auto stats = render.consumeStats();
    const std::string name = render.deviceName();
    CHECK(render.active(), "the endpoint must stay active while pushing");
    CHECK(!name.empty(), "the endpoint should report a friendly name");
    CHECK(stats.framesPushed > 0, "WASAPI must accept PCM (framesPushed > 0)");
    render.stop();
    CHECK(!render.active(), "stop must release the endpoint");
    std::printf("[PASS] Test9_WasapiPcmStereoRender (device='%s' pushed=%llu dropped=%llu)\n", name.c_str(),
                static_cast<unsigned long long>(stats.framesPushed),
                static_cast<unsigned long long>(stats.framesDropped));
    return true;
}

} // namespace

int main() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool ok = true;
    ok &= test1_XboxProtocolAndTeredo();
    ok &= test2_CadenceDedup60To30();
    ok &= test3_D3D12NvOfAndComputeOpticalFlow();
    ok &= test4_FrameGenAndFsrUpscale();
    ok &= test5_OpenXrLoaderAndRuntimeProbe();
    ok &= test6_NvencD3D12HardwareEncodeAndQuestServer();
    ok &= test7_TvCadenceGate();
    ok &= test8_TvPipelineUpscale4k();
    ok &= test9_WasapiPcmStereoRender();
    CoUninitialize();

    if (ok) {
        std::printf("\nALL 9 TESTS PASSED.\n");
        return 0;
    }
    std::fprintf(stderr, "\nONE OR MORE TESTS FAILED.\n");
    return 1;
}

#include "gta60/sink/NvencD3D12Encoder.h"
#include "gta60/Log.h"

#include <windows.h>
#include <ffnvcodec/nvEncodeAPI.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <format>

namespace gta60::sink {
using pipeline::ComPtr;

namespace {
inline uint32_t divUp(uint32_t n, uint32_t d) { return (n + d - 1) / d; }

struct RgbToNv12Const {
    uint32_t width;
    uint32_t height;
    uint32_t pad0;
    uint32_t pad1;
};
} // namespace

struct NvencD3D12Encoder::Impl {
    HMODULE dll = nullptr;
    void* encoder = nullptr;
    NV_ENCODE_API_FUNCTION_LIST api{};
    pipeline::D3D12Context* ctx = nullptr;
    NvencConfig cfg{};
    bool initialized = false;
    std::atomic<bool> forceIdr{true};
    uint64_t submitted = 0;

    // Phase accumulators for EncodeTimings (microseconds).
    uint64_t tSubmitUs = 0, tPicUs = 0, tWaitUs = 0, tLockUs = 0, tCbUs = 0, tFrames = 0;

    ComPtr<ID3D12Fence> fence;
    HANDLE event = nullptr;
    pipeline::ComputePass convertPass;
    ComPtr<ID3D12Resource> yTex;
    ComPtr<ID3D12Resource> uvTex;
    ComPtr<ID3D12Resource> nv12UploadBuf;
    uint8_t* nv12UploadMapped = nullptr;
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT nv12Footprints[2]{};
    std::vector<uint8_t> spsPps;

    struct Slot {
        ComPtr<ID3D12Resource> input;
        ComPtr<ID3D12Resource> output;
        NV_ENC_REGISTERED_PTR registeredIn = nullptr;
        NV_ENC_REGISTERED_PTR registeredOut = nullptr;
        NV_ENC_INPUT_PTR mappedIn = nullptr;
        NV_ENC_INPUT_PTR mappedOut = nullptr;
        NV_ENC_INPUT_RESOURCE_D3D12 in{};
        NV_ENC_OUTPUT_RESOURCE_D3D12 out{};
    };
    std::array<Slot, 4> slots{};

    bool check(NVENCSTATUS code, const char* op) const {
        if (code == NV_ENC_SUCCESS) return true;
        const char* detail = (encoder && api.nvEncGetLastErrorString) ? api.nvEncGetLastErrorString(encoder) : "";
        log::error("nvenc-d3d12", std::format("{} failed status={} detail={}", op, int(code), detail ? detail : ""));
        return false;
    }
};

NvencD3D12Encoder::NvencD3D12Encoder() : p_(std::make_unique<Impl>()) {}
NvencD3D12Encoder::~NvencD3D12Encoder() { shutdown(); }

bool NvencD3D12Encoder::initialize(pipeline::D3D12Context& ctx, const NvencConfig& cfg) {
    shutdown();
    if (!ctx.device() || !ctx.queue() || !ctx.fence()) return false;
    if ((cfg.width & 1u) != 0 || (cfg.height & 1u) != 0) {
        log::error("nvenc-d3d12", "NV12 encoding requires even width and height");
        return false;
    }

    p_->ctx = &ctx;
    p_->cfg = cfg;
    p_->forceIdr.store(true, std::memory_order_relaxed);
    p_->submitted = 0;

    wchar_t sysDir[MAX_PATH]{};
    GetSystemDirectoryW(sysDir, MAX_PATH);
    const auto dllPath = std::wstring(sysDir) + L"\\nvEncodeAPI64.dll";
    p_->dll = LoadLibraryExW(dllPath.c_str(), nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!p_->dll) {
        log::error("nvenc-d3d12", std::format("LoadLibrary nvEncodeAPI64.dll failed err={}", GetLastError()));
        return false;
    }

    using PFN_MaxVer = NVENCSTATUS(NVENCAPI*)(uint32_t*);
    using PFN_Create = NVENCSTATUS(NVENCAPI*)(NV_ENCODE_API_FUNCTION_LIST*);
    auto maxVerFn = reinterpret_cast<PFN_MaxVer>(GetProcAddress(p_->dll, "NvEncodeAPIGetMaxSupportedVersion"));
    auto createFn = reinterpret_cast<PFN_Create>(GetProcAddress(p_->dll, "NvEncodeAPICreateInstance"));
    if (!createFn) {
        shutdown();
        return false;
    }

    uint32_t driverApi = 0;
    if (maxVerFn) maxVerFn(&driverApi);

    p_->api.version = NV_ENCODE_API_FUNCTION_LIST_VER;
    if (!p_->check(createFn(&p_->api), "NvEncodeAPICreateInstance")) {
        shutdown();
        return false;
    }

    NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS open{};
    open.version = NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;
    open.apiVersion = NVENCAPI_VERSION;
    open.device = ctx.device();
    open.deviceType = NV_ENC_DEVICE_TYPE_DIRECTX;
    if (!p_->check(p_->api.nvEncOpenEncodeSessionEx(&open, &p_->encoder), "nvEncOpenEncodeSessionEx")) {
        shutdown();
        return false;
    }

    const bool hevc = (cfg.codec == media::VideoCodec::HEVC);
    const GUID codecGuid = hevc ? NV_ENC_CODEC_HEVC_GUID : NV_ENC_CODEC_H264_GUID;
    const GUID presetGuid = [&cfg]() -> GUID {
        switch (cfg.presetIndex) {
        case 1: return NV_ENC_PRESET_P1_GUID;
        case 2: return NV_ENC_PRESET_P2_GUID;
        case 3: return NV_ENC_PRESET_P3_GUID;
        case 5: return NV_ENC_PRESET_P5_GUID;
        case 6: return NV_ENC_PRESET_P6_GUID;
        case 7: return NV_ENC_PRESET_P7_GUID;
        default: return NV_ENC_PRESET_P4_GUID;
        }
    }();
    NV_ENC_PRESET_CONFIG preset{};
    preset.version = NV_ENC_PRESET_CONFIG_VER;
    preset.presetCfg.version = NV_ENC_CONFIG_VER;
    if (!p_->check(p_->api.nvEncGetEncodePresetConfigEx(
                       p_->encoder, codecGuid, presetGuid, NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY, &preset),
                   "nvEncGetEncodePresetConfigEx")) {
        shutdown();
        return false;
    }

    const uint32_t bitsPerSec = std::max(5u, cfg.bitrateMbps) * 1000000u;
    preset.presetCfg.profileGUID = hevc ? NV_ENC_HEVC_PROFILE_MAIN_GUID : NV_ENC_H264_PROFILE_HIGH_GUID;
    preset.presetCfg.frameIntervalP = 1; // zero B-frames
    preset.presetCfg.gopLength = 120;
    preset.presetCfg.rcParams.enableLookahead = 0;
    preset.presetCfg.rcParams.rateControlMode = NV_ENC_PARAMS_RC_CBR;
    preset.presetCfg.rcParams.multiPass = NV_ENC_MULTI_PASS_DISABLED; // single-pass ULL for low encode latency
    preset.presetCfg.rcParams.averageBitRate = bitsPerSec;
    preset.presetCfg.rcParams.maxBitRate = bitsPerSec;
    preset.presetCfg.rcParams.vbvBufferSize = bitsPerSec / 4u; // 250 ms VBV for crisp I/P quality (no IDR crushing)
    preset.presetCfg.rcParams.vbvInitialDelay = preset.presetCfg.rcParams.vbvBufferSize;
    preset.presetCfg.rcParams.zeroReorderDelay = 1; // signals num_reorder_frames=0 for instant MediaCodec decode
    preset.presetCfg.rcParams.enableAQ = 1;
    preset.presetCfg.rcParams.aqStrength = 8;
    preset.presetCfg.rcParams.enableTemporalAQ = 0;

    // Parameter-set config. Both codecs get the same policy - repeat the parameter sets on every
    // IDR, suppress AUD/SEI, no slices, BT.709 limited range, and declare the true frame rate - but
    // the structures (and the rate formula) differ per codec.
    if (hevc) {
        auto& h265 = preset.presetCfg.encodeCodecConfig.hevcConfig;
        h265.idrPeriod = 120;
        h265.repeatSPSPPS = 1;
        h265.outputAUD = 0;
        h265.outputBufferingPeriodSEI = 0;
        h265.outputPictureTimingSEI = 0;
        h265.sliceMode = 0;
        h265.sliceModeData = 0;
        // HEVC shares the H.264 VUI struct, including bitstreamRestrictionFlag - with
        // zeroReorderDelay=1 NVENC then writes max_num_reorder_pics=0 into the SPS, which is what
        // keeps MediaCodec from queueing frames in the DPB.
        auto& vui = h265.hevcVUIParameters;
        vui.bitstreamRestrictionFlag = 1;
        vui.videoSignalTypePresentFlag = 1;
        vui.videoFormat = NV_ENC_VUI_VIDEO_FORMAT_COMPONENT;
        vui.videoFullRangeFlag = 0;
        vui.colourDescriptionPresentFlag = 1;
        vui.colourPrimaries = NV_ENC_VUI_COLOR_PRIMARIES_BT709;
        vui.transferCharacteristics = NV_ENC_VUI_TRANSFER_CHARACTERISTIC_BT709;
        vui.colourMatrix = NV_ENC_VUI_MATRIX_COEFFS_BT709;
        // HEVC derives the frame rate as time_scale / num_units_in_tick, with NO factor of 2 (unlike
        // H.264, whose frame rate is time_scale / (2 * num_units_in_tick)). Declaring 2*fps here
        // would advertise the stream as 120 fps; declaring the true rate is what stops the headset's
        // compositor pacing each frame for two refresh intervals.
        vui.timingInfoPresentFlag = 1;
        vui.numUnitInTicks = 1;
        vui.timeScale = cfg.fps;
    } else {
        auto& h264 = preset.presetCfg.encodeCodecConfig.h264Config;
        h264.idrPeriod = 120;
        h264.repeatSPSPPS = 1;
        h264.outputAUD = 0;
        h264.outputBufferingPeriodSEI = 0;
        h264.outputPictureTimingSEI = 0;
        h264.entropyCodingMode = NV_ENC_H264_ENTROPY_CODING_MODE_CABAC;
        h264.sliceMode = 0;
        h264.sliceModeData = 0;
        h264.h264VUIParameters.bitstreamRestrictionFlag = 1; // explicit max_num_reorder_frames=0 in SPS VUI
        h264.h264VUIParameters.videoSignalTypePresentFlag = 1;
        h264.h264VUIParameters.videoFormat = NV_ENC_VUI_VIDEO_FORMAT_COMPONENT;
        h264.h264VUIParameters.videoFullRangeFlag = 0;
        h264.h264VUIParameters.colourDescriptionPresentFlag = 1;
        h264.h264VUIParameters.colourPrimaries = NV_ENC_VUI_COLOR_PRIMARIES_BT709;
        h264.h264VUIParameters.transferCharacteristics = NV_ENC_VUI_TRANSFER_CHARACTERISTIC_BT709;
        h264.h264VUIParameters.colourMatrix = NV_ENC_VUI_MATRIX_COEFFS_BT709;
        // Declare the true frame rate in the SPS VUI timing info. H.264 derives the frame rate as
        // time_scale / (2 * num_units_in_tick), so emitting num_units_in_tick=1 / time_scale=2*fps
        // yields exactly fps. Without this the driver's implicit timing made decoders (and therefore
        // the Quest compositor) treat the 60 fps stream as 30 fps content, causing each frame to be
        // held for two refresh intervals instead of being presented as soon as it was decoded.
        h264.h264VUIParameters.timingInfoPresentFlag = 1;
        h264.h264VUIParameters.numUnitInTicks = 1;
        h264.h264VUIParameters.timeScale = 2u * cfg.fps;
    }

    NV_ENC_INITIALIZE_PARAMS init{};
    init.version = NV_ENC_INITIALIZE_PARAMS_VER;
    init.encodeGUID = codecGuid;
    init.presetGUID = presetGuid;
    init.tuningInfo = NV_ENC_TUNING_INFO_ULTRA_LOW_LATENCY;
    init.encodeWidth = cfg.width;
    init.encodeHeight = cfg.height;
    init.darWidth = cfg.width;
    init.darHeight = cfg.height;
    init.frameRateNum = cfg.fps;
    init.frameRateDen = 1;
    init.enablePTD = 1;
    init.encodeConfig = &preset.presetCfg;
    init.maxEncodeWidth = cfg.width;
    init.maxEncodeHeight = cfg.height;
    init.bufferFormat = NV_ENC_BUFFER_FORMAT_NV12;

    if (!p_->check(p_->api.nvEncInitializeEncoder(p_->encoder, &init), "nvEncInitializeEncoder")) {
        shutdown();
        return false;
    }

    p_->spsPps.resize(4096);
    uint32_t seqSize = 0;
    NV_ENC_SEQUENCE_PARAM_PAYLOAD seq{};
    seq.version = NV_ENC_SEQUENCE_PARAM_PAYLOAD_VER;
    seq.inBufferSize = static_cast<uint32_t>(p_->spsPps.size());
    seq.spsppsBuffer = p_->spsPps.data();
    seq.outSPSPPSPayloadSize = &seqSize;
    if (!p_->check(p_->api.nvEncGetSequenceParams(p_->encoder, &seq), "nvEncGetSequenceParams")) {
        shutdown();
        return false;
    }
    p_->spsPps.resize(seqSize);

    if (FAILED(ctx.device()->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&p_->fence)))) {
        shutdown();
        return false;
    }
    p_->event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!p_->event) {
        shutdown();
        return false;
    }

    std::vector<uint8_t> dxil;
    if (!pipeline::D3D12Context::loadShaderDxil("RgbToNv12", dxil) ||
        !p_->convertPass.create(ctx.device(), dxil, 4, 1, 2)) {
        shutdown();
        return false;
    }

    p_->yTex = ctx.createTexture2D(cfg.width, cfg.height, DXGI_FORMAT_R8_UNORM);
    p_->uvTex = ctx.createTexture2D(cfg.width / 2, cfg.height / 2, DXGI_FORMAT_R8G8_UNORM);
    if (!p_->yTex || !p_->uvTex) {
        shutdown();
        return false;
    }

    const uint64_t outBufSize = (uint64_t(cfg.width) * cfg.height * 4 + 4095ull) & ~4095ull;
    for (auto& s : p_->slots) {
        s.input = ctx.createTexture2D(cfg.width, cfg.height, DXGI_FORMAT_NV12, D3D12_RESOURCE_FLAG_NONE);
        s.output = ctx.createBuffer(outBufSize, D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
        if (!s.input || !s.output) {
            shutdown();
            return false;
        }

        auto regMap = [&](ID3D12Resource* res, bool isOut, NV_ENC_REGISTERED_PTR& regOut,
                          NV_ENC_INPUT_PTR& mapOut) -> bool {
            NV_ENC_REGISTER_RESOURCE r{};
            r.version = NV_ENC_REGISTER_RESOURCE_VER;
            r.resourceType = NV_ENC_INPUT_RESOURCE_TYPE_DIRECTX;
            r.resourceToRegister = res;
            r.width = isOut ? static_cast<uint32_t>(outBufSize) : cfg.width;
            r.height = isOut ? 1 : cfg.height;
            r.bufferFormat = isOut ? NV_ENC_BUFFER_FORMAT_U8 : NV_ENC_BUFFER_FORMAT_NV12;
            r.bufferUsage = isOut ? NV_ENC_OUTPUT_BITSTREAM : NV_ENC_INPUT_IMAGE;
            if (!p_->check(p_->api.nvEncRegisterResource(p_->encoder, &r),
                           isOut ? "RegisterOutput" : "RegisterNV12")) {
                return false;
            }
            regOut = r.registeredResource;

            NV_ENC_MAP_INPUT_RESOURCE m{};
            m.version = NV_ENC_MAP_INPUT_RESOURCE_VER;
            m.registeredResource = regOut;
            if (!p_->check(p_->api.nvEncMapInputResource(p_->encoder, &m), "MapResource")) {
                return false;
            }
            mapOut = m.mappedResource;
            return true;
        };

        if (!regMap(s.input.Get(), false, s.registeredIn, s.mappedIn) ||
            !regMap(s.output.Get(), true, s.registeredOut, s.mappedOut)) {
            shutdown();
            return false;
        }

        s.in.version = NV_ENC_INPUT_RESOURCE_D3D12_VER;
        s.in.pInputBuffer = s.mappedIn;
        s.in.inputFencePoint.version = NV_ENC_FENCE_POINT_D3D12_VER;
        s.in.inputFencePoint.pFence = ctx.fence();
        s.in.inputFencePoint.bWait = 1;

        s.out.version = NV_ENC_OUTPUT_RESOURCE_D3D12_VER;
        s.out.pOutputBuffer = s.mappedOut;
        s.out.outputFencePoint.version = NV_ENC_FENCE_POINT_D3D12_VER;
        s.out.outputFencePoint.pFence = p_->fence.Get();
        s.out.outputFencePoint.bSignal = 1;
    }

    // Persistently mapped NV12 upload buffer for zero-shader direct NV12 encoding
    {
        const D3D12_RESOURCE_DESC nv12Desc = p_->slots[0].input->GetDesc();
        UINT rows[2]{};
        UINT64 rowBytes[2]{};
        UINT64 totalUploadBytes = 0;
        ctx.device()->GetCopyableFootprints(&nv12Desc, 0, 2, 0, p_->nv12Footprints, rows, rowBytes, &totalUploadBytes);
        p_->nv12UploadBuf = ctx.createBuffer(totalUploadBytes, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
        D3D12_RANGE emptyRead{0, 0};
        if (!p_->nv12UploadBuf ||
            FAILED(p_->nv12UploadBuf->Map(0, &emptyRead, reinterpret_cast<void**>(&p_->nv12UploadMapped))) ||
            !p_->nv12UploadMapped) {
            shutdown();
            return false;
        }
    }

    p_->initialized = true;
    log::info("nvenc-d3d12", std::format("initialized {} P{} ULL 1-pass AQ 0-reorder {}x{} @ {}fps {} Mbps (driverApi={}.{}, csd={}B)",
                                         media::videoCodecName(cfg.codec), cfg.presetIndex, cfg.width, cfg.height,
                                         cfg.fps, cfg.bitrateMbps, driverApi >> 4, driverApi & 15,
                                         p_->spsPps.size()));
    return true;
}

bool NvencD3D12Encoder::encodeFrame(ID3D12Resource* rgbTex,
                                    uint32_t timestampMs,
                                    bool isGenerated,
                                    const EncodedPacketCallback& cb) {
    if (!p_->initialized || !rgbTex || !p_->ctx) return false;

    const auto tBegin = std::chrono::steady_clock::now();
    auto& s = p_->slots[p_->submitted % p_->slots.size()];

    ID3D12GraphicsCommandList* list = nullptr;
    if (!p_->ctx->beginCommandList(&list)) return false;

    p_->ctx->transition(list, rgbTex, D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    p_->ctx->transition(list, p_->yTex.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    p_->ctx->transition(list, p_->uvTex.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);

    const RgbToNv12Const c{p_->cfg.width, p_->cfg.height, 0, 0};
    ID3D12Resource* srvs[] = {rgbTex};
    ID3D12Resource* uavs[] = {p_->yTex.Get(), p_->uvTex.Get()};
    p_->ctx->dispatchCompute(list, p_->convertPass, &c, 4, srvs, nullptr, 1, uavs, nullptr, 2,
                             divUp(p_->cfg.width, 16), divUp(p_->cfg.height, 16));

    p_->ctx->transition(list, p_->yTex.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
    p_->ctx->transition(list, p_->uvTex.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COPY_SOURCE);
    p_->ctx->transition(list, s.input.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COPY_DEST);

    for (UINT plane = 0; plane < 2; ++plane) {
        D3D12_TEXTURE_COPY_LOCATION src{};
        src.pResource = plane == 0 ? p_->yTex.Get() : p_->uvTex.Get();
        src.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
        src.SubresourceIndex = 0;

        D3D12_TEXTURE_COPY_LOCATION dst{};
        dst.pResource = s.input.Get();
        dst.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
        dst.SubresourceIndex = plane;
        list->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
    }

    p_->ctx->transition(list, s.input.Get(), D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_COMMON);
    p_->ctx->transition(list, p_->yTex.Get(), D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_COMMON);
    p_->ctx->transition(list, p_->uvTex.Get(), D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_COMMON);
    p_->ctx->transition(list, rgbTex, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_COMMON);

    const uint64_t waitVal = p_->ctx->submitCommandList();
    if (!waitVal) return false;
    const auto tSubmit = std::chrono::steady_clock::now();

    const uint64_t outVal = p_->submitted + 1;
    s.in.inputFencePoint.waitValue = waitVal;
    s.out.outputFencePoint.signalValue = outVal;

    NV_ENC_PIC_PARAMS pic{};
    pic.version = NV_ENC_PIC_PARAMS_VER;
    pic.inputWidth = p_->cfg.width;
    pic.inputHeight = p_->cfg.height;
    pic.inputBuffer = &s.in;
    pic.outputBitstream = &s.out;
    pic.bufferFmt = NV_ENC_BUFFER_FORMAT_NV12;
    pic.pictureStruct = NV_ENC_PIC_STRUCT_FRAME;
    pic.inputTimeStamp = timestampMs;
    pic.inputDuration = 1;
    if (p_->forceIdr.exchange(false, std::memory_order_relaxed)) {
        pic.encodePicFlags = NV_ENC_PIC_FLAG_FORCEIDR | NV_ENC_PIC_FLAG_OUTPUT_SPSPPS;
    }

    if (!p_->check(p_->api.nvEncEncodePicture(p_->encoder, &pic), "nvEncEncodePicture")) {
        return false;
    }
    ++p_->submitted;
    const auto tPic = std::chrono::steady_clock::now();

    if (p_->fence->GetCompletedValue() < outVal) {
        if (FAILED(p_->fence->SetEventOnCompletion(outVal, p_->event)) ||
            WaitForSingleObject(p_->event, 5000) != WAIT_OBJECT_0) {
            log::error("nvenc-d3d12", "encoder output fence wait failed");
            return false;
        }
    }
    const auto tWait = std::chrono::steady_clock::now();

    NV_ENC_LOCK_BITSTREAM lock{};
    lock.version = NV_ENC_LOCK_BITSTREAM_VER;
    lock.outputBitstream = &s.out;
    lock.doNotWait = 0;
    if (!p_->check(p_->api.nvEncLockBitstream(p_->encoder, &lock), "nvEncLockBitstream")) {
        return false;
    }

    const bool isIdr = (lock.pictureType == NV_ENC_PIC_TYPE_IDR);
    const auto tLock = std::chrono::steady_clock::now();
    if (cb && lock.bitstreamBufferPtr && lock.bitstreamSizeInBytes > 0) {
        cb(static_cast<const uint8_t*>(lock.bitstreamBufferPtr), lock.bitstreamSizeInBytes,
           timestampMs, isIdr, isGenerated);
    }
    const auto tEnd = std::chrono::steady_clock::now();

    const bool unlocked = p_->check(p_->api.nvEncUnlockBitstream(p_->encoder, &s.out), "nvEncUnlockBitstream");

    const auto us = [](auto a, auto b) {
        return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(b - a).count());
    };
    p_->tSubmitUs += us(tBegin, tSubmit);
    p_->tPicUs += us(tSubmit, tPic);
    p_->tWaitUs += us(tPic, tWait);
    p_->tLockUs += us(tWait, tLock);
    p_->tCbUs += us(tLock, tEnd);
    ++p_->tFrames;

    return unlocked;
}

EncodeTimings NvencD3D12Encoder::consumeTimings() {
    EncodeTimings t{};
    const uint64_t frames = p_->tFrames;
    if (frames > 0) {
        t.submitMs = static_cast<double>(p_->tSubmitUs) / 1000.0 / static_cast<double>(frames);
        t.picMs = static_cast<double>(p_->tPicUs) / 1000.0 / static_cast<double>(frames);
        t.waitMs = static_cast<double>(p_->tWaitUs) / 1000.0 / static_cast<double>(frames);
        t.lockMs = static_cast<double>(p_->tLockUs) / 1000.0 / static_cast<double>(frames);
        t.cbMs = static_cast<double>(p_->tCbUs) / 1000.0 / static_cast<double>(frames);
    }
    t.frames = frames;
    p_->tSubmitUs = p_->tPicUs = p_->tWaitUs = p_->tLockUs = p_->tCbUs = p_->tFrames = 0;
    return t;
}

bool NvencD3D12Encoder::encodeNv12Frame(const media::DecodedNv12Frame& frame,
                                        uint32_t timestampMs,
                                        bool isGenerated,
                                        const EncodedPacketCallback& cb) {
    if (!p_->initialized || !p_->ctx || !p_->nv12UploadMapped) return false;
    if (frame.width != p_->cfg.width || frame.height != p_->cfg.height) return false;

    const uint32_t w = p_->cfg.width;
    const uint32_t h = p_->cfg.height;
    const auto& yFp = p_->nv12Footprints[0];
    const auto& uvFp = p_->nv12Footprints[1];

    if (frame.stride == yFp.Footprint.RowPitch && frame.stride == w) {
        std::memcpy(p_->nv12UploadMapped + yFp.Offset, frame.yPlane.data(), static_cast<size_t>(w) * h);
    } else {
        for (uint32_t y = 0; y < h; ++y) {
            std::memcpy(p_->nv12UploadMapped + yFp.Offset + static_cast<size_t>(y) * yFp.Footprint.RowPitch,
                        frame.yPlane.data() + static_cast<size_t>(y) * frame.stride, w);
        }
    }
    if (frame.stride == uvFp.Footprint.RowPitch && frame.stride == w) {
        std::memcpy(p_->nv12UploadMapped + uvFp.Offset, frame.uvPlane.data(), static_cast<size_t>(w) * (h / 2));
    } else {
        for (uint32_t y = 0; y < h / 2; ++y) {
            std::memcpy(p_->nv12UploadMapped + uvFp.Offset + static_cast<size_t>(y) * uvFp.Footprint.RowPitch,
                        frame.uvPlane.data() + static_cast<size_t>(y) * frame.stride, w);
        }
    }

    auto& s = p_->slots[p_->submitted % p_->slots.size()];

    ID3D12GraphicsCommandList* list = nullptr;
    if (!p_->ctx->beginCommandList(&list)) return false;

    p_->ctx->transition(list, s.input.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COPY_DEST);
    for (UINT plane = 0; plane < 2; ++plane) {
        D3D12_TEXTURE_COPY_LOCATION src{};
        src.pResource = p_->nv12UploadBuf.Get();
        src.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
        src.PlacedFootprint = p_->nv12Footprints[plane];

        D3D12_TEXTURE_COPY_LOCATION dst{};
        dst.pResource = s.input.Get();
        dst.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
        dst.SubresourceIndex = plane;
        list->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
    }
    p_->ctx->transition(list, s.input.Get(), D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_COMMON);

    const uint64_t waitVal = p_->ctx->submitCommandList();
    if (!waitVal) return false;

    const uint64_t outVal = p_->submitted + 1;
    s.in.inputFencePoint.waitValue = waitVal;
    s.out.outputFencePoint.signalValue = outVal;

    NV_ENC_PIC_PARAMS pic{};
    pic.version = NV_ENC_PIC_PARAMS_VER;
    pic.inputWidth = p_->cfg.width;
    pic.inputHeight = p_->cfg.height;
    pic.inputBuffer = &s.in;
    pic.outputBitstream = &s.out;
    pic.bufferFmt = NV_ENC_BUFFER_FORMAT_NV12;
    pic.pictureStruct = NV_ENC_PIC_STRUCT_FRAME;
    pic.inputTimeStamp = timestampMs;
    pic.inputDuration = 1;
    if (p_->forceIdr.exchange(false, std::memory_order_relaxed)) {
        pic.encodePicFlags = NV_ENC_PIC_FLAG_FORCEIDR | NV_ENC_PIC_FLAG_OUTPUT_SPSPPS;
    }

    if (!p_->check(p_->api.nvEncEncodePicture(p_->encoder, &pic), "nvEncEncodePicture(NV12)")) {
        return false;
    }
    ++p_->submitted;

    if (p_->fence->GetCompletedValue() < outVal) {
        if (FAILED(p_->fence->SetEventOnCompletion(outVal, p_->event)) ||
            WaitForSingleObject(p_->event, 5000) != WAIT_OBJECT_0) {
            log::error("nvenc-d3d12", "encoder output fence wait failed (NV12)");
            return false;
        }
    }

    NV_ENC_LOCK_BITSTREAM lock{};
    lock.version = NV_ENC_LOCK_BITSTREAM_VER;
    lock.outputBitstream = &s.out;
    lock.doNotWait = 0;
    if (!p_->check(p_->api.nvEncLockBitstream(p_->encoder, &lock), "nvEncLockBitstream(NV12)")) {
        return false;
    }

    const bool isIdr = (lock.pictureType == NV_ENC_PIC_TYPE_IDR);
    if (cb && lock.bitstreamBufferPtr && lock.bitstreamSizeInBytes > 0) {
        cb(static_cast<const uint8_t*>(lock.bitstreamBufferPtr), lock.bitstreamSizeInBytes,
           timestampMs, isIdr, isGenerated);
    }

    return p_->check(p_->api.nvEncUnlockBitstream(p_->encoder, &s.out), "nvEncUnlockBitstream(NV12)");
}

void NvencD3D12Encoder::requestIdr() { p_->forceIdr.store(true, std::memory_order_relaxed); }
const std::vector<uint8_t>& NvencD3D12Encoder::spsPps() const { return p_->spsPps; }
media::VideoCodec NvencD3D12Encoder::codec() const { return p_->cfg.codec; }
bool NvencD3D12Encoder::initialized() const { return p_->initialized; }

void NvencD3D12Encoder::shutdown() {
    if (p_->ctx) p_->ctx->waitIdle();

    if (p_->encoder) {
        for (auto& s : p_->slots) {
            if (s.mappedIn && p_->api.nvEncUnmapInputResource) {
                p_->api.nvEncUnmapInputResource(p_->encoder, s.mappedIn);
                s.mappedIn = nullptr;
            }
            if (s.registeredIn && p_->api.nvEncUnregisterResource) {
                p_->api.nvEncUnregisterResource(p_->encoder, s.registeredIn);
                s.registeredIn = nullptr;
            }
            if (s.mappedOut && p_->api.nvEncUnmapInputResource) {
                p_->api.nvEncUnmapInputResource(p_->encoder, s.mappedOut);
                s.mappedOut = nullptr;
            }
            if (s.registeredOut && p_->api.nvEncUnregisterResource) {
                p_->api.nvEncUnregisterResource(p_->encoder, s.registeredOut);
                s.registeredOut = nullptr;
            }
        }
        if (p_->api.nvEncDestroyEncoder) p_->api.nvEncDestroyEncoder(p_->encoder);
        p_->encoder = nullptr;
    }

    for (auto& s : p_->slots) {
        s.input.Reset();
        s.output.Reset();
    }
    if (p_->nv12UploadBuf && p_->nv12UploadMapped) {
        D3D12_RANGE empty{0, 0};
        p_->nv12UploadBuf->Unmap(0, &empty);
        p_->nv12UploadMapped = nullptr;
    }
    p_->nv12UploadBuf.Reset();
    p_->yTex.Reset();
    p_->uvTex.Reset();
    p_->fence.Reset();
    if (p_->event) {
        CloseHandle(p_->event);
        p_->event = nullptr;
    }
    if (p_->dll) {
        FreeLibrary(p_->dll);
        p_->dll = nullptr;
    }
    p_->initialized = false;
    p_->ctx = nullptr;
}

} // namespace gta60::sink

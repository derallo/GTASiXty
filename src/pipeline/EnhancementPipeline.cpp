#include "gta60/pipeline/EnhancementPipeline.h"
#include "gta60/Log.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <format>

namespace gta60::pipeline {
namespace {

inline uint32_t divUp(uint32_t n, uint32_t d) { return (n + d - 1) / d; }

struct Nv12Const {
    uint32_t w, h, p0, p1;
};
struct DisConst {
    uint32_t w, h, gw, gh, gs, rad, p0, p1;
};
struct RefineConst {
    uint32_t gw, gh, fw, fh, cw, ch, gs;
    float tieBias;
};
struct FillConst {
    uint32_t fw, fh, cw, ch;
    float costLimit, disagreeLimit, distWeight, p0;
};
struct FrameGenConst {
    uint32_t w, h, fw, fh, gw, gh, gs;
    float phase;
    float hud0[4];
    float hud1[4];
    uint32_t hudEnable;
    float staticThresh;
    float p0, p1;
};
struct EasuConst {
    uint32_t sw, sh, dw, dh;
};
struct RcasConst {
    uint32_t w, h;
    float sharpness;
    float p0;
};

} // namespace

EnhancementPipeline::~EnhancementPipeline() { shutdown(); }

bool EnhancementPipeline::initialize(D3D12Context& ctx, const PipelineConfig& cfg) {
    shutdown();
    ctx_ = &ctx;
    cfg_ = cfg;
    uniqueFrameCount_ = 0;
    currSlot_ = 1;
    lastUploadFence_ = 0;

    auto* dev = ctx.device();
    if (!dev) return false;

    const uint32_t inW = cfg.inputWidth;
    const uint32_t inH = cfg.inputHeight;
    const uint32_t outW = cfg.outputWidth;
    const uint32_t outH = cfg.outputHeight;

    gridW_ = divUp(inW, kGridSize);
    gridH_ = divUp(inH, kGridSize);
    // Run motion refinement at half resolution for ultra-fast RTX 3080 Ti latency (~0.2 ms)
    flowW_ = std::max(1u, inW / 2);
    flowH_ = std::max(1u, inH / 2);

    // Load and compile compute PSOs
    auto loadPass = [&](ComputePass& pass, const char* name, UINT c32, UINT srvs, UINT uavs) -> bool {
        std::vector<uint8_t> dxil;
        if (!D3D12Context::loadShaderDxil(name, dxil)) return false;
        return pass.create(dev, dxil, c32, srvs, uavs);
    };

    if (!loadPass(passNv12ToRgb_, "Nv12ToRgb", 4, 2, 1) ||
        !loadPass(passLumaExtract_, "LumaExtract", 4, 1, 1) ||
        !loadPass(passDisFlow_, "DisOpticalFlow", 8, 2, 4) ||
        !loadPass(passFlowRefine_, "FlowRefine", 8, 4, 1) ||
        !loadPass(passFlowFill_, "FlowFill", 8, 2, 1) ||
        !loadPass(passFrameGen_, "FrameGenWarp", 20, 5, 1) ||
        !loadPass(passEasu_, "EasuUpscale", 4, 1, 1) ||
        !loadPass(passRcas_, "RcasSharpen", 4, 1, 1)) {
        shutdown();
        return false;
    }

    yTex_ = ctx.createTexture2D(inW, inH, DXGI_FORMAT_R8_UNORM, D3D12_RESOURCE_FLAG_NONE);
    uvTex_ = ctx.createTexture2D((inW + 1) / 2, (inH + 1) / 2, DXGI_FORMAT_R8G8_UNORM, D3D12_RESOURCE_FLAG_NONE);
    if (!yTex_ || !uvTex_) {
        shutdown();
        return false;
    }

    const D3D12_RESOURCE_DESC yDesc = yTex_->GetDesc();
    const D3D12_RESOURCE_DESC uvDesc = uvTex_->GetDesc();
    UINT64 yBytes = 0, uvBytes = 0;
    UINT rows = 0;
    UINT64 rowBytes = 0;
    dev->GetCopyableFootprints(&yDesc, 0, 1, 0, &yFootprint_, &rows, &rowBytes, &yBytes);
    const UINT64 uvOffset = (yBytes + D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT - 1) &
                            ~(UINT64(D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT) - 1);
    dev->GetCopyableFootprints(&uvDesc, 0, 1, uvOffset, &uvFootprint_, &rows, &rowBytes, &uvBytes);
    uploadSize_ = uvOffset + uvBytes;
    nv12UploadBuf_ = ctx.createBuffer(uploadSize_, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
    if (!nv12UploadBuf_) {
        shutdown();
        return false;
    }
    D3D12_RANGE emptyRead{0, 0};
    if (FAILED(nv12UploadBuf_->Map(0, &emptyRead, reinterpret_cast<void**>(&nv12UploadMapped_))) || !nv12UploadMapped_) {
        shutdown();
        return false;
    }

    // Working textures: B8G8R8A8_UNORM for NVOFA compatibility
    for (int i = 0; i < 2; ++i) {
        colorSlots_[i] = ctx.createTexture2D(inW, inH, DXGI_FORMAT_B8G8R8A8_UNORM);
        lumaSlots_[i] = ctx.createTexture2D(inW, inH, DXGI_FORMAT_R8_UNORM);
        if (!colorSlots_[i] || !lumaSlots_[i]) {
            shutdown();
            return false;
        }
    }

    flowFwdRaw_ = ctx.createTexture2D(gridW_, gridH_, DXGI_FORMAT_R16G16_SINT);
    costFwdRaw_ = ctx.createTexture2D(gridW_, gridH_, DXGI_FORMAT_R8_UINT);
    flowRevRaw_ = ctx.createTexture2D(gridW_, gridH_, DXGI_FORMAT_R16G16_SINT);
    costRevRaw_ = ctx.createTexture2D(gridW_, gridH_, DXGI_FORMAT_R8_UINT);
    refinedTex_ = ctx.createTexture2D(flowW_, flowH_, DXGI_FORMAT_R16G16B16A16_FLOAT);
    filledFlowTex_ = ctx.createTexture2D(flowW_, flowH_, DXGI_FORMAT_R16G16_FLOAT);

    interpTex_ = ctx.createTexture2D(inW, inH, DXGI_FORMAT_R8G8B8A8_UNORM);
    upscaleTmpTex_ = ctx.createTexture2D(outW, outH, DXGI_FORMAT_R8G8B8A8_UNORM);

    const uint32_t multiplier = cfg.targetFps >= 90 ? 3u : 2u;
    outputSlots_.resize(multiplier);
    for (uint32_t i = 0; i < multiplier; ++i) {
        outputSlots_[i] = ctx.createTexture2D(outW, outH, cfg.outputFormat);
        if (!outputSlots_[i]) {
            shutdown();
            return false;
        }
    }

    if (!flowFwdRaw_ || !costFwdRaw_ || !flowRevRaw_ || !costRevRaw_ ||
        !refinedTex_ || !filledFlowTex_ || !interpTex_ || !upscaleTmpTex_) {
        shutdown();
        return false;
    }

    if (FAILED(dev->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&nvofInFence_))) ||
        FAILED(dev->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&nvofOutFence_)))) {
        shutdown();
        return false;
    }

    usingNvof_ = false;
    if (cfg.useNvofa) {
        nvof::NvOfSession::Desc ofDesc{};
        ofDesc.width = inW;
        ofDesc.height = inH;
        ofDesc.inFence = nvofInFence_.Get();
        ofDesc.outFence = nvofOutFence_.Get();
        ofDesc.quality = 0; // Fast preset for ultra-low-latency streaming
        ofDesc.gridSize = kGridSize;
        ofDesc.reverseFlow = flowRevRaw_.Get();
        ofDesc.reverseCost = nullptr; // cost buffers are not consumed by refinement/warp

        if (nvof_.initialize(dev, colorSlots_[0].Get(), colorSlots_[1].Get(),
                             flowFwdRaw_.Get(), nullptr, ofDesc)) {
            usingNvof_ = true;
        } else {
            log::warn("pipeline", "NVOFA unavailable; falling back to D3D12 compute optical flow");
        }
    }

    initialized_ = true;
    log::info("pipeline", std::format("initialized {}x{} -> {}x{} target={}fps flow={} (nvofa={})",
                                      inW, inH, outW, outH, cfg.targetFps,
                                      usingNvof_ ? "NVOFA-HW" : "D3D12-Compute", usingNvof_));
    return true;
}

void EnhancementPipeline::shutdown() {
    if (ctx_) ctx_->waitIdle();

    // Critical teardown order: unregister NVOFA buffers BEFORE releasing textures!
    nvof_.unregisterAll();
    nvofInFence_.Reset();
    nvofOutFence_.Reset();

    outputSlots_.clear();
    upscaleTmpTex_.Reset();
    interpTex_.Reset();
    filledFlowTex_.Reset();
    refinedTex_.Reset();
    costRevRaw_.Reset();
    flowRevRaw_.Reset();
    costFwdRaw_.Reset();
    flowFwdRaw_.Reset();
    for (int i = 0; i < 2; ++i) {
        lumaSlots_[i].Reset();
        colorSlots_[i].Reset();
    }
    if (nv12UploadBuf_ && nv12UploadMapped_) {
        D3D12_RANGE empty{0, 0};
        nv12UploadBuf_->Unmap(0, &empty);
        nv12UploadMapped_ = nullptr;
    }
    nv12UploadBuf_.Reset();
    uvTex_.Reset();
    yTex_.Reset();

    nvof_.shutdown();
    usingNvof_ = false;
    initialized_ = false;
    ctx_ = nullptr;
}

StageTimings EnhancementPipeline::readLastGpuTimings(bool didFrameGen) const {
    StageTimings t{};
    if (!ctx_) return t;
    t.nv12UploadAndConvertMs = ctx_->elapsedMs(0, 1);
    if (didFrameGen) {
        t.opticalFlowMs = ctx_->elapsedMs(1, 2);
        t.flowRefineAndFillMs = ctx_->elapsedMs(2, 3);
        t.frameGenAndUpscaleMs = ctx_->elapsedMs(3, 4);
    } else {
        t.frameGenAndUpscaleMs = ctx_->elapsedMs(1, 4);
    }
    return t;
}

bool EnhancementPipeline::processUniqueFrame(const media::DecodedNv12Frame& frame,
                                             ProcessedFrameBatch& outBatch,
                                             uint32_t frameGenMultiplier,
                                             bool waitForCompletion) {
    if (!initialized_ || !ctx_ || !nv12UploadMapped_) return false;
    if (frame.width != cfg_.inputWidth || frame.height != cfg_.inputHeight) {
        log::error("pipeline", std::format("frame extent {}x{} != configured {}x{}",
                                           frame.width, frame.height, cfg_.inputWidth, cfg_.inputHeight));
        return false;
    }

    outBatch.frames.clear();
    outBatch.isGenerated.clear();
    outBatch.timings = {};
    outBatch.submitFenceValue = 0;
    outBatch.didFrameGen = false;

    const auto wallStart = std::chrono::steady_clock::now();
    const uint32_t inW = cfg_.inputWidth;
    const uint32_t inH = cfg_.inputHeight;
    const uint32_t outW = cfg_.outputWidth;
    const uint32_t outH = cfg_.outputHeight;
    const bool sameSize = (inW == outW && inH == outH);
    const uint32_t gxIn = divUp(inW, 16);
    const uint32_t gyIn = divUp(inH, 16);
    const uint32_t gxFlow = divUp(flowW_, 16);
    const uint32_t gyFlow = divUp(flowH_, 16);
    const uint32_t gxGrid = divUp(gridW_, 16);
    const uint32_t gyGrid = divUp(gridH_, 16);
    const uint32_t gxOut = divUp(outW, 16);
    const uint32_t gyOut = divUp(outH, 16);

    // Ensure previous upload copy has completed before overwriting the mapped upload buffer
    if (lastUploadFence_ > 0 && ctx_->fence()->GetCompletedValue() < lastUploadFence_) {
        if (!ctx_->waitForFenceValue(ctx_->fence(), lastUploadFence_)) return false;
    }

    // 1. Copy NV12 planes into persistently mapped upload buffer (fast bulk copy when pitch matches)
    if (frame.stride == yFootprint_.Footprint.RowPitch && frame.stride == inW) {
        std::memcpy(nv12UploadMapped_ + yFootprint_.Offset, frame.yPlane.data(), static_cast<size_t>(inW) * inH);
    } else {
        for (uint32_t y = 0; y < inH; ++y) {
            std::memcpy(nv12UploadMapped_ + yFootprint_.Offset + static_cast<size_t>(y) * yFootprint_.Footprint.RowPitch,
                        frame.yPlane.data() + static_cast<size_t>(y) * frame.stride, inW);
        }
    }
    if (frame.stride == uvFootprint_.Footprint.RowPitch && frame.stride == inW) {
        std::memcpy(nv12UploadMapped_ + uvFootprint_.Offset, frame.uvPlane.data(), static_cast<size_t>(inW) * (inH / 2));
    } else {
        for (uint32_t y = 0; y < inH / 2; ++y) {
            std::memcpy(nv12UploadMapped_ + uvFootprint_.Offset + static_cast<size_t>(y) * uvFootprint_.Footprint.RowPitch,
                        frame.uvPlane.data() + static_cast<size_t>(y) * frame.stride, inW);
        }
    }

    // Ping-pong colorSlots_ so we NEVER copy 14.7 MB of BGRA8 on the GPU!
    const int prevSlot = currSlot_;
    const int nextCurr = (uniqueFrameCount_ == 0) ? 1 : (1 - currSlot_);
    ID3D12Resource* prevColor = colorSlots_[prevSlot].Get();
    ID3D12Resource* currColor = colorSlots_[nextCurr].Get();
    ID3D12Resource* prevLuma = lumaSlots_[prevSlot].Get();
    ID3D12Resource* currLuma = lumaSlots_[nextCurr].Get();

    const uint32_t defaultMult = (cfg_.targetFps >= 90) ? 3u : 2u;
    const uint32_t mult = (frameGenMultiplier == 0)
                              ? defaultMult
                              : std::clamp(frameGenMultiplier, 1u, static_cast<uint32_t>(outputSlots_.size()));
    const bool doFrameGen = (uniqueFrameCount_ > 0) && (mult > 1u);
    outBatch.didFrameGen = doFrameGen;

    // 2. Stage A command list: upload NV12, convert to BGRA8 into currColor, run compute flow if fallback
    ID3D12GraphicsCommandList* list = nullptr;
    if (!ctx_->beginCommandList(&list)) return false;

    ctx_->writeTimestamp(list, 0);

    ctx_->transition(list, yTex_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COPY_DEST);
    ctx_->transition(list, uvTex_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COPY_DEST);

    D3D12_TEXTURE_COPY_LOCATION yDst{yTex_.Get(), D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX, {0}};
    D3D12_TEXTURE_COPY_LOCATION ySrc{nv12UploadBuf_.Get(), D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT, {yFootprint_}};
    list->CopyTextureRegion(&yDst, 0, 0, 0, &ySrc, nullptr);

    D3D12_TEXTURE_COPY_LOCATION uvDst{uvTex_.Get(), D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX, {0}};
    D3D12_TEXTURE_COPY_LOCATION uvSrc{nv12UploadBuf_.Get(), D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT, {uvFootprint_}};
    list->CopyTextureRegion(&uvDst, 0, 0, 0, &uvSrc, nullptr);

    ctx_->transition(list, yTex_.Get(), D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    ctx_->transition(list, uvTex_.Get(), D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
    ctx_->transition(list, currColor, D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);

    const Nv12Const nv12c{inW, inH, 0, 0};
    ID3D12Resource* nv12Srvs[] = {yTex_.Get(), uvTex_.Get()};
    ID3D12Resource* nv12Uavs[] = {currColor};
    ctx_->dispatchCompute(list, passNv12ToRgb_, &nv12c, 4, nv12Srvs, nullptr, 2, nv12Uavs, nullptr, 1, gxIn, gyIn);

    ctx_->transition(list, yTex_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_COMMON);
    ctx_->transition(list, uvTex_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_COMMON);
    ctx_->transition(list, currColor, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COMMON);
    ctx_->writeTimestamp(list, 1);

    if (doFrameGen && !usingNvof_) {
        ID3D12Resource* pairColor[2] = {prevColor, currColor};
        ID3D12Resource* pairLuma[2] = {prevLuma, currLuma};
        for (int i = 0; i < 2; ++i) {
            ctx_->transition(list, pairColor[i], D3D12_RESOURCE_STATE_COMMON,
                             D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            ctx_->transition(list, pairLuma[i], D3D12_RESOURCE_STATE_COMMON,
                             D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
            ID3D12Resource* s[] = {pairColor[i]};
            ID3D12Resource* u[] = {pairLuma[i]};
            ctx_->dispatchCompute(list, passLumaExtract_, &nv12c, 4, s, nullptr, 1, u, nullptr, 1, gxIn, gyIn);
            ctx_->transition(list, pairLuma[i], D3D12_RESOURCE_STATE_UNORDERED_ACCESS,
                             D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            ctx_->transition(list, pairColor[i], D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                             D3D12_RESOURCE_STATE_COMMON);
        }

        ctx_->transition(list, flowFwdRaw_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        ctx_->transition(list, costFwdRaw_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        ctx_->transition(list, flowRevRaw_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
        ctx_->transition(list, costRevRaw_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);

        const DisConst dc{inW, inH, gridW_, gridH_, kGridSize, 24u, 0, 0};
        ID3D12Resource* disSrvs[] = {prevLuma, currLuma};
        ID3D12Resource* disUavs[] = {flowFwdRaw_.Get(), costFwdRaw_.Get(), flowRevRaw_.Get(), costRevRaw_.Get()};
        ctx_->dispatchCompute(list, passDisFlow_, &dc, 8, disSrvs, nullptr, 2, disUavs, nullptr, 4, gxGrid, gyGrid);

        ctx_->transition(list, prevLuma, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, currLuma, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, flowFwdRaw_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, costFwdRaw_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, flowRevRaw_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, costRevRaw_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COMMON);
        ctx_->writeTimestamp(list, 2);
    }

    uint64_t uploadFence = 0;
    if (doFrameGen && usingNvof_) {
        const uint64_t stageAFence = ctx_->submitCommandList();
        if (!stageAFence) return false;
        uploadFence = stageAFence;

        const uint64_t inVal = ++nvofInVal_;
        ctx_->queue()->Signal(nvofInFence_.Get(), inVal);
        const bool swapInputs = (nextCurr == 0);
        if (!nvof_.execute(inVal, swapInputs)) return false;
        ctx_->queue()->Wait(nvofOutFence_.Get(), nvof_.lastOutValue());

        if (!ctx_->beginCommandList(&list)) return false;
        ctx_->writeTimestamp(list, 2);
    }

    auto emitUpscaledOrSharpened = [&](ID3D12Resource* srcTex, D3D12_RESOURCE_STATES srcInitialState,
                                       ID3D12Resource* dstTex) {
        if (sameSize) {
            if (srcInitialState != D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE) {
                ctx_->transition(list, srcTex, srcInitialState, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            }
            ctx_->transition(list, dstTex, D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
            const RcasConst rcConst{outW, outH, cfg_.rcasSharpness, 0.0f};
            ID3D12Resource* rcasSrvs[] = {srcTex};
            ID3D12Resource* rcasUavs[] = {dstTex};
            ctx_->dispatchCompute(list, passRcas_, &rcConst, 4, rcasSrvs, nullptr, 1, rcasUavs, nullptr, 1, gxOut, gyOut);
            ctx_->transition(list, dstTex, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COMMON);
            if (srcInitialState != D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE) {
                ctx_->transition(list, srcTex, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, srcInitialState);
            }
        } else {
            if (srcInitialState != D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE) {
                ctx_->transition(list, srcTex, srcInitialState, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            }
            ctx_->transition(list, upscaleTmpTex_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
            const EasuConst ec{inW, inH, outW, outH};
            ID3D12Resource* easuSrvs[] = {srcTex};
            ID3D12Resource* easuUavs[] = {upscaleTmpTex_.Get()};
            ctx_->dispatchCompute(list, passEasu_, &ec, 4, easuSrvs, nullptr, 1, easuUavs, nullptr, 1, gxOut, gyOut);

            ctx_->transition(list, upscaleTmpTex_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS,
                             D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            if (srcInitialState != D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE) {
                ctx_->transition(list, srcTex, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, srcInitialState);
            }
            ctx_->transition(list, dstTex, D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);

            const RcasConst rcConst{outW, outH, cfg_.rcasSharpness, 0.0f};
            ID3D12Resource* rcasSrvs[] = {upscaleTmpTex_.Get()};
            ID3D12Resource* rcasUavs[] = {dstTex};
            ctx_->dispatchCompute(list, passRcas_, &rcConst, 4, rcasSrvs, nullptr, 1, rcasUavs, nullptr, 1, gxOut, gyOut);

            ctx_->transition(list, dstTex, D3D12_RESOURCE_STATE_UNORDERED_ACCESS, D3D12_RESOURCE_STATE_COMMON);
            ctx_->transition(list, upscaleTmpTex_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                             D3D12_RESOURCE_STATE_COMMON);
        }
    };

    // 3. Stage B: FlowRefine + FlowFill + FrameGen + EASU/RCAS
    if (doFrameGen) {
        ctx_->transition(list, flowFwdRaw_.Get(), D3D12_RESOURCE_STATE_COMMON,
                         D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        ctx_->transition(list, flowRevRaw_.Get(), D3D12_RESOURCE_STATE_COMMON,
                         D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        ctx_->transition(list, prevColor, D3D12_RESOURCE_STATE_COMMON,
                         D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        ctx_->transition(list, currColor, D3D12_RESOURCE_STATE_COMMON,
                         D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        ctx_->transition(list, refinedTex_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);

        const RefineConst rc{gridW_, gridH_, flowW_, flowH_, inW, inH, kGridSize, 0.002f};
        ID3D12Resource* refSrvs[] = {flowFwdRaw_.Get(), prevColor, currColor, flowRevRaw_.Get()};
        ID3D12Resource* refUavs[] = {refinedTex_.Get()};
        ctx_->dispatchCompute(list, passFlowRefine_, &rc, 8, refSrvs, nullptr, 4, refUavs, nullptr, 1, gxFlow, gyFlow);

        ctx_->transition(list, refinedTex_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS,
                         D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        ctx_->transition(list, filledFlowTex_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);

        const FillConst fc{flowW_, flowH_, inW, inH, 0.08f, 3.0f, 0.01f, 0.0f};
        ID3D12Resource* fillSrvs[] = {refinedTex_.Get(), currColor};
        ID3D12Resource* fillUavs[] = {filledFlowTex_.Get()};
        ctx_->dispatchCompute(list, passFlowFill_, &fc, 8, fillSrvs, nullptr, 2, fillUavs, nullptr, 1, gxFlow, gyFlow);

        ctx_->transition(list, filledFlowTex_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS,
                         D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        ctx_->writeTimestamp(list, 3);

        const uint32_t interpCount = mult - 1u;
        for (uint32_t k = 0; k < interpCount; ++k) {
            const float phase = (interpCount == 1u) ? 0.5f : (k == 0 ? (1.0f / 3.0f) : (2.0f / 3.0f));

            ctx_->transition(list, interpTex_.Get(), D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
            FrameGenConst fgc{};
            fgc.w = inW;
            fgc.h = inH;
            fgc.fw = flowW_;
            fgc.fh = flowH_;
            fgc.gw = gridW_;
            fgc.gh = gridH_;
            fgc.gs = kGridSize;
            fgc.phase = phase;
            std::memcpy(fgc.hud0, cfg_.hudRect0.data(), sizeof(fgc.hud0));
            std::memcpy(fgc.hud1, cfg_.hudRect1.data(), sizeof(fgc.hud1));
            fgc.hudEnable = cfg_.hudProtect ? 1u : 0u;
            fgc.staticThresh = 0.025f;

            ID3D12Resource* fgSrvs[] = {prevColor, currColor, filledFlowTex_.Get(),
                                        flowRevRaw_.Get(), refinedTex_.Get()};
            ID3D12Resource* fgUavs[] = {interpTex_.Get()};
            ctx_->dispatchCompute(list, passFrameGen_, &fgc, 20, fgSrvs, nullptr, 5, fgUavs, nullptr, 1, gxIn, gyIn);

            ctx_->transition(list, interpTex_.Get(), D3D12_RESOURCE_STATE_UNORDERED_ACCESS,
                             D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            emitUpscaledOrSharpened(interpTex_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, outputSlots_[k].Get());
            ctx_->transition(list, interpTex_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                             D3D12_RESOURCE_STATE_COMMON);

            outBatch.frames.push_back(outputSlots_[k].Get());
            outBatch.isGenerated.push_back(true);
        }

        // Upscale/sharpen real current frame while currColor is ALREADY in SRV state!
        const uint32_t realSlotIdx = interpCount;
        emitUpscaledOrSharpened(currColor, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE, outputSlots_[realSlotIdx].Get());
        outBatch.frames.push_back(outputSlots_[realSlotIdx].Get());
        outBatch.isGenerated.push_back(false);

        ctx_->transition(list, filledFlowTex_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, refinedTex_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, flowFwdRaw_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, flowRevRaw_.Get(), D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, prevColor, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
        ctx_->transition(list, currColor, D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE,
                         D3D12_RESOURCE_STATE_COMMON);
    } else {
        // 4. Upscale/sharpen only the real current frame (currColor)
        emitUpscaledOrSharpened(currColor, D3D12_RESOURCE_STATE_COMMON, outputSlots_[0].Get());
        outBatch.frames.push_back(outputSlots_[0].Get());
        outBatch.isGenerated.push_back(false);
    }

    ctx_->writeTimestamp(list, 4);
    ctx_->resolveTimestamps(list, 5);

    const uint64_t finalFence = ctx_->submitCommandList();
    if (!finalFence) return false;
    lastUploadFence_ = uploadFence ? uploadFence : finalFence;
    outBatch.submitFenceValue = finalFence;
    currSlot_ = nextCurr;
    ++uniqueFrameCount_;

    if (waitForCompletion) {
        if (!ctx_->waitForFenceValue(ctx_->fence(), finalFence)) {
            return false;
        }
        const auto wallEnd = std::chrono::steady_clock::now();
        outBatch.timings = readLastGpuTimings(doFrameGen);
        outBatch.timings.totalGpuMs = std::chrono::duration<double, std::milli>(wallEnd - wallStart).count();
    } else {
        const auto wallEnd = std::chrono::steady_clock::now();
        outBatch.timings.totalGpuMs = std::chrono::duration<double, std::milli>(wallEnd - wallStart).count();
    }

    return true;
}

} // namespace gta60::pipeline

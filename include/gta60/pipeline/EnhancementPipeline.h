#pragma once
#include "gta60/media/MfH264Decoder.h"
#include "gta60/nvof/NvOfSession.h"
#include "gta60/pipeline/D3D12Context.h"

#include <array>
#include <cstdint>
#include <vector>

namespace gta60::pipeline {

struct PipelineConfig {
    uint32_t inputWidth = 1920;
    uint32_t inputHeight = 1080;
    uint32_t outputWidth = 2560;
    uint32_t outputHeight = 1440;
    uint32_t targetFps = 60; // 60 (2x) or 90 (3x)
    bool useNvofa = true;
    DXGI_FORMAT outputFormat = DXGI_FORMAT_R8G8B8A8_UNORM;
    float rcasSharpness = 0.25f;
    bool hudProtect = true;
    std::array<float, 4> hudRect0{0.0f, 0.72f, 0.22f, 1.0f}; // bottom-left minimap
    std::array<float, 4> hudRect1{0.78f, 0.0f, 1.0f, 0.15f}; // top-right status HUD
};

struct StageTimings {
    double nv12UploadAndConvertMs = 0.0;
    double opticalFlowMs = 0.0;
    double flowRefineAndFillMs = 0.0;
    double frameGenAndUpscaleMs = 0.0;
    double totalGpuMs = 0.0;
};

struct ProcessedFrameBatch {
    // Outputs ordered chronologically:
    //   2x (60 FPS): [0]=interpolated(t=0.5), [1]=real(curr)
    //   3x (90 FPS): [0]=interpolated(t=1/3), [1]=interpolated(t=2/3), [2]=real(curr)
    //   1x / First frame: [0]=real(curr)
    std::vector<ID3D12Resource*> frames;
    std::vector<bool> isGenerated;
    StageTimings timings;
    uint64_t submitFenceValue = 0;
    bool didFrameGen = false;
};

class EnhancementPipeline {
public:
    EnhancementPipeline() = default;
    ~EnhancementPipeline();
    EnhancementPipeline(const EnhancementPipeline&) = delete;
    EnhancementPipeline& operator=(const EnhancementPipeline&) = delete;

    bool initialize(D3D12Context& ctx, const PipelineConfig& cfg);
    void shutdown();

    // Processes one unique (post-dedup) NV12 frame on the GPU.
    // frameGenMultiplier: 0 = auto from cfg.targetFps (2x for 60, 3x for 90), 1 = upscale/sharpen only (no FG), 2 or 3 = explicit.
    // waitForCompletion: false allows GPU-chaining straight into NVENC on the same command queue without a CPU stall.
    bool processUniqueFrame(const media::DecodedNv12Frame& frame,
                            ProcessedFrameBatch& outBatch,
                            uint32_t frameGenMultiplier = 0,
                            bool waitForCompletion = true);

    void resetTemporalHistory() {
        uniqueFrameCount_ = 0;
        currSlot_ = 1;
    }

    StageTimings readLastGpuTimings(bool didFrameGen) const;

    bool usingHardwareNvof() const { return usingNvof_; }
    const PipelineConfig& config() const { return cfg_; }

    // Exposed for unit/integration inspection in tests
    ID3D12Resource* forwardFlowGridTex() const { return flowFwdRaw_.Get(); }
    ID3D12Resource* refinedFlowTex() const { return refinedTex_.Get(); }
    ID3D12Resource* filledFlowTex() const { return filledFlowTex_.Get(); }
    ID3D12Resource* prevColorTex() const { return colorSlots_[1 - currSlot_].Get(); }
    ID3D12Resource* currColorTex() const { return colorSlots_[currSlot_].Get(); }

private:
    D3D12Context* ctx_ = nullptr;
    PipelineConfig cfg_;
    bool initialized_ = false;
    bool usingNvof_ = false;
    uint64_t uniqueFrameCount_ = 0;
    int currSlot_ = 1;
    uint32_t gridW_ = 0, gridH_ = 0;
    uint32_t flowW_ = 0, flowH_ = 0;
    static constexpr uint32_t kGridSize = 4;

    // Upload & NV12 textures
    ComPtr<ID3D12Resource> nv12UploadBuf_;
    uint8_t* nv12UploadMapped_ = nullptr;
    uint64_t lastUploadFence_ = 0;
    uint64_t uploadSize_ = 0;
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT yFootprint_{};
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT uvFootprint_{};
    ComPtr<ID3D12Resource> yTex_;
    ComPtr<ID3D12Resource> uvTex_;

    // Prev/Curr working color (B8G8R8A8_UNORM for NVOFA compatibility)
    ComPtr<ID3D12Resource> colorSlots_[2];
    ComPtr<ID3D12Resource> lumaSlots_[2];

    // Optical flow & refinement
    ComPtr<ID3D12Resource> flowFwdRaw_;
    ComPtr<ID3D12Resource> costFwdRaw_;
    ComPtr<ID3D12Resource> flowRevRaw_;
    ComPtr<ID3D12Resource> costRevRaw_;
    ComPtr<ID3D12Resource> refinedTex_;
    ComPtr<ID3D12Resource> filledFlowTex_;

    // FrameGen & FSR1 intermediate/output textures
    ComPtr<ID3D12Resource> interpTex_;
    ComPtr<ID3D12Resource> upscaleTmpTex_;
    std::vector<ComPtr<ID3D12Resource>> outputSlots_; // up to 3 (real + 2 generated)

    // Hardware NVOFA session + sync fences
    nvof::NvOfSession nvof_;
    ComPtr<ID3D12Fence> nvofInFence_;
    ComPtr<ID3D12Fence> nvofOutFence_;
    uint64_t nvofInVal_ = 0;

    // Compute shaders
    ComputePass passNv12ToRgb_;
    ComputePass passLumaExtract_;
    ComputePass passDisFlow_;
    ComputePass passFlowRefine_;
    ComputePass passFlowFill_;
    ComputePass passFrameGen_;
    ComputePass passEasu_;
    ComputePass passRcas_;
};

} // namespace gta60::pipeline

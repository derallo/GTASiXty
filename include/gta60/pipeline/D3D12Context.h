#pragma once
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>

#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace gta60::pipeline {

template <typename T>
using ComPtr = Microsoft::WRL::ComPtr<T>;

struct ComputePass {
    ComPtr<ID3D12RootSignature> rootSig;
    ComPtr<ID3D12PipelineState> pso;
    UINT numConstants32 = 0;
    UINT numSrvs = 0;
    UINT numUavs = 0;

    bool create(ID3D12Device* device,
                const std::vector<uint8_t>& dxil,
                UINT constants32,
                UINT srvs,
                UINT uavs);
};

class D3D12Context {
public:
    D3D12Context() = default;
    ~D3D12Context();
    D3D12Context(const D3D12Context&) = delete;
    D3D12Context& operator=(const D3D12Context&) = delete;

    // If preferredLuid != 0, selects the DXGI adapter matching that LUID (required by OpenXR).
    bool initialize(LUID preferredLuid = {});
    void shutdown();

    ID3D12Device* device() const { return device_.Get(); }
    ID3D12CommandQueue* queue() const { return queue_.Get(); }
    ID3D12Fence* fence() const { return fence_.Get(); }
    LUID adapterLuid() const { return adapterLuid_; }
    const std::wstring& adapterName() const { return adapterName_; }

    ComPtr<ID3D12Resource> createTexture2D(uint32_t w, uint32_t h, DXGI_FORMAT fmt,
                                           D3D12_RESOURCE_FLAGS flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS,
                                           D3D12_RESOURCE_STATES initialState = D3D12_RESOURCE_STATE_COMMON);
    ComPtr<ID3D12Resource> createBuffer(uint64_t size, D3D12_HEAP_TYPE heapType,
                                        D3D12_RESOURCE_STATES initialState,
                                        D3D12_RESOURCE_FLAGS flags = D3D12_RESOURCE_FLAG_NONE);

    bool beginCommandList(ID3D12GraphicsCommandList** outList);
    uint64_t submitCommandList();
    bool waitForFenceValue(ID3D12Fence* f, uint64_t value, uint32_t timeoutMs = 5000);
    bool waitIdle();

    // Stages SRVs and UAVs via non-shader-visible CPU heap -> GPU shader-visible heap ring,
    // binds root signature + PSO + root constants, and dispatches.
    void dispatchCompute(ID3D12GraphicsCommandList* list,
                         const ComputePass& pass,
                         const void* constants,
                         UINT numConstants32,
                         ID3D12Resource* const* srvs,
                         const DXGI_FORMAT* srvFormats,
                         UINT numSrvs,
                         ID3D12Resource* const* uavs,
                         const DXGI_FORMAT* uavFormats,
                         UINT numUavs,
                         uint32_t groupsX,
                         uint32_t groupsY,
                         uint32_t groupsZ = 1);

    void transition(ID3D12GraphicsCommandList* list,
                    ID3D12Resource* res,
                    D3D12_RESOURCE_STATES from,
                    D3D12_RESOURCE_STATES to);
    void uavBarrier(ID3D12GraphicsCommandList* list, ID3D12Resource* res);

    // GPU timestamp helpers
    void resetTimestamps(ID3D12GraphicsCommandList* list);
    void writeTimestamp(ID3D12GraphicsCommandList* list, UINT index);
    void resolveTimestamps(ID3D12GraphicsCommandList* list, UINT count);
    double elapsedMs(UINT startIdx, UINT endIdx) const;

    // Reads back a 2D texture to CPU memory (for automated tests/verification).
    bool readbackTexture2D(ID3D12Resource* tex, std::vector<uint8_t>& outBytes, uint32_t& outRowPitch);

    static bool loadShaderDxil(const std::string& name, std::vector<uint8_t>& outBytes);

private:
    static constexpr UINT kAllocatorCount = 4;
    ComPtr<ID3D12Device> device_;
    ComPtr<ID3D12CommandQueue> queue_;
    ComPtr<ID3D12CommandAllocator> allocators_[kAllocatorCount];
    uint64_t allocatorFenceValues_[kAllocatorCount]{};
    UINT allocatorIdx_ = 0;
    ComPtr<ID3D12GraphicsCommandList> cmdList_;
    ComPtr<ID3D12Fence> fence_;
    HANDLE fenceEvent_ = nullptr;
    uint64_t fenceValue_ = 0;

    // Non-shader-visible staging heap (2 descriptors) + shader-visible ring heap
    ComPtr<ID3D12DescriptorHeap> cpuStagingHeap_;
    ComPtr<ID3D12DescriptorHeap> gpuVisibleHeap_;
    UINT srvUavInc_ = 0;
    UINT gpuHeapCapacity_ = 4096;
    UINT gpuHeapCursor_ = 0;

    // GPU timestamps
    static constexpr UINT kMaxTimestamps = 32;
    ComPtr<ID3D12QueryHeap> queryHeap_;
    ComPtr<ID3D12Resource> queryReadback_;
    uint64_t gpuTickFreq_ = 1;

    LUID adapterLuid_{};
    std::wstring adapterName_;
};

} // namespace gta60::pipeline

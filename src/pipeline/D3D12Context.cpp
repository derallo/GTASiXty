#include "gta60/pipeline/D3D12Context.h"
#include "gta60/Log.h"

#include <windows.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <format>
#include <fstream>

namespace gta60::pipeline {

bool ComputePass::create(ID3D12Device* device,
                         const std::vector<uint8_t>& dxil,
                         UINT constants32,
                         UINT srvs,
                         UINT uavs) {
    if (!device || dxil.empty()) return false;
    numConstants32 = constants32;
    numSrvs = srvs;
    numUavs = uavs;

    std::vector<D3D12_ROOT_PARAMETER1> params;
    D3D12_DESCRIPTOR_RANGE1 srvRange{};
    D3D12_DESCRIPTOR_RANGE1 uavRange{};

    if (constants32 > 0) {
        D3D12_ROOT_PARAMETER1 p{};
        p.ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
        p.Constants.ShaderRegister = 0;
        p.Constants.RegisterSpace = 0;
        p.Constants.Num32BitValues = constants32;
        p.ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
        params.push_back(p);
    }
    if (srvs > 0) {
        srvRange.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
        srvRange.NumDescriptors = srvs;
        srvRange.BaseShaderRegister = 0;
        srvRange.Flags = D3D12_DESCRIPTOR_RANGE_FLAG_DATA_VOLATILE;
        srvRange.OffsetInDescriptorsFromTableStart = 0;

        D3D12_ROOT_PARAMETER1 p{};
        p.ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        p.DescriptorTable.NumDescriptorRanges = 1;
        p.DescriptorTable.pDescriptorRanges = &srvRange;
        p.ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
        params.push_back(p);
    }
    if (uavs > 0) {
        uavRange.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_UAV;
        uavRange.NumDescriptors = uavs;
        uavRange.BaseShaderRegister = 0;
        uavRange.Flags = D3D12_DESCRIPTOR_RANGE_FLAG_DATA_VOLATILE;
        uavRange.OffsetInDescriptorsFromTableStart = 0;

        D3D12_ROOT_PARAMETER1 p{};
        p.ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        p.DescriptorTable.NumDescriptorRanges = 1;
        p.DescriptorTable.pDescriptorRanges = &uavRange;
        p.ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
        params.push_back(p);
    }

    D3D12_VERSIONED_ROOT_SIGNATURE_DESC rsDesc{};
    rsDesc.Version = D3D_ROOT_SIGNATURE_VERSION_1_1;
    rsDesc.Desc_1_1.NumParameters = static_cast<UINT>(params.size());
    rsDesc.Desc_1_1.pParameters = params.empty() ? nullptr : params.data();
    rsDesc.Desc_1_1.Flags = D3D12_ROOT_SIGNATURE_FLAG_NONE;

    ComPtr<ID3DBlob> sigBlob, errBlob;
    HRESULT hr = D3D12SerializeVersionedRootSignature(&rsDesc, &sigBlob, &errBlob);
    if (FAILED(hr)) {
        const char* msg = errBlob ? static_cast<const char*>(errBlob->GetBufferPointer()) : "unknown";
        log::error("d3d12", std::format("D3D12SerializeVersionedRootSignature failed hr=0x{:X}: {}", unsigned(hr), msg));
        return false;
    }

    hr = device->CreateRootSignature(0, sigBlob->GetBufferPointer(), sigBlob->GetBufferSize(),
                                     IID_PPV_ARGS(&rootSig));
    if (FAILED(hr)) {
        log::error("d3d12", std::format("CreateRootSignature failed hr=0x{:X}", unsigned(hr)));
        return false;
    }

    D3D12_COMPUTE_PIPELINE_STATE_DESC psoDesc{};
    psoDesc.pRootSignature = rootSig.Get();
    psoDesc.CS = {dxil.data(), dxil.size()};
    hr = device->CreateComputePipelineState(&psoDesc, IID_PPV_ARGS(&pso));
    if (FAILED(hr)) {
        log::error("d3d12", std::format("CreateComputePipelineState failed hr=0x{:X}", unsigned(hr)));
        return false;
    }
    return true;
}

D3D12Context::~D3D12Context() { shutdown(); }

bool D3D12Context::initialize(LUID preferredLuid) {
    shutdown();

    ComPtr<IDXGIFactory4> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) {
        log::error("d3d12", "CreateDXGIFactory1 failed");
        return false;
    }

    ComPtr<IDXGIAdapter1> chosen;
    const bool hasLuid = (preferredLuid.LowPart != 0 || preferredLuid.HighPart != 0);

    ComPtr<IDXGIFactory6> factory6;
    factory.As(&factory6);

    for (UINT i = 0;; ++i) {
        ComPtr<IDXGIAdapter1> cand;
        const HRESULT enumHr = factory6
                                   ? factory6->EnumAdapterByGpuPreference(i, DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,
                                                                          IID_PPV_ARGS(&cand))
                                   : factory->EnumAdapters1(i, &cand);
        if (enumHr == DXGI_ERROR_NOT_FOUND) break;
        if (FAILED(enumHr) || !cand) continue;

        DXGI_ADAPTER_DESC1 desc{};
        cand->GetDesc1(&desc);
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) continue;
        if (hasLuid) {
            if (desc.AdapterLuid.LowPart == preferredLuid.LowPart &&
                desc.AdapterLuid.HighPart == preferredLuid.HighPart) {
                chosen = cand;
                adapterLuid_ = desc.AdapterLuid;
                adapterName_ = desc.Description;
                break;
            }
        } else if (!chosen) {
            chosen = cand;
            adapterLuid_ = desc.AdapterLuid;
            adapterName_ = desc.Description;
            break;
        }
    }

    if (!chosen) {
        log::error("d3d12", "No suitable hardware DXGI adapter found");
        return false;
    }

    HRESULT hr = D3D12CreateDevice(chosen.Get(), D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&device_));
    if (FAILED(hr)) {
        log::error("d3d12", std::format("D3D12CreateDevice failed hr=0x{:X}", unsigned(hr)));
        return false;
    }

    D3D12_COMMAND_QUEUE_DESC qd{};
    qd.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    if (FAILED(device_->CreateCommandQueue(&qd, IID_PPV_ARGS(&queue_)))) {
        shutdown();
        return false;
    }
    queue_->GetTimestampFrequency(&gpuTickFreq_);

    for (UINT i = 0; i < kAllocatorCount; ++i) {
        if (FAILED(device_->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&allocators_[i])))) {
            shutdown();
            return false;
        }
        allocatorFenceValues_[i] = 0;
    }
    allocatorIdx_ = 0;
    if (FAILED(device_->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocators_[0].Get(), nullptr,
                                          IID_PPV_ARGS(&cmdList_)))) {
        shutdown();
        return false;
    }
    cmdList_->Close();

    if (FAILED(device_->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence_)))) {
        shutdown();
        return false;
    }
    fenceEvent_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!fenceEvent_) {
        shutdown();
        return false;
    }

    srvUavInc_ = device_->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);

    D3D12_DESCRIPTOR_HEAP_DESC cpuDesc{};
    cpuDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;
    cpuDesc.NumDescriptors = 64;
    cpuDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_NONE;
    if (FAILED(device_->CreateDescriptorHeap(&cpuDesc, IID_PPV_ARGS(&cpuStagingHeap_)))) {
        shutdown();
        return false;
    }

    D3D12_DESCRIPTOR_HEAP_DESC gpuDesc{};
    gpuDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;
    gpuDesc.NumDescriptors = gpuHeapCapacity_;
    gpuDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
    if (FAILED(device_->CreateDescriptorHeap(&gpuDesc, IID_PPV_ARGS(&gpuVisibleHeap_)))) {
        shutdown();
        return false;
    }

    D3D12_QUERY_HEAP_DESC qhd{};
    qhd.Type = D3D12_QUERY_HEAP_TYPE_TIMESTAMP;
    qhd.Count = kMaxTimestamps;
    if (SUCCEEDED(device_->CreateQueryHeap(&qhd, IID_PPV_ARGS(&queryHeap_)))) {
        queryReadback_ = createBuffer(sizeof(uint64_t) * kMaxTimestamps, D3D12_HEAP_TYPE_READBACK,
                                      D3D12_RESOURCE_STATE_COPY_DEST);
    }

    log::info("d3d12", std::format("initialized adapter='{}'", log::narrow(adapterName_)));
    return true;
}

void D3D12Context::shutdown() {
    if (device_ && queue_ && fence_) waitIdle();
    if (fenceEvent_) {
        CloseHandle(fenceEvent_);
        fenceEvent_ = nullptr;
    }
    queryReadback_.Reset();
    queryHeap_.Reset();
    gpuVisibleHeap_.Reset();
    cpuStagingHeap_.Reset();
    fence_.Reset();
    cmdList_.Reset();
    for (UINT i = 0; i < kAllocatorCount; ++i) {
        allocators_[i].Reset();
        allocatorFenceValues_[i] = 0;
    }
    allocatorIdx_ = 0;
    queue_.Reset();
    device_.Reset();
    fenceValue_ = 0;
    gpuHeapCursor_ = 0;
}

ComPtr<ID3D12Resource> D3D12Context::createTexture2D(uint32_t w, uint32_t h, DXGI_FORMAT fmt,
                                                     D3D12_RESOURCE_FLAGS flags,
                                                     D3D12_RESOURCE_STATES initialState) {
    D3D12_HEAP_PROPERTIES hp{};
    hp.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC td{};
    td.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    td.Width = w;
    td.Height = h;
    td.DepthOrArraySize = 1;
    td.MipLevels = 1;
    td.Format = fmt;
    td.SampleDesc.Count = 1;
    td.Flags = flags;
    ComPtr<ID3D12Resource> r;
    const HRESULT hr = device_->CreateCommittedResource(&hp, D3D12_HEAP_FLAG_NONE, &td, initialState, nullptr,
                                                        IID_PPV_ARGS(&r));
    if (FAILED(hr)) {
        log::error("d3d12", std::format("createTexture2D {}x{} fmt={} failed hr=0x{:X}", w, h, int(fmt), unsigned(hr)));
        return nullptr;
    }
    return r;
}

ComPtr<ID3D12Resource> D3D12Context::createBuffer(uint64_t size, D3D12_HEAP_TYPE heapType,
                                                  D3D12_RESOURCE_STATES initialState,
                                                  D3D12_RESOURCE_FLAGS flags) {
    D3D12_HEAP_PROPERTIES hp{};
    hp.Type = heapType;
    D3D12_RESOURCE_DESC bd{};
    bd.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    bd.Width = size;
    bd.Height = 1;
    bd.DepthOrArraySize = 1;
    bd.MipLevels = 1;
    bd.SampleDesc.Count = 1;
    bd.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    bd.Flags = flags;
    ComPtr<ID3D12Resource> r;
    const HRESULT hr = device_->CreateCommittedResource(&hp, D3D12_HEAP_FLAG_NONE, &bd, initialState, nullptr,
                                                        IID_PPV_ARGS(&r));
    if (FAILED(hr)) {
        log::error("d3d12", std::format("createBuffer size={} failed hr=0x{:X}", size, unsigned(hr)));
        return nullptr;
    }
    return r;
}

bool D3D12Context::beginCommandList(ID3D12GraphicsCommandList** outList) {
    if (!allocators_[0] || !cmdList_) return false;
    allocatorIdx_ = (allocatorIdx_ + 1) % kAllocatorCount;
    const uint64_t prevVal = allocatorFenceValues_[allocatorIdx_];
    if (prevVal > 0 && fence_ && fence_->GetCompletedValue() < prevVal) {
        if (!waitForFenceValue(fence_.Get(), prevVal)) return false;
    }
    auto* alloc = allocators_[allocatorIdx_].Get();
    if (FAILED(alloc->Reset()) || FAILED(cmdList_->Reset(alloc, nullptr))) {
        return false;
    }
    ID3D12DescriptorHeap* heaps[] = {gpuVisibleHeap_.Get()};
    cmdList_->SetDescriptorHeaps(1, heaps);
    if (outList) *outList = cmdList_.Get();
    return true;
}

uint64_t D3D12Context::submitCommandList() {
    if (FAILED(cmdList_->Close())) return 0;
    ID3D12CommandList* lists[] = {cmdList_.Get()};
    queue_->ExecuteCommandLists(1, lists);
    const uint64_t val = ++fenceValue_;
    queue_->Signal(fence_.Get(), val);
    allocatorFenceValues_[allocatorIdx_] = val;
    return val;
}

bool D3D12Context::waitForFenceValue(ID3D12Fence* f, uint64_t value, uint32_t timeoutMs) {
    if (!f || value == 0) return true;
    if (f->GetCompletedValue() >= value) return true;
    if (FAILED(f->SetEventOnCompletion(value, fenceEvent_))) return false;
    return WaitForSingleObject(fenceEvent_, timeoutMs) == WAIT_OBJECT_0;
}

bool D3D12Context::waitIdle() {
    if (!queue_ || !fence_) return true;
    const uint64_t val = ++fenceValue_;
    if (FAILED(queue_->Signal(fence_.Get(), val))) return false;
    return waitForFenceValue(fence_.Get(), val);
}

void D3D12Context::dispatchCompute(ID3D12GraphicsCommandList* list,
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
                                   uint32_t groupsZ) {
    const UINT totalNeeded = numSrvs + numUavs;
    if (gpuHeapCursor_ + totalNeeded > gpuHeapCapacity_) {
        gpuHeapCursor_ = 0;
    }

    const D3D12_CPU_DESCRIPTOR_HANDLE stageBase = cpuStagingHeap_->GetCPUDescriptorHandleForHeapStart();
    const D3D12_CPU_DESCRIPTOR_HANDLE gpuCpuBase = gpuVisibleHeap_->GetCPUDescriptorHandleForHeapStart();
    const D3D12_GPU_DESCRIPTOR_HANDLE gpuBase = gpuVisibleHeap_->GetGPUDescriptorHandleForHeapStart();

    const UINT srvOffset = gpuHeapCursor_;
    for (UINT i = 0; i < numSrvs; ++i) {
        D3D12_SHADER_RESOURCE_VIEW_DESC d{};
        d.Format = srvFormats ? srvFormats[i] : srvs[i]->GetDesc().Format;
        d.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
        d.Texture2D.MipLevels = 1;
        d.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
        D3D12_CPU_DESCRIPTOR_HANDLE stageH{stageBase.ptr + SIZE_T(i) * srvUavInc_};
        device_->CreateShaderResourceView(srvs[i], &d, stageH);
    }

    const UINT uavOffset = gpuHeapCursor_ + numSrvs;
    for (UINT i = 0; i < numUavs; ++i) {
        D3D12_UNORDERED_ACCESS_VIEW_DESC d{};
        d.Format = uavFormats ? uavFormats[i] : uavs[i]->GetDesc().Format;
        d.ViewDimension = D3D12_UAV_DIMENSION_TEXTURE2D;
        D3D12_CPU_DESCRIPTOR_HANDLE stageH{stageBase.ptr + SIZE_T(numSrvs + i) * srvUavInc_};
        device_->CreateUnorderedAccessView(uavs[i], nullptr, &d, stageH);
    }

    if (totalNeeded > 0) {
        D3D12_CPU_DESCRIPTOR_HANDLE dstH{gpuCpuBase.ptr + SIZE_T(gpuHeapCursor_) * srvUavInc_};
        device_->CopyDescriptorsSimple(totalNeeded, dstH, stageBase, D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
        gpuHeapCursor_ += totalNeeded;
    }

    list->SetComputeRootSignature(pass.rootSig.Get());
    list->SetPipelineState(pass.pso.Get());

    UINT paramIdx = 0;
    if (pass.numConstants32 > 0 && constants && numConstants32 > 0) {
        list->SetComputeRoot32BitConstants(paramIdx++, numConstants32, constants, 0);
    }
    if (pass.numSrvs > 0) {
        D3D12_GPU_DESCRIPTOR_HANDLE h{gpuBase.ptr + UINT64(srvOffset) * srvUavInc_};
        list->SetComputeRootDescriptorTable(paramIdx++, h);
    }
    if (pass.numUavs > 0) {
        D3D12_GPU_DESCRIPTOR_HANDLE h{gpuBase.ptr + UINT64(uavOffset) * srvUavInc_};
        list->SetComputeRootDescriptorTable(paramIdx++, h);
    }

    list->Dispatch(groupsX, groupsY, groupsZ);
}

void D3D12Context::transition(ID3D12GraphicsCommandList* list,
                              ID3D12Resource* res,
                              D3D12_RESOURCE_STATES from,
                              D3D12_RESOURCE_STATES to) {
    if (from == to || !res) return;
    D3D12_RESOURCE_BARRIER b{};
    b.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    b.Transition.pResource = res;
    b.Transition.StateBefore = from;
    b.Transition.StateAfter = to;
    b.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    list->ResourceBarrier(1, &b);
}

void D3D12Context::uavBarrier(ID3D12GraphicsCommandList* list, ID3D12Resource* res) {
    D3D12_RESOURCE_BARRIER b{};
    b.Type = D3D12_RESOURCE_BARRIER_TYPE_UAV;
    b.UAV.pResource = res;
    list->ResourceBarrier(1, &b);
}

void D3D12Context::resetTimestamps(ID3D12GraphicsCommandList*) {}

void D3D12Context::writeTimestamp(ID3D12GraphicsCommandList* list, UINT index) {
    if (queryHeap_ && index < kMaxTimestamps) {
        list->EndQuery(queryHeap_.Get(), D3D12_QUERY_TYPE_TIMESTAMP, index);
    }
}

void D3D12Context::resolveTimestamps(ID3D12GraphicsCommandList* list, UINT count) {
    if (queryHeap_ && queryReadback_ && count <= kMaxTimestamps) {
        list->ResolveQueryData(queryHeap_.Get(), D3D12_QUERY_TYPE_TIMESTAMP, 0, count, queryReadback_.Get(), 0);
    }
}

double D3D12Context::elapsedMs(UINT startIdx, UINT endIdx) const {
    if (!queryReadback_ || startIdx >= kMaxTimestamps || endIdx >= kMaxTimestamps || gpuTickFreq_ == 0) {
        return 0.0;
    }
    uint64_t* ptr = nullptr;
    D3D12_RANGE readRange{0, sizeof(uint64_t) * kMaxTimestamps};
    if (FAILED(queryReadback_->Map(0, &readRange, reinterpret_cast<void**>(&ptr))) || !ptr) return 0.0;
    const uint64_t t0 = ptr[startIdx];
    const uint64_t t1 = ptr[endIdx];
    D3D12_RANGE emptyRange{0, 0};
    queryReadback_->Unmap(0, &emptyRange);
    if (t1 <= t0) return 0.0;
    return (static_cast<double>(t1 - t0) * 1000.0) / static_cast<double>(gpuTickFreq_);
}

bool D3D12Context::readbackTexture2D(ID3D12Resource* tex, std::vector<uint8_t>& outBytes, uint32_t& outRowPitch) {
    if (!tex || !device_) return false;
    waitIdle();

    const D3D12_RESOURCE_DESC desc = tex->GetDesc();
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint{};
    UINT numRows = 0;
    UINT64 rowBytes = 0;
    UINT64 totalBytes = 0;
    device_->GetCopyableFootprints(&desc, 0, 1, 0, &footprint, &numRows, &rowBytes, &totalBytes);

    auto rb = createBuffer(totalBytes, D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
    if (!rb) return false;

    ID3D12GraphicsCommandList* list = nullptr;
    if (!beginCommandList(&list)) return false;

    transition(list, tex, D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COPY_SOURCE);
    D3D12_TEXTURE_COPY_LOCATION srcLoc{};
    srcLoc.pResource = tex;
    srcLoc.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    srcLoc.SubresourceIndex = 0;

    D3D12_TEXTURE_COPY_LOCATION dstLoc{};
    dstLoc.pResource = rb.Get();
    dstLoc.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    dstLoc.PlacedFootprint = footprint;

    list->CopyTextureRegion(&dstLoc, 0, 0, 0, &srcLoc, nullptr);
    transition(list, tex, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_COMMON);

    const uint64_t val = submitCommandList();
    if (!waitForFenceValue(fence_.Get(), val)) return false;

    uint8_t* mapped = nullptr;
    D3D12_RANGE range{0, static_cast<SIZE_T>(totalBytes)};
    if (FAILED(rb->Map(0, &range, reinterpret_cast<void**>(&mapped))) || !mapped) return false;
    outBytes.assign(mapped, mapped + totalBytes);
    D3D12_RANGE empty{0, 0};
    rb->Unmap(0, &empty);
    outRowPitch = footprint.Footprint.RowPitch;
    return true;
}

bool D3D12Context::loadShaderDxil(const std::string& name, std::vector<uint8_t>& outBytes) {
    wchar_t exePath[MAX_PATH] = {};
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::filesystem::path exeDir = std::filesystem::path(exePath).parent_path();

    std::filesystem::path candidates[] = {
        exeDir / "shaders" / (name + ".dxil"),
        exeDir.parent_path() / "shaders" / (name + ".dxil"),
        std::filesystem::current_path() / "build" / "shaders" / (name + ".dxil"),
        std::filesystem::current_path() / "shaders" / (name + ".dxil"),
    };

    for (const auto& p : candidates) {
        std::ifstream in(p, std::ios::binary | std::ios::ate);
        if (!in) continue;
        const auto sz = in.tellg();
        if (sz <= 0) continue;
        outBytes.resize(static_cast<size_t>(sz));
        in.seekg(0);
        in.read(reinterpret_cast<char*>(outBytes.data()), sz);
        return true;
    }
    log::error("d3d12", std::format("shader {}.dxil not found", name));
    return false;
}

} // namespace gta60::pipeline

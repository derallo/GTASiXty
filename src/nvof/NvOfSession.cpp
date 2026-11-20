#include "gta60/nvof/NvOfSession.h"
#include "gta60/nvof/NvOfD3D12Abi.h"
#include "gta60/Log.h"

#include <windows.h>
#include <wrl/client.h>

#include <algorithm>
#include <format>
#include <memory>

namespace gta60::nvof {

using PFN_NvOFGetMaxSupportedApiVersion = NV_OF_STATUS(NVOFAPI*)(uint32_t*);
using PFN_NvOFAPICreateInstanceD3D12 = NV_OF_STATUS(NVOFAPI*)(uint32_t, NV_OF_D3D12_API_FUNCTION_LIST*);

struct NvOfSession::FnList {
    NV_OF_D3D12_API_FUNCTION_LIST list{};
    PFN_NvOFAPICreateInstanceD3D12 createInstance = nullptr;
};

NvOfSession::~NvOfSession() { shutdown(); }

bool NvOfSession::initialize(ID3D12Device* device,
                             ID3D12Resource* inputA,
                             ID3D12Resource* inputB,
                             ID3D12Resource* flowOut,
                             ID3D12Resource* costOut,
                             const Desc& desc) {
    if (!device || !inputA || !inputB || !flowOut ||
        !desc.inFence || !desc.outFence ||
        (costOut != nullptr && desc.reverseFlow != nullptr && desc.reverseCost == nullptr) ||
        (costOut == nullptr && desc.reverseCost != nullptr)) {
        log::error("nvof", "initialize rejected: invalid null arguments");
        return false;
    }

    if (initialized_) shutdown();
    inFence_ = desc.inFence;
    outFence_ = desc.outFence;
    nextOutValue_ = 1;
    executeCount_ = 0;

    dll_ = LoadLibraryExW(L"nvofapi64.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!dll_) {
        log::warn("nvof", std::format("LoadLibraryExW nvofapi64.dll failed err={}", GetLastError()));
        return false;
    }

    auto getMaxVer = reinterpret_cast<PFN_NvOFGetMaxSupportedApiVersion>(
        GetProcAddress(static_cast<HMODULE>(dll_), "NvOFGetMaxSupportedApiVersion"));
    if (!getMaxVer) {
        log::warn("nvof", "NvOFGetMaxSupportedApiVersion export missing");
        shutdown();
        return false;
    }

    uint32_t maxVer = 0;
    if (getMaxVer(&maxVer) != NV_OF_SUCCESS) {
        log::warn("nvof", "NvOFGetMaxSupportedApiVersion failed");
        shutdown();
        return false;
    }
    const uint32_t apiVer = std::min<uint32_t>(maxVer, NV_OF_API_VERSION);

    fn_ = new FnList();
    fn_->createInstance = reinterpret_cast<PFN_NvOFAPICreateInstanceD3D12>(
        GetProcAddress(static_cast<HMODULE>(dll_), "NvOFAPICreateInstanceD3D12"));
    if (!fn_->createInstance ||
        fn_->createInstance(apiVer, &fn_->list) != NV_OF_SUCCESS ||
        !fn_->list.nvCreateOpticalFlowD3D12 || !fn_->list.nvOFInit ||
        !fn_->list.nvOFExecuteD3D12 || !fn_->list.nvOFRegisterResourceD3D12 ||
        !fn_->list.nvOFUnregisterResourceD3D12 || !fn_->list.nvOFDestroy || !fn_->list.nvOFGetCaps) {
        log::warn("nvof", "NvOFAPICreateInstanceD3D12 failed");
        shutdown();
        return false;
    }

    NvOFHandle ofHandle = nullptr;
    const NV_OF_STATUS cst = fn_->list.nvCreateOpticalFlowD3D12(device, &ofHandle);
    ofHandle_ = ofHandle;
    if (cst != NV_OF_SUCCESS || !ofHandle) {
        log::warn("nvof", std::format("nvCreateOpticalFlowD3D12 failed status={}", static_cast<int>(cst)));
        shutdown();
        return false;
    }

    uint32_t elemCount = 0;
    if (fn_->list.nvOFGetCaps(ofHandle, NV_OF_CAPS_SUPPORTED_OUTPUT_GRID_SIZES, nullptr, &elemCount) != NV_OF_SUCCESS ||
        elemCount == 0 || elemCount > 64) {
        log::warn("nvof", "nvOFGetCaps supported grids query failed");
        shutdown();
        return false;
    }
    caps_.supportedGrids.assign(elemCount, 0);
    uint32_t filled = elemCount;
    if (fn_->list.nvOFGetCaps(ofHandle, NV_OF_CAPS_SUPPORTED_OUTPUT_GRID_SIZES, caps_.supportedGrids.data(), &filled) != NV_OF_SUCCESS ||
        filled == 0) {
        shutdown();
        return false;
    }
    caps_.supportedGrids.resize(filled);

    auto scalarCap = [&](NV_OF_CAPS cap, uint32_t& out) -> bool {
        uint32_t v = 0, one = 1;
        if (fn_->list.nvOFGetCaps(ofHandle, cap, &v, &one) != NV_OF_SUCCESS) return false;
        out = v;
        return true;
    };
    if (!scalarCap(NV_OF_CAPS_WIDTH_MIN, caps_.minWidth) ||
        !scalarCap(NV_OF_CAPS_WIDTH_MAX, caps_.maxWidth) ||
        !scalarCap(NV_OF_CAPS_HEIGHT_MIN, caps_.minHeight) ||
        !scalarCap(NV_OF_CAPS_HEIGHT_MAX, caps_.maxHeight)) {
        shutdown();
        return false;
    }
    caps_.queried = true;

    if (desc.width < caps_.minWidth || desc.width > caps_.maxWidth ||
        desc.height < caps_.minHeight || desc.height > caps_.maxHeight) {
        log::error("nvof", std::format("extent {}x{} outside [{},{}]x[{},{}]",
                                       desc.width, desc.height, caps_.minWidth, caps_.maxWidth,
                                       caps_.minHeight, caps_.maxHeight));
        shutdown();
        return false;
    }

    uint32_t selectedGrid = desc.gridSize;
    if (std::find(caps_.supportedGrids.begin(), caps_.supportedGrids.end(), selectedGrid) == caps_.supportedGrids.end()) {
        log::error("nvof", std::format("grid size {} unsupported", selectedGrid));
        shutdown();
        return false;
    }
    caps_.selectedGrid = selectedGrid;
    gridW_ = (desc.width + selectedGrid - 1) / selectedGrid;
    gridH_ = (desc.height + selectedGrid - 1) / selectedGrid;

    const D3D12_RESOURCE_DESC da = inputA->GetDesc();
    const D3D12_RESOURCE_DESC db = inputB->GetDesc();
    const D3D12_RESOURCE_DESC df = flowOut->GetDesc();
    if (da.Format != DXGI_FORMAT_B8G8R8A8_UNORM || db.Format != DXGI_FORMAT_B8G8R8A8_UNORM ||
        da.Width != desc.width || da.Height != desc.height ||
        db.Width != desc.width || db.Height != desc.height ||
        df.Format != DXGI_FORMAT_R16G16_SINT || df.Width != gridW_ || df.Height != gridH_) {
        log::error("nvof", "resource format/dimension contract mismatch");
        shutdown();
        return false;
    }
    if (costOut) {
        const D3D12_RESOURCE_DESC dc = costOut->GetDesc();
        if (dc.Format != DXGI_FORMAT_R8_UINT || dc.Width != gridW_ || dc.Height != gridH_) {
            log::error("nvof", "cost resource format/dimension contract mismatch");
            shutdown();
            return false;
        }
    }

    NV_OF_INIT_PARAMS init{};
    init.width = desc.width;
    init.height = desc.height;
    init.outGridSize = static_cast<NV_OF_OUTPUT_VECTOR_GRID_SIZE>(selectedGrid);
    init.mode = NV_OF_MODE_OPTICALFLOW;
    init.perfLevel = desc.quality == 0 ? NV_OF_PERF_LEVEL_FAST
                                       : (desc.quality == 1 ? NV_OF_PERF_LEVEL_MEDIUM : NV_OF_PERF_LEVEL_SLOW);
    init.enableExternalHints = NV_OF_FALSE;
    init.enableOutputCost = costOut ? NV_OF_TRUE : NV_OF_FALSE;
    init.predDirection = desc.reverseFlow ? NV_OF_PRED_DIRECTION_BOTH : NV_OF_PRED_DIRECTION_FORWARD;
    init.enableGlobalFlow = NV_OF_FALSE;
    init.inputBufferFormat = NV_OF_BUFFER_FORMAT_ABGR8;

    const NV_OF_STATUS initSt = fn_->list.nvOFInit(ofHandle, &init);
    if (initSt != NV_OF_SUCCESS) {
        log::error("nvof", std::format("nvOFInit failed status={}", static_cast<int>(initSt)));
        shutdown();
        return false;
    }

    Microsoft::WRL::ComPtr<ID3D12Fence> regFence;
    if (FAILED(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&regFence)))) {
        shutdown();
        return false;
    }
    std::unique_ptr<void, decltype(&CloseHandle)> regEvent(CreateEventW(nullptr, FALSE, FALSE, nullptr), &CloseHandle);
    if (!regEvent) {
        shutdown();
        return false;
    }

    const uint64_t inputReady = inFence_->GetCompletedValue();
    uint64_t registered = 0;

    auto finishRegistration = [&]() -> bool {
        if (!registered) return true;
        if (regFence->GetCompletedValue() >= registered) return true;
        if (FAILED(regFence->SetEventOnCompletion(registered, regEvent.get()))) return false;
        return WaitForSingleObject(regEvent.get(), 5000) == WAIT_OBJECT_0;
    };

    auto registerRes = [&](ID3D12Resource* res, void*& handleOut, const char* name) -> bool {
        NvOFGPUBufferHandle h = nullptr;
        NV_OF_REGISTER_RESOURCE_PARAMS_D3D12 reg{};
        reg.resource = res;
        reg.hOFGpuBuffer = &h;
        reg.inputFencePoint = registered ? NV_OF_FENCE_POINT{regFence.Get(), registered}
                                         : NV_OF_FENCE_POINT{inFence_, inputReady};
        reg.outputFencePoint = {regFence.Get(), registered + 1};
        const NV_OF_STATUS st = fn_->list.nvOFRegisterResourceD3D12(ofHandle, &reg);
        handleOut = h;
        if (st != NV_OF_SUCCESS) {
            log::error("nvof", std::format("register {} failed st={}", name, static_cast<int>(st)));
            finishRegistration();
            return false;
        }
        ++registered;
        return true;
    };

    if (!registerRes(inputA, hInputA_, "inputA") ||
        !registerRes(inputB, hInputB_, "inputB") ||
        !registerRes(flowOut, hFlow_, "flowOut") ||
        (costOut && !registerRes(costOut, hCost_, "costOut")) ||
        (desc.reverseFlow && (!registerRes(desc.reverseFlow, hReverseFlow_, "reverseFlow") ||
                              (desc.reverseCost && !registerRes(desc.reverseCost, hReverseCost_, "reverseCost"))))) {
        unregisterAll();
        shutdown();
        return false;
    }

    if (!finishRegistration()) {
        unregisterAll();
        shutdown();
        return false;
    }

    initialized_ = true;
    log::info("nvof", std::format("initialized NVOFA {}x{} grid={} ({}x{}) bidirectional={}",
                                  desc.width, desc.height, selectedGrid, gridW_, gridH_,
                                  desc.reverseFlow != nullptr));
    return true;
}

bool NvOfSession::execute(uint64_t inValue, bool swapInputs) {
    if (!initialized_ || !ofHandle_ || !fn_) return false;

    NV_OF_EXECUTE_INPUT_PARAMS_D3D12 in{};
    NV_OF_EXECUTE_OUTPUT_PARAMS_D3D12 out{};
    NV_OF_FENCE_POINT inFencePt{inFence_, inValue};
    NV_OF_FENCE_POINT outFencePt{outFence_, nextOutValue_};

    in.inputFrame = reinterpret_cast<NvOFGPUBufferHandle>(swapInputs ? hInputA_ : hInputB_);     // current
    in.referenceFrame = reinterpret_cast<NvOFGPUBufferHandle>(swapInputs ? hInputB_ : hInputA_); // previous
    in.disableTemporalHints = NV_OF_TRUE;
    in.numFencePoints = 1;
    in.fencePoint = &inFencePt;

    out.outputBuffer = reinterpret_cast<NvOFGPUBufferHandle>(hFlow_);
    out.outputCostBuffer = reinterpret_cast<NvOFGPUBufferHandle>(hCost_);
    out.bwdOutputBuffer = reinterpret_cast<NvOFGPUBufferHandle>(hReverseFlow_);
    out.bwdOutputCostBuffer = reinterpret_cast<NvOFGPUBufferHandle>(hReverseCost_);
    out.fencePoint = &outFencePt;

    const NV_OF_STATUS st = fn_->list.nvOFExecuteD3D12(reinterpret_cast<NvOFHandle>(ofHandle_), &in, &out);
    if (st != NV_OF_SUCCESS) {
        log::error("nvof", std::format("nvOFExecuteD3D12 failed st={}", static_cast<int>(st)));
        return false;
    }
    ++executeCount_;
    ++nextOutValue_;
    return true;
}

bool NvOfSession::unregisterAll() {
    if (!fn_ || !fn_->list.nvOFUnregisterResourceD3D12) return true;
    bool allOk = true;
    auto unreg = [&](void*& h) {
        if (!h) return;
        NV_OF_UNREGISTER_RESOURCE_PARAMS_D3D12 p{reinterpret_cast<NvOFGPUBufferHandle>(h)};
        if (fn_->list.nvOFUnregisterResourceD3D12(&p) == NV_OF_SUCCESS) h = nullptr;
        else allOk = false;
    };
    unreg(hReverseCost_);
    unreg(hReverseFlow_);
    unreg(hCost_);
    unreg(hFlow_);
    unreg(hInputB_);
    unreg(hInputA_);
    return allOk;
}

void NvOfSession::shutdown() {
    unregisterAll();
    if (ofHandle_ && fn_ && fn_->list.nvOFDestroy) {
        fn_->list.nvOFDestroy(reinterpret_cast<NvOFHandle>(ofHandle_));
        ofHandle_ = nullptr;
    }
    delete fn_;
    fn_ = nullptr;
    if (dll_) {
        FreeLibrary(static_cast<HMODULE>(dll_));
        dll_ = nullptr;
    }
    initialized_ = false;
    inFence_ = outFence_ = nullptr;
}

} // namespace gta60::nvof

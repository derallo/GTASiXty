#pragma once
#include <d3d12.h>
#include <cstdint>
#include <vector>

namespace gta60::nvof {

class NvOfSession {
public:
    NvOfSession() = default;
    ~NvOfSession();

    NvOfSession(const NvOfSession&) = delete;
    NvOfSession& operator=(const NvOfSession&) = delete;

    struct Desc {
        uint32_t width = 0;
        uint32_t height = 0;
        ID3D12Fence* inFence = nullptr;
        ID3D12Fence* outFence = nullptr;
        uint32_t quality = 0; // 0=fast, 1=medium, 2=slow
        uint32_t gridSize = 4;
        ID3D12Resource* reverseFlow = nullptr; // R16G16_SINT
        ID3D12Resource* reverseCost = nullptr; // R8_UINT
    };

    struct Caps {
        bool queried = false;
        std::vector<uint32_t> supportedGrids;
        uint32_t minWidth = 0, maxWidth = 0, minHeight = 0, maxHeight = 0;
        uint32_t selectedGrid = 0;
    };

    bool initialize(ID3D12Device* device,
                    ID3D12Resource* inputA,
                    ID3D12Resource* inputB,
                    ID3D12Resource* flowOut,
                    ID3D12Resource* costOut,
                    const Desc& desc);

    // Executes optical flow:
    //   swapInputs=false: inputFrame = inputB (current), referenceFrame = inputA (previous).
    //   swapInputs=true:  inputFrame = inputA (current), referenceFrame = inputB (previous).
    // Vectors in flowOut point current -> previous; reverseFlow points previous -> current.
    bool execute(uint64_t inValue, bool swapInputs = false);

    // Safe teardown order: unregisterAll() BEFORE releasing registered ID3D12Resources, then shutdown().
    bool unregisterAll();
    void shutdown();

    bool initialized() const { return initialized_; }
    uint32_t gridWidth() const { return gridW_; }
    uint32_t gridHeight() const { return gridH_; }
    uint64_t nextOutValue() const { return nextOutValue_; }
    uint64_t lastOutValue() const { return nextOutValue_ > 1 ? nextOutValue_ - 1 : 0; }
    uint64_t executeCount() const { return executeCount_; }
    const Caps& caps() const { return caps_; }

private:
    struct FnList;
    void* dll_ = nullptr;
    FnList* fn_ = nullptr;
    void* ofHandle_ = nullptr;
    void* hInputA_ = nullptr;
    void* hInputB_ = nullptr;
    void* hFlow_ = nullptr;
    void* hCost_ = nullptr;
    void* hReverseFlow_ = nullptr;
    void* hReverseCost_ = nullptr;
    ID3D12Fence* inFence_ = nullptr;
    ID3D12Fence* outFence_ = nullptr;
    uint64_t nextOutValue_ = 1;
    uint64_t executeCount_ = 0;
    bool initialized_ = false;
    uint32_t gridW_ = 0, gridH_ = 0;
    Caps caps_;
};

} // namespace gta60::nvof

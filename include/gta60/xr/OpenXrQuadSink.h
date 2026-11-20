#pragma once
#include "gta60/pipeline/D3D12Context.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace gta60::xr {

struct OpenXrProbeResult {
    bool loaderAvailable = false;
    bool d3d12ExtensionSupported = false;
    bool instanceCreated = false;
    bool headsetFound = false;
    std::string runtimeName;
    LUID requiredAdapterLuid{};
    std::string statusMessage;
};

struct QuadScreenConfig {
    uint32_t width = 2560;
    uint32_t height = 1440;
    float screenWidthMeters = 3.6f;
    float screenDistanceMeters = 2.5f;
};

class OpenXrQuadSink {
public:
    OpenXrQuadSink();
    ~OpenXrQuadSink();
    OpenXrQuadSink(const OpenXrQuadSink&) = delete;
    OpenXrQuadSink& operator=(const OpenXrQuadSink&) = delete;

    // Probes OpenXR runtime, creates instance, checks HMD system, and extracts required D3D12 LUID.
    OpenXrProbeResult probeAndCreateInstance();

    // Binds the D3D12 device/queue, creates session, local space, and swapchain.
    bool attachDevice(ID3D12Device* device, ID3D12CommandQueue* queue, const QuadScreenConfig& cfg);

    // True if OpenXR swapchain format uses BGRA channel ordering.
    bool prefersBgra() const;
    DXGI_FORMAT swapchainFormat() const;

    // Submits one frame texture directly to OpenXR XrCompositionLayerQuad (zero DWM).
    // Returns false if OpenXR session exited or lost.
    bool submitFrame(pipeline::D3D12Context& ctx, ID3D12Resource* frameTex);

    bool isSessionActive() const;
    void recenterDistance(float widthMeters, float distanceMeters);
    void shutdown();

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60::xr

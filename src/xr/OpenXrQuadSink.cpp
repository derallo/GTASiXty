#define XR_USE_GRAPHICS_API_D3D12
#include "gta60/xr/OpenXrQuadSink.h"
#include "gta60/Log.h"

#include <windows.h>
#include <d3d12.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <cstring>
#include <format>
#include <vector>

namespace gta60::xr {

struct OpenXrQuadSink::Impl {
    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace localSpace = XR_NULL_HANDLE;
    XrSwapchain swapchain = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageD3D12KHR> swapchainImages;

    DXGI_FORMAT chosenFormat = DXGI_FORMAT_R8G8B8A8_UNORM_SRGB;
    bool bgra = false;
    QuadScreenConfig cfg{};

    XrSessionState sessionState = XR_SESSION_STATE_UNKNOWN;
    bool sessionRunning = false;
    bool exitRequested = false;

    std::string xrErr(XrResult r) const {
        if (instance != XR_NULL_HANDLE) {
            char buf[XR_MAX_RESULT_STRING_SIZE] = {};
            if (XR_SUCCEEDED(xrResultToString(instance, r, buf))) return buf;
        }
        return std::format("XrResult({})", static_cast<int>(r));
    }

    void pollEvents() {
        if (instance == XR_NULL_HANDLE) return;
        for (;;) {
            XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
            const XrResult r = xrPollEvent(instance, &ev);
            if (r == XR_EVENT_UNAVAILABLE) break;
            if (XR_FAILED(r)) break;

            if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                const auto* sc = reinterpret_cast<const XrEventDataSessionStateChanged*>(&ev);
                sessionState = sc->state;
                log::info("openxr", std::format("session state -> {}", static_cast<int>(sessionState)));
                if (sessionState == XR_SESSION_STATE_READY && !sessionRunning) {
                    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    if (XR_SUCCEEDED(xrBeginSession(session, &bi))) {
                        sessionRunning = true;
                        log::info("openxr", "OpenXR session began (PRIMARY_STEREO)");
                    }
                } else if (sessionState == XR_SESSION_STATE_STOPPING && sessionRunning) {
                    xrEndSession(session);
                    sessionRunning = false;
                } else if (sessionState == XR_SESSION_STATE_EXITING ||
                           sessionState == XR_SESSION_STATE_LOSS_PENDING) {
                    exitRequested = true;
                }
            }
        }
    }
};

OpenXrQuadSink::OpenXrQuadSink() : p_(std::make_unique<Impl>()) {}
OpenXrQuadSink::~OpenXrQuadSink() { shutdown(); }

OpenXrProbeResult OpenXrQuadSink::probeAndCreateInstance() {
    shutdown();
    OpenXrProbeResult res;

    uint32_t extCount = 0;
    XrResult xr = xrEnumerateInstanceExtensionProperties(nullptr, 0, &extCount, nullptr);
    if (XR_FAILED(xr)) {
        res.statusMessage = std::format("xrEnumerateInstanceExtensionProperties failed: {}", int(xr));
        return res;
    }
    res.loaderAvailable = true;

    std::vector<XrExtensionProperties> exts(extCount, {XR_TYPE_EXTENSION_PROPERTIES});
    if (extCount > 0) {
        xrEnumerateInstanceExtensionProperties(nullptr, extCount, &extCount, exts.data());
    }
    for (const auto& e : exts) {
        if (std::strcmp(e.extensionName, XR_KHR_D3D12_ENABLE_EXTENSION_NAME) == 0) {
            res.d3d12ExtensionSupported = true;
            break;
        }
    }
    if (!res.d3d12ExtensionSupported) {
        res.statusMessage = "Active OpenXR runtime does not advertise XR_KHR_D3D12_enable";
        return res;
    }

    const char* enabledExts[] = {XR_KHR_D3D12_ENABLE_EXTENSION_NAME};
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    std::strcpy(ci.applicationInfo.applicationName, "GTASixty");
    ci.applicationInfo.applicationVersion = 1;
    std::strcpy(ci.applicationInfo.engineName, "GTASixty-D3D12");
    ci.applicationInfo.engineVersion = 1;
    ci.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    ci.enabledExtensionCount = 1;
    ci.enabledExtensionNames = enabledExts;

    xr = xrCreateInstance(&ci, &p_->instance);
    if (XR_FAILED(xr) || p_->instance == XR_NULL_HANDLE) {
        res.statusMessage = std::format("xrCreateInstance failed: {}", int(xr));
        return res;
    }
    res.instanceCreated = true;

    XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES};
    if (XR_SUCCEEDED(xrGetInstanceProperties(p_->instance, &ip))) {
        res.runtimeName = ip.runtimeName;
    }

    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    xr = xrGetSystem(p_->instance, &sgi, &p_->systemId);
    if (XR_FAILED(xr) || p_->systemId == XR_NULL_SYSTEM_ID) {
        res.headsetFound = false;
        res.statusMessage = std::format("No active OpenXR HMD found ({}): connect Quest 3 via Virtual Desktop or Quest Link",
                                        p_->xrErr(xr));
        return res;
    }
    res.headsetFound = true;

    PFN_xrGetD3D12GraphicsRequirementsKHR pfnGetReq = nullptr;
    xr = xrGetInstanceProcAddr(p_->instance, "xrGetD3D12GraphicsRequirementsKHR",
                               reinterpret_cast<PFN_xrVoidFunction*>(&pfnGetReq));
    if (XR_FAILED(xr) || !pfnGetReq) {
        res.statusMessage = "Failed to resolve xrGetD3D12GraphicsRequirementsKHR";
        return res;
    }

    XrGraphicsRequirementsD3D12KHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_D3D12_KHR};
    xr = pfnGetReq(p_->instance, p_->systemId, &req);
    if (XR_FAILED(xr)) {
        res.statusMessage = std::format("xrGetD3D12GraphicsRequirementsKHR failed: {}", p_->xrErr(xr));
        return res;
    }

    res.requiredAdapterLuid = req.adapterLuid;
    res.statusMessage = std::format("OpenXR ready: runtime='{}'", res.runtimeName);
    log::info("openxr", res.statusMessage);
    return res;
}

bool OpenXrQuadSink::attachDevice(ID3D12Device* device, ID3D12CommandQueue* queue, const QuadScreenConfig& cfg) {
    if (p_->instance == XR_NULL_HANDLE || p_->systemId == XR_NULL_SYSTEM_ID || !device || !queue) {
        return false;
    }
    p_->cfg = cfg;

    XrGraphicsBindingD3D12KHR binding{XR_TYPE_GRAPHICS_BINDING_D3D12_KHR};
    binding.device = device;
    binding.queue = queue;

    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &binding;
    sci.systemId = p_->systemId;
    XrResult xr = xrCreateSession(p_->instance, &sci, &p_->session);
    if (XR_FAILED(xr) || p_->session == XR_NULL_HANDLE) {
        log::error("openxr", std::format("xrCreateSession failed: {}", p_->xrErr(xr)));
        return false;
    }

    XrReferenceSpaceCreateInfo rsci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rsci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    rsci.poseInReferenceSpace.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
    rsci.poseInReferenceSpace.position = {0.0f, 0.0f, 0.0f};
    xr = xrCreateReferenceSpace(p_->session, &rsci, &p_->localSpace);
    if (XR_FAILED(xr)) {
        log::error("openxr", std::format("xrCreateReferenceSpace failed: {}", p_->xrErr(xr)));
        return false;
    }

    uint32_t fmtCount = 0;
    xrEnumerateSwapchainFormats(p_->session, 0, &fmtCount, nullptr);
    std::vector<int64_t> formats(fmtCount, 0);
    if (fmtCount > 0) xrEnumerateSwapchainFormats(p_->session, fmtCount, &fmtCount, formats.data());

    const int64_t preferred[] = {
        DXGI_FORMAT_R8G8B8A8_UNORM,
        DXGI_FORMAT_R8G8B8A8_UNORM_SRGB,
        DXGI_FORMAT_B8G8R8A8_UNORM,
        DXGI_FORMAT_B8G8R8A8_UNORM_SRGB,
    };
    bool foundFmt = false;
    for (int64_t cand : preferred) {
        if (std::find(formats.begin(), formats.end(), cand) != formats.end()) {
            p_->chosenFormat = static_cast<DXGI_FORMAT>(cand);
            p_->bgra = (cand == DXGI_FORMAT_B8G8R8A8_UNORM || cand == DXGI_FORMAT_B8G8R8A8_UNORM_SRGB);
            foundFmt = true;
            break;
        }
    }
    if (!foundFmt && !formats.empty()) {
        p_->chosenFormat = static_cast<DXGI_FORMAT>(formats[0]);
    }

    XrSwapchainCreateInfo swci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    swci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT;
    swci.format = p_->chosenFormat;
    swci.sampleCount = 1;
    swci.width = cfg.width;
    swci.height = cfg.height;
    swci.faceCount = 1;
    swci.arraySize = 1;
    swci.mipCount = 1;

    xr = xrCreateSwapchain(p_->session, &swci, &p_->swapchain);
    if (XR_FAILED(xr) || p_->swapchain == XR_NULL_HANDLE) {
        log::error("openxr", std::format("xrCreateSwapchain {}x{} failed: {}", cfg.width, cfg.height, p_->xrErr(xr)));
        return false;
    }

    uint32_t imgCount = 0;
    xrEnumerateSwapchainImages(p_->swapchain, 0, &imgCount, nullptr);
    p_->swapchainImages.resize(imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_D3D12_KHR});
    xrEnumerateSwapchainImages(p_->swapchain, imgCount, &imgCount,
                               reinterpret_cast<XrSwapchainImageBaseHeader*>(p_->swapchainImages.data()));

    log::info("openxr", std::format("OpenXR Quad swapchain ready: {}x{} images={} fmt={} bgra={}",
                                    cfg.width, cfg.height, imgCount, int(p_->chosenFormat), p_->bgra));
    return true;
}

bool OpenXrQuadSink::prefersBgra() const { return p_->bgra; }
DXGI_FORMAT OpenXrQuadSink::swapchainFormat() const { return p_->chosenFormat; }
bool OpenXrQuadSink::isSessionActive() const { return p_->session != XR_NULL_HANDLE && !p_->exitRequested; }

void OpenXrQuadSink::recenterDistance(float widthMeters, float distanceMeters) {
    p_->cfg.screenWidthMeters = widthMeters;
    p_->cfg.screenDistanceMeters = distanceMeters;
}

bool OpenXrQuadSink::submitFrame(pipeline::D3D12Context& ctx, ID3D12Resource* frameTex) {
    if (p_->session == XR_NULL_HANDLE || p_->exitRequested) return false;

    p_->pollEvents();
    if (p_->exitRequested) return false;
    if (!p_->sessionRunning) return true;

    XrFrameWaitInfo wi{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(p_->session, &wi, &fs))) return false;

    XrFrameBeginInfo bi{XR_TYPE_FRAME_BEGIN_INFO};
    if (XR_FAILED(xrBeginFrame(p_->session, &bi))) return false;

    bool haveLayer = false;
    XrCompositionLayerQuad quad{XR_TYPE_COMPOSITION_LAYER_QUAD};

    if (fs.shouldRender && frameTex) {
        uint32_t imgIndex = 0;
        XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        if (XR_SUCCEEDED(xrAcquireSwapchainImage(p_->swapchain, &ai, &imgIndex))) {
            XrSwapchainImageWaitInfo swi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
            swi.timeout = XR_INFINITE_DURATION;
            if (XR_SUCCEEDED(xrWaitSwapchainImage(p_->swapchain, &swi))) {
                ID3D12Resource* dstTex = p_->swapchainImages[imgIndex].texture;
                ID3D12GraphicsCommandList* list = nullptr;
                if (ctx.beginCommandList(&list)) {
                    ctx.transition(list, frameTex, D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COPY_SOURCE);
                    ctx.transition(list, dstTex, D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_COPY_DEST);
                    list->CopyResource(dstTex, frameTex);
                    ctx.transition(list, dstTex, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_RENDER_TARGET);
                    ctx.transition(list, frameTex, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_COMMON);
                    const uint64_t fVal = ctx.submitCommandList();
                    ctx.waitForFenceValue(ctx.fence(), fVal);
                }
                XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
                xrReleaseSwapchainImage(p_->swapchain, &ri);

                const float aspect = static_cast<float>(p_->cfg.height) / static_cast<float>(p_->cfg.width);
                quad.layerFlags = 0;
                quad.space = p_->localSpace;
                quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                quad.subImage.swapchain = p_->swapchain;
                quad.subImage.imageRect = {{0, 0}, {static_cast<int32_t>(p_->cfg.width), static_cast<int32_t>(p_->cfg.height)}};
                quad.subImage.imageArrayIndex = 0;
                quad.pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
                quad.pose.position = {0.0f, 0.0f, -p_->cfg.screenDistanceMeters};
                quad.size = {p_->cfg.screenWidthMeters, p_->cfg.screenWidthMeters * aspect};
                haveLayer = true;
            }
        }
    }

    const XrCompositionLayerBaseHeader* layers[] = {reinterpret_cast<const XrCompositionLayerBaseHeader*>(&quad)};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.displayTime = fs.predictedDisplayTime;
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    ei.layerCount = haveLayer ? 1u : 0u;
    ei.layers = haveLayer ? layers : nullptr;

    return XR_SUCCEEDED(xrEndFrame(p_->session, &ei));
}

void OpenXrQuadSink::shutdown() {
    if (p_->swapchain != XR_NULL_HANDLE) {
        xrDestroySwapchain(p_->swapchain);
        p_->swapchain = XR_NULL_HANDLE;
    }
    p_->swapchainImages.clear();
    if (p_->localSpace != XR_NULL_HANDLE) {
        xrDestroySpace(p_->localSpace);
        p_->localSpace = XR_NULL_HANDLE;
    }
    if (p_->session != XR_NULL_HANDLE) {
        if (p_->sessionRunning) {
            xrEndSession(p_->session);
            p_->sessionRunning = false;
        }
        xrDestroySession(p_->session);
        p_->session = XR_NULL_HANDLE;
    }
    if (p_->instance != XR_NULL_HANDLE) {
        xrDestroyInstance(p_->instance);
        p_->instance = XR_NULL_HANDLE;
    }
    p_->systemId = XR_NULL_SYSTEM_ID;
    p_->sessionState = XR_SESSION_STATE_UNKNOWN;
    p_->exitRequested = false;
}

} // namespace gta60::xr

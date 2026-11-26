// hand_tracker_jni.cpp — thin JNI bridge to OpenXR XR_EXT_hand_tracking for GTA-Sixty Spatial.
//
// Why this exists
// ---------------
// Meta Spatial SDK 0.15.0 exposes the live OpenXR instance/session/getInstanceProcAddr handles on
// `com.meta.spatial.runtime.Scene`, so a ~500 line C++ shim can call
// `xrCreateHandTrackerEXT` + `xrLocateHandJointsEXT` directly. This gives us all 26 hand joints per
// hand with per-joint validity flags and joint radii, works even when
// `avatarSystem.setShowHands(false)` hides Meta's translucent hand meshes, and never needs the
// BODY_TRACKING permission or consent prompts.
//
// We deliberately do NOT link against libopenxr_loader.so: every entry point is resolved through the
// `xrGetInstanceProcAddr` pointer that the Spatial SDK already owns, so the resulting .so has zero
// external dependencies and can never pull a second OpenXR loader into the process.
//
// Threading
// ---------
// All functions are called from the Spatial `onSceneTick` thread. No locks are taken; the state is a
// handful of XrHandles touched only from that one thread after `nativeInit`.
//
// Output layout (zero per-frame allocations on the Kotlin side, arrays are reused)
// -------------------------------------------------------------------------------
//   poses : float[2 * 26 * 8]  -> per joint [px,py,pz, qx,qy,qz,qw, radius]
//   flags : int[2 * 26]        -> XrSpaceLocationFlags bitmask per joint
//   hand 0 = LEFT, hand 1 = RIGHT; joint indices follow XrHandJointEXT (PALM=0, WRIST=1, ...).
//
// Time base
// ---------
// `XrTime` is CLOCK_MONOTONIC nanoseconds on the Meta runtime, exactly what `System.nanoTime()`
// reports on Android, so Kotlin passes `System.nanoTime()` straight through. If the runtime rejects
// that time we retry a few milliseconds in the past before giving up.

#include <jni.h>
#include <android/log.h>
#include <time.h>

#include <cstdint>
#include <cstring>

#include "openxr.h"

// ---------------------------------------------------------------------------------------------
// XR_META_simultaneous_hands_and_controllers ("multimodal")
//
// The vendored openxr.h is 1.0.34 and predates this Meta extension, so declare just the pieces we
// need. The structure-type values are from KhronosGroup/OpenXR-SDK main
// (`XR_TYPE_SYSTEM_SIMULTANEOUS_HANDS_AND_CONTROLLERS_PROPERTIES_META = 1000532001`,
// `XR_TYPE_SIMULTANEOUS_HANDS_AND_CONTROLLERS_TRACKING_RESUME_INFO_META = 1000532002`).
//
// Why this matters: without multimodal, Horizon OS runs the app in the classic "either hands or
// controllers" mode, so as soon as the Touch controllers are tracked (even just resting next to the
// user) the runtime stops delivering XR_EXT_hand_tracking joints — every locate call returns
// isActive=false with all-invalid joints. Calling
// xrResumeSimultaneousHandsAndControllersTrackingMETA overrides the auto-transition logic and keeps
// hands live while the controllers are merely detected.
// ---------------------------------------------------------------------------------------------
#ifndef XR_META_simultaneous_hands_and_controllers
#define XR_META_simultaneous_hands_and_controllers 1
#endif
#ifndef XR_TYPE_SYSTEM_SIMULTANEOUS_HANDS_AND_CONTROLLERS_PROPERTIES_META
#define XR_TYPE_SYSTEM_SIMULTANEOUS_HANDS_AND_CONTROLLERS_PROPERTIES_META ((XrStructureType)1000532001)
#endif
#ifndef XR_TYPE_SIMULTANEOUS_HANDS_AND_CONTROLLERS_TRACKING_RESUME_INFO_META
#define XR_TYPE_SIMULTANEOUS_HANDS_AND_CONTROLLERS_TRACKING_RESUME_INFO_META ((XrStructureType)1000532002)
#endif

struct XrSystemSimultaneousHandsAndControllersPropertiesMETA {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    XrBool32 supportsSimultaneousHandsAndControllers;
};

struct XrSimultaneousHandsAndControllersTrackingResumeInfoMETA {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
};

typedef XrResult(XRAPI_PTR* PFN_xrResumeSimultaneousHandsAndControllersTrackingMETA)(
        XrSession session,
        const XrSimultaneousHandsAndControllersTrackingResumeInfoMETA* resumeInfo);

typedef XrResult(XRAPI_PTR* PFN_xrConvertTimespecTimeToTimeKHR)(
        XrInstance instance,
        const struct timespec* timespecTime,
        XrTime* time);

#define LOG_TAG "GTA60Hands"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kJointCount = 26;             // XR_HAND_JOINT_MAX_ENUM_EXT == 26 in OpenXR 1.0
constexpr int kFloatsPerJoint = 8;          // pos(3) + quat(4) + radius(1)
constexpr int kHands = 2;
constexpr int kSpaceCount = 3;              // 0 = STAGE, 1 = LOCAL (head-at-start), 2 = LOCAL_FLOOR

struct SpaceSlot {
    XrSpace handle = XR_NULL_HANDLE;
    int referenceType = -1;
    const char* label = "";
};

struct State {
    bool initialized = false;
    bool extensionsReady = false;

    XrInstance instance = XR_NULL_HANDLE;
    XrSession session = XR_NULL_HANDLE;

    PFN_xrGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_xrCreateReferenceSpace createReferenceSpace = nullptr;
    PFN_xrDestroySpace destroySpace = nullptr;
    PFN_xrLocateSpace locateSpace = nullptr;
    PFN_xrCreateHandTrackerEXT createHandTracker = nullptr;
    PFN_xrDestroyHandTrackerEXT destroyHandTracker = nullptr;
    PFN_xrLocateHandJointsEXT locateHandJoints = nullptr;
    PFN_xrResumeSimultaneousHandsAndControllersTrackingMETA resumeSimultaneous = nullptr;
    PFN_xrGetSystem getSystem = nullptr;
    PFN_xrGetSystemProperties getSystemProperties = nullptr;
    PFN_xrConvertTimespecTimeToTimeKHR convertTimespec = nullptr;

    XrHandTrackerEXT trackers[kHands] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    SpaceSlot spaces[kSpaceCount];
    SpaceSlot viewSpace;

    // 0 = auto/unprobed, 1 = KHR convertTimespec, 2 = CLOCK_MONOTONIC, 3 = CLOCK_BOOTTIME
    int clockMode = 0;
    bool clockProbed = false;

    // Scratch buffers — reused every frame so a locate() call performs no heap allocations.
    XrHandJointLocationEXT joints[kHands][kJointCount];
    XrHandJointLocationsEXT locations[kHands];

    int lastError = 0;
    int locateCalls = 0;
    int locateFailures = 0;
    int resolvedTimeOffsetNs = 0;
    int multimodalResult = -999;
};

State g_state;

int64_t monotonicNanos() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

int64_t boottimeNanos() {
    struct timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

XrTime queryClockCandidate(int mode) {
    if (mode == 1 && g_state.convertTimespec != nullptr && g_state.instance != XR_NULL_HANDLE) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        XrTime t = 0;
        if (XR_SUCCEEDED(g_state.convertTimespec(g_state.instance, &ts, &t)) && t > 0) {
            return t;
        }
        return 0;
    }
    if (mode == 3) {
        return static_cast<XrTime>(boottimeNanos());
    }
    return static_cast<XrTime>(monotonicNanos());
}

void probeXrTimeClock() {
    if (g_state.locateSpace == nullptr || g_state.viewSpace.handle == XR_NULL_HANDLE) {
        return;
    }
    XrSpace base = g_state.spaces[0].handle != XR_NULL_HANDLE ? g_state.spaces[0].handle
                                                              : g_state.spaces[1].handle;
    if (base == XR_NULL_HANDLE) return;

    int bestMode = 0;
    const char* names[4] = {"NONE", "KHR", "MONO", "BOOT"};
    for (int m = 1; m <= 3; ++m) {
        XrTime t = queryClockCandidate(m);
        if (t <= 0) {
            LOGI("clock probe [%s]: unavailable", names[m]);
            continue;
        }
        XrSpaceLocation loc{};
        loc.type = XR_TYPE_SPACE_LOCATION;
        loc.next = nullptr;
        XrResult r = g_state.locateSpace(g_state.viewSpace.handle, base, t, &loc);
        bool posValid = XR_SUCCEEDED(r) && ((loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0);
        bool posTracked = XR_SUCCEEDED(r) && ((loc.locationFlags & XR_SPACE_LOCATION_POSITION_TRACKED_BIT) != 0);
        LOGI("clock probe [%s]: t=%lld rc=%d flags=0x%llx valid=%d tracked=%d view=(%+.3f,%+.3f,%+.3f)",
             names[m], static_cast<long long>(t), static_cast<int>(r),
             static_cast<unsigned long long>(loc.locationFlags),
             posValid ? 1 : 0, posTracked ? 1 : 0,
             loc.pose.position.x, loc.pose.position.y, loc.pose.position.z);
        if (bestMode == 0 && posTracked) {
            bestMode = m;
        }
    }
    if (bestMode == 0) {
        // Fallback: accept POSITION_VALID even if not tracked yet (headset resting on desk)
        for (int m = 1; m <= 3; ++m) {
            XrTime t = queryClockCandidate(m);
            if (t <= 0) continue;
            XrSpaceLocation loc{};
            loc.type = XR_TYPE_SPACE_LOCATION;
            XrResult r = g_state.locateSpace(g_state.viewSpace.handle, base, t, &loc);
            if (XR_SUCCEEDED(r) && ((loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0)) {
                bestMode = m;
                break;
            }
        }
    }
    if (bestMode != 0) {
        g_state.clockMode = bestMode;
        g_state.clockProbed = true;
        LOGI("Selected XrTime clock mode: %s (mode=%d)", names[bestMode], bestMode);
    }
}

template <typename Fn>
Fn resolve(const char* name) {
    if (g_state.getInstanceProcAddr == nullptr || g_state.instance == XR_NULL_HANDLE) {
        return nullptr;
    }
    PFN_xrVoidFunction fn = nullptr;
    XrResult r = g_state.getInstanceProcAddr(
            g_state.instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&fn));
    if (XR_FAILED(r) || fn == nullptr) {
        return nullptr;
    }
    return reinterpret_cast<Fn>(fn);
}

void releaseSpace(SpaceSlot& slot) {
    if (slot.handle != XR_NULL_HANDLE && g_state.destroySpace != nullptr) {
        g_state.destroySpace(slot.handle);
    }
    slot.handle = XR_NULL_HANDLE;
    slot.referenceType = -1;
}

void releaseAll() {
    if (g_state.destroyHandTracker != nullptr) {
        for (int i = 0; i < kHands; ++i) {
            if (g_state.trackers[i] != XR_NULL_HANDLE) {
                g_state.destroyHandTracker(g_state.trackers[i]);
            }
            g_state.trackers[i] = XR_NULL_HANDLE;
        }
    }
    for (int i = 0; i < kSpaceCount; ++i) {
        releaseSpace(g_state.spaces[i]);
    }
    releaseSpace(g_state.viewSpace);
    g_state.clockMode = 0;
    g_state.clockProbed = false;
    g_state.extensionsReady = false;
}

bool createSpace(SpaceSlot& slot, XrReferenceSpaceType type, const char* label) {
    if (g_state.createReferenceSpace == nullptr) {
        return false;
    }
    XrReferenceSpaceCreateInfo info{};
    info.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    info.next = nullptr;
    info.referenceSpaceType = type;
    info.poseInReferenceSpace.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
    info.poseInReferenceSpace.position = {0.0f, 0.0f, 0.0f};
    XrResult r = g_state.createReferenceSpace(g_state.session, &info, &slot.handle);
    if (XR_FAILED(r)) {
        slot.handle = XR_NULL_HANDLE;
        slot.referenceType = -1;
        slot.label = label;
        LOGW("xrCreateReferenceSpace(%s) failed: %d", label, static_cast<int>(r));
        return false;
    }
    slot.referenceType = static_cast<int>(type);
    slot.label = label;
    LOGI("Created reference space %s (XrSpace=%p)", label, reinterpret_cast<void*>(slot.handle));
    return true;
}

bool createTracker(int handIndex, XrHandEXT hand, const char* label) {
    if (g_state.createHandTracker == nullptr) {
        return false;
    }
    XrHandTrackerCreateInfoEXT info{};
    info.type = XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT;
    info.next = nullptr;
    info.hand = hand;
    info.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
    XrResult r = g_state.createHandTracker(g_state.session, &info, &g_state.trackers[handIndex]);
    if (XR_FAILED(r)) {
        g_state.trackers[handIndex] = XR_NULL_HANDLE;
        g_state.lastError = static_cast<int>(r);
        LOGW("xrCreateHandTrackerEXT(%s) failed: %d", label, static_cast<int>(r));
        return false;
    }
    LOGI("Created %s hand tracker (XrHandTrackerEXT=%p)", label,
         reinterpret_cast<void*>(g_state.trackers[handIndex]));
    return true;
}

}  // namespace

extern "C" {

// Returns a bitmask describing what came up:
//   bit0 = left tracker, bit1 = right tracker, bit2 = STAGE space, bit3 = LOCAL space,
//   bit4 = LOCAL_FLOOR space, bit5 = XR_EXT_hand_tracking resolved.
JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeInit(JNIEnv*, jobject, jlong instance,
                                                   jlong session, jlong getInstanceProcAddr) {
    releaseAll();

    g_state.instance = reinterpret_cast<XrInstance>(static_cast<intptr_t>(instance));
    g_state.session = reinterpret_cast<XrSession>(static_cast<intptr_t>(session));
    g_state.getInstanceProcAddr =
            reinterpret_cast<PFN_xrGetInstanceProcAddr>(static_cast<intptr_t>(getInstanceProcAddr));
    g_state.lastError = 0;
    g_state.locateCalls = 0;
    g_state.locateFailures = 0;
    g_state.resolvedTimeOffsetNs = 0;

    if (g_state.instance == XR_NULL_HANDLE || g_state.session == XR_NULL_HANDLE ||
        g_state.getInstanceProcAddr == nullptr) {
        LOGE("nativeInit: invalid handles instance=%lld session=%lld proc=%lld",
             static_cast<long long>(instance), static_cast<long long>(session),
             static_cast<long long>(getInstanceProcAddr));
        return 0;
    }

    g_state.createReferenceSpace =
            resolve<PFN_xrCreateReferenceSpace>("xrCreateReferenceSpace");
    g_state.destroySpace = resolve<PFN_xrDestroySpace>("xrDestroySpace");
    g_state.locateSpace = resolve<PFN_xrLocateSpace>("xrLocateSpace");
    g_state.convertTimespec =
            resolve<PFN_xrConvertTimespecTimeToTimeKHR>("xrConvertTimespecTimeToTimeKHR");
    g_state.createHandTracker = resolve<PFN_xrCreateHandTrackerEXT>("xrCreateHandTrackerEXT");
    g_state.destroyHandTracker = resolve<PFN_xrDestroyHandTrackerEXT>("xrDestroyHandTrackerEXT");
    g_state.locateHandJoints = resolve<PFN_xrLocateHandJointsEXT>("xrLocateHandJointsEXT");
    g_state.resumeSimultaneous =
            resolve<PFN_xrResumeSimultaneousHandsAndControllersTrackingMETA>(
                    "xrResumeSimultaneousHandsAndControllersTrackingMETA");
    g_state.getSystem = resolve<PFN_xrGetSystem>("xrGetSystem");
    g_state.getSystemProperties = resolve<PFN_xrGetSystemProperties>("xrGetSystemProperties");
    LOGI("multimodal: resume=%p getSystem=%p getSystemProperties=%p",
         reinterpret_cast<void*>(g_state.resumeSimultaneous),
         reinterpret_cast<void*>(g_state.getSystem),
         reinterpret_cast<void*>(g_state.getSystemProperties));

    int mask = 0;
    if (g_state.createHandTracker != nullptr && g_state.locateHandJoints != nullptr &&
        g_state.destroyHandTracker != nullptr) {
        mask |= 0x20;
    } else {
        // The runtime only hands out these entry points when XR_EXT_hand_tracking was enabled on the
        // XrInstance that Spatial SDK created for us. Declaring `oculus.software.handtracking` in the
        // manifest (and hand tracking enabled on the headset) is what makes that happen.
        LOGW("XR_EXT_hand_tracking entry points unavailable (create=%p locate=%p)",
             reinterpret_cast<void*>(g_state.createHandTracker),
             reinterpret_cast<void*>(g_state.locateHandJoints));
        g_state.initialized = false;
        return 0;
    }

    for (int i = 0; i < kHands; ++i) {
        std::memset(&g_state.locations[i], 0, sizeof(XrHandJointLocationsEXT));
        std::memset(g_state.joints[i], 0, sizeof(XrHandJointLocationEXT) * kJointCount);
        g_state.locations[i].type = XR_TYPE_HAND_JOINT_LOCATIONS_EXT;
        g_state.locations[i].next = nullptr;
        g_state.locations[i].jointCount = kJointCount;
        g_state.locations[i].jointLocations = g_state.joints[i];
    }

    if (createTracker(0, XR_HAND_LEFT_EXT, "left")) mask |= 0x01;
    if (createTracker(1, XR_HAND_RIGHT_EXT, "right")) mask |= 0x02;

    if (createSpace(g_state.spaces[0], XR_REFERENCE_SPACE_TYPE_STAGE, "STAGE")) mask |= 0x04;
    if (createSpace(g_state.spaces[1], XR_REFERENCE_SPACE_TYPE_LOCAL, "LOCAL")) mask |= 0x08;
    if (createSpace(g_state.spaces[2], XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT, "LOCAL_FLOOR")) {
        mask |= 0x10;
    }
    createSpace(g_state.viewSpace, XR_REFERENCE_SPACE_TYPE_VIEW, "VIEW");
    probeXrTimeClock();

    // If the app only gets one useful space, fall back to whatever exists so hand tracking still
    // works (the Kotlin layer logs which space it settled on).
    g_state.extensionsReady = (mask & 0x03) != 0 && (mask & 0x1C) != 0;
    g_state.initialized = g_state.extensionsReady;

    LOGI("nativeInit mask=0x%02X ready=%d", mask, g_state.initialized ? 1 : 0);
    return mask;
}

JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeRecreateTrackers(JNIEnv*, jobject) {
    // Some runtimes only start delivering hand data once a tracker is created while the session is
    // already focused. If our first trackers were made too early they stay permanently inactive, so
    // the caller recreates them when several probes in a row saw no valid joints.
    if (g_state.destroyHandTracker != nullptr) {
        for (int i = 0; i < kHands; ++i) {
            if (g_state.trackers[i] != XR_NULL_HANDLE) {
                g_state.destroyHandTracker(g_state.trackers[i]);
            }
            g_state.trackers[i] = XR_NULL_HANDLE;
        }
    }
    int mask = 0;
    if (createTracker(0, XR_HAND_LEFT_EXT, "left")) mask |= 0x01;
    if (createTracker(1, XR_HAND_RIGHT_EXT, "right")) mask |= 0x02;
    LOGI("nativeRecreateTrackers mask=0x%02X", mask);
    return mask;
}

// 1 = support reported, 0 = not supported, negative = could not query.
JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeMultimodalSupported(JNIEnv*, jobject) {
    if (g_state.instance == XR_NULL_HANDLE) return -1;
    if (g_state.getSystem == nullptr) {
        g_state.getSystem = resolve<PFN_xrGetSystem>("xrGetSystem");
    }
    if (g_state.getSystemProperties == nullptr) {
        g_state.getSystemProperties = resolve<PFN_xrGetSystemProperties>("xrGetSystemProperties");
    }
    if (g_state.getSystem == nullptr || g_state.getSystemProperties == nullptr) return -2;

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.next = nullptr;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrResult r = g_state.getSystem(g_state.instance, &systemInfo, &systemId);
    if (XR_FAILED(r)) {
        LOGW("xrGetSystem failed: %d", static_cast<int>(r));
        return -3;
    }

    XrSystemSimultaneousHandsAndControllersPropertiesMETA mm{};
    mm.type = XR_TYPE_SYSTEM_SIMULTANEOUS_HANDS_AND_CONTROLLERS_PROPERTIES_META;
    mm.next = nullptr;
    mm.supportsSimultaneousHandsAndControllers = XR_FALSE;

    XrSystemProperties props{};
    props.type = XR_TYPE_SYSTEM_PROPERTIES;
    props.next = &mm;
    r = g_state.getSystemProperties(g_state.instance, systemId, &props);
    if (XR_FAILED(r)) {
        LOGW("xrGetSystemProperties failed: %d", static_cast<int>(r));
        return -4;
    }
    LOGI("multimodal supported=%d (system=%s)",
         static_cast<int>(mm.supportsSimultaneousHandsAndControllers), props.systemName);
    return mm.supportsSimultaneousHandsAndControllers == XR_TRUE ? 1 : 0;
}

/**
 * Turns on simultaneous hands + controllers tracking. This is the switch that stops Horizon OS from
 * suspending XR_EXT_hand_tracking while the Touch controllers are merely detected.
 *
 * @return the XrResult (0 == XR_SUCCESS), or -1 when the extension entry point is unavailable.
 */
JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeEnableMultimodal(JNIEnv*, jobject) {
    if (g_state.session == XR_NULL_HANDLE) return -2;
    if (g_state.resumeSimultaneous == nullptr) {
        g_state.resumeSimultaneous =
                resolve<PFN_xrResumeSimultaneousHandsAndControllersTrackingMETA>(
                        "xrResumeSimultaneousHandsAndControllersTrackingMETA");
    }
    if (g_state.resumeSimultaneous == nullptr) {
        LOGW("multimodal: xrResumeSimultaneousHandsAndControllersTrackingMETA unavailable");
        g_state.multimodalResult = -1;
        return -1;
    }
    XrSimultaneousHandsAndControllersTrackingResumeInfoMETA info{};
    info.type = XR_TYPE_SIMULTANEOUS_HANDS_AND_CONTROLLERS_TRACKING_RESUME_INFO_META;
    info.next = nullptr;
    XrResult r = g_state.resumeSimultaneous(g_state.session, &info);
    g_state.multimodalResult = static_cast<int>(r);
    LOGI("xrResumeSimultaneousHandsAndControllersTrackingMETA -> %d", static_cast<int>(r));
    return static_cast<jint>(r);
}

JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeCreateSpace(JNIEnv*, jobject, jint spaceSel) {
    if (spaceSel < 0 || spaceSel >= kSpaceCount) {
        return -1;
    }
    SpaceSlot& slot = g_state.spaces[spaceSel];
    if (slot.handle != XR_NULL_HANDLE) {
        return 1;
    }
    static const XrReferenceSpaceType kTypes[kSpaceCount] = {
            XR_REFERENCE_SPACE_TYPE_STAGE,
            XR_REFERENCE_SPACE_TYPE_LOCAL,
            XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT,
    };
    static const char* kLabels[kSpaceCount] = {"STAGE", "LOCAL", "LOCAL_FLOOR"};
    if (g_state.createReferenceSpace == nullptr) {
        g_state.createReferenceSpace =
                resolve<PFN_xrCreateReferenceSpace>("xrCreateReferenceSpace");
    }
    if (g_state.destroySpace == nullptr) {
        g_state.destroySpace = resolve<PFN_xrDestroySpace>("xrDestroySpace");
    }
    return createSpace(slot, kTypes[spaceSel], kLabels[spaceSel]) ? 1 : 0;
}

// Locates both hands (and the OpenXR VIEW space at the exact same timestamp) and writes packed
// joint poses into the caller's arrays.
// Returns 0 on success, a negative XrResult-ish code on failure. `statusOut` (int[2]) receives:
//   bit0 = isActive for that hand, bit1 = at least one joint had POSITION_VALID,
//   bit2 (on statusOut[0]) = viewPoseOut[0..6] (px,py,pz,qx,qy,qz,qw) is valid.
JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeLocate(JNIEnv* env, jobject, jfloatArray posesOut,
                                                     jintArray flagsOut, jintArray statusOut,
                                                     jfloatArray viewPoseOut,
                                                     jint spaceSel, jlong timeNanos) {
    if (!g_state.initialized || g_state.locateHandJoints == nullptr) {
        return -100;
    }
    if (spaceSel < 0 || spaceSel >= kSpaceCount) {
        return -101;
    }
    XrSpace baseSpace = g_state.spaces[spaceSel].handle;
    if (baseSpace == XR_NULL_HANDLE) {
        return -102;
    }
    if (posesOut == nullptr || env->GetArrayLength(posesOut) < kHands * kJointCount * kFloatsPerJoint) {
        return -103;
    }

    if (!g_state.clockProbed && (g_state.locateCalls % 120) == 0) {
        probeXrTimeClock();
    }

    int64_t timeNs = 0;
    if (g_state.clockMode != 0) {
        timeNs = static_cast<int64_t>(queryClockCandidate(g_state.clockMode));
    }
    if (timeNs <= 0) {
        timeNs = static_cast<int64_t>(timeNanos);
        if (timeNs <= 0) {
            timeNs = monotonicNanos();
        }
    }
    timeNs += g_state.resolvedTimeOffsetNs;

    XrResult result = XR_SUCCESS;
    int64_t usedTimeNs = timeNs;
    for (int attempt = 0; attempt < 4; ++attempt) {
        int64_t t = timeNs - static_cast<int64_t>(attempt) * 2000000LL;  // step back 2 ms per retry
        bool failed = false;
        for (int h = 0; h < kHands; ++h) {
            if (g_state.trackers[h] == XR_NULL_HANDLE) {
                g_state.locations[h].isActive = XR_FALSE;
                continue;
            }
            XrHandJointsLocateInfoEXT info{};
            info.type = XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT;
            info.next = nullptr;
            info.baseSpace = baseSpace;
            info.time = static_cast<XrTime>(t);
            result = g_state.locateHandJoints(g_state.trackers[h], &info, &g_state.locations[h]);
            if (XR_FAILED(result)) {
                failed = true;
                break;
            }
        }
        if (!failed) {
            usedTimeNs = t;
            if (attempt > 0) {
                // Remember the correction so later frames hit on the first try.
                g_state.resolvedTimeOffsetNs -= attempt * 2000000;
                LOGI("xrLocateHandJointsEXT accepted time offset %d ns", g_state.resolvedTimeOffsetNs);
            }
            g_state.locateCalls++;
            break;
        }
        if (attempt == 3) {
            g_state.locateFailures++;
            g_state.lastError = static_cast<int>(result);
            if (g_state.locateFailures <= 3 || (g_state.locateFailures % 600) == 0) {
                LOGW("xrLocateHandJointsEXT failed (count=%d): %d", g_state.locateFailures,
                     static_cast<int>(result));
            }
            // Always report a negative code so the Kotlin side can tell failures from success.
            return -1000 + static_cast<int>(result);
        }
    }

    bool viewValid = false;
    XrSpaceLocation viewLoc{};
    viewLoc.type = XR_TYPE_SPACE_LOCATION;
    viewLoc.next = nullptr;
    if (viewPoseOut != nullptr && g_state.locateSpace != nullptr &&
        g_state.viewSpace.handle != XR_NULL_HANDLE && env->GetArrayLength(viewPoseOut) >= 7) {
        XrResult vr = g_state.locateSpace(g_state.viewSpace.handle, baseSpace,
                                          static_cast<XrTime>(usedTimeNs), &viewLoc);
        if (XR_SUCCEEDED(vr) &&
            (viewLoc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
            (viewLoc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {
            viewValid = true;
            jfloat vp[7] = {
                    viewLoc.pose.position.x,
                    viewLoc.pose.position.y,
                    viewLoc.pose.position.z,
                    viewLoc.pose.orientation.x,
                    viewLoc.pose.orientation.y,
                    viewLoc.pose.orientation.z,
                    viewLoc.pose.orientation.w,
            };
            env->SetFloatArrayRegion(viewPoseOut, 0, 7, vp);
        }
    }

    jfloat* poses = env->GetFloatArrayElements(posesOut, nullptr);
    jint* flags = flagsOut != nullptr ? env->GetIntArrayElements(flagsOut, nullptr) : nullptr;
    jint* status = statusOut != nullptr ? env->GetIntArrayElements(statusOut, nullptr) : nullptr;
    if (poses == nullptr) {
        return -104;
    }

    for (int h = 0; h < kHands; ++h) {
        bool active = g_state.locations[h].isActive == XR_TRUE;
        bool anyPosition = false;
        for (int j = 0; j < kJointCount; ++j) {
            const XrHandJointLocationEXT& loc = g_state.joints[h][j];
            float* dst = poses + (h * kJointCount + j) * kFloatsPerJoint;
            dst[0] = loc.pose.position.x;
            dst[1] = loc.pose.position.y;
            dst[2] = loc.pose.position.z;
            dst[3] = loc.pose.orientation.x;
            dst[4] = loc.pose.orientation.y;
            dst[5] = loc.pose.orientation.z;
            dst[6] = loc.pose.orientation.w;
            dst[7] = loc.radius;
            if (flags != nullptr) {
                flags[h * kJointCount + j] = static_cast<jint>(loc.locationFlags);
            }
            if ((loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0) {
                anyPosition = true;
            }
        }
        if (status != nullptr) {
            status[h] = (active ? 1 : 0) | (anyPosition ? 2 : 0) | ((h == 0 && viewValid) ? 4 : 0);
        }
    }

    env->ReleaseFloatArrayElements(posesOut, poses, 0);
    if (flags != nullptr) {
        env->ReleaseIntArrayElements(flagsOut, flags, 0);
    }
    if (status != nullptr) {
        env->ReleaseIntArrayElements(statusOut, status, 0);
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeIsReady(JNIEnv*, jobject) {
    return g_state.initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeSpaceType(JNIEnv*, jobject, jint spaceSel) {
    if (spaceSel < 0 || spaceSel >= kSpaceCount) {
        return -1;
    }
    return g_state.spaces[spaceSel].referenceType;
}

JNIEXPORT jintArray JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeStats(JNIEnv* env, jobject) {
    jint values[4] = {
            g_state.locateCalls,
            g_state.locateFailures,
            g_state.lastError,
            g_state.resolvedTimeOffsetNs,
    };
    jintArray out = env->NewIntArray(4);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, 4, values);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_com_gtasixty_spatial_HandTrackerJni_nativeShutdown(JNIEnv*, jobject) {
    releaseAll();
    g_state.initialized = false;
    g_state.instance = XR_NULL_HANDLE;
    g_state.session = XR_NULL_HANDLE;
    g_state.getInstanceProcAddr = nullptr;
    g_state.createHandTracker = nullptr;
    g_state.destroyHandTracker = nullptr;
    g_state.locateHandJoints = nullptr;
    g_state.createReferenceSpace = nullptr;
    g_state.destroySpace = nullptr;
    LOGI("nativeShutdown");
}

}  // extern "C"

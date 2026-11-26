package com.gtasixty.spatial

import android.util.Log

/**
 * Direct JNI binding to OpenXR `XR_EXT_hand_tracking`.
 *
 * The library `libgtahandtracker.so` is produced by `scripts/build-hand-tracker.ps1` from
 * `src/main/cpp/hand_tracker_jni.cpp` and placed in `src/main/jniLibs/arm64-v8a/`. It has zero
 * external dependencies (every OpenXR entry point is resolved through the
 * `xrGetInstanceProcAddr` pointer owned by the Meta Spatial SDK), so loading it can never disturb
 * the SDK's own OpenXR loader.
 *
 * [ensureLoaded] returns false when the `.so` was never built or the device/ABI cannot load it;
 * callers then fall back to [HandTrackingSource]'s SDK body-tracking path.
 */
internal object HandTrackerJni {

    private const val TAG = "GTA60Hands"

    const val JOINT_COUNT = 26
    const val FLOATS_PER_JOINT = 8
    const val HAND_COUNT = 2

    /** Left hand slot. */
    const val HAND_LEFT = 0

    /** Right hand slot. */
    const val HAND_RIGHT = 1

    /** `XrReferenceSpaceType` slot indices understood by [nativeCreateSpace] / [nativeLocate]. */
    const val SPACE_STAGE = 0
    const val SPACE_LOCAL = 1
    const val SPACE_LOCAL_FLOOR = 2

    /** Joint index constants from `XrHandJointEXT`. */
    const val JOINT_PALM = 0
    const val JOINT_WRIST = 1
    const val JOINT_THUMB_METACARPAL = 2
    const val JOINT_THUMB_PROXIMAL = 3
    const val JOINT_THUMB_DISTAL = 4
    const val JOINT_THUMB_TIP = 5
    const val JOINT_INDEX_METACARPAL = 6
    const val JOINT_INDEX_PROXIMAL = 7
    const val JOINT_INDEX_INTERMEDIATE = 8
    const val JOINT_INDEX_DISTAL = 9
    const val JOINT_INDEX_TIP = 10
    const val JOINT_MIDDLE_METACARPAL = 11
    const val JOINT_MIDDLE_PROXIMAL = 12
    const val JOINT_MIDDLE_INTERMEDIATE = 13
    const val JOINT_MIDDLE_DISTAL = 14
    const val JOINT_MIDDLE_TIP = 15
    const val JOINT_RING_METACARPAL = 16
    const val JOINT_RING_PROXIMAL = 17
    const val JOINT_RING_INTERMEDIATE = 18
    const val JOINT_RING_DISTAL = 19
    const val JOINT_RING_TIP = 20
    const val JOINT_LITTLE_METACARPAL = 21
    const val JOINT_LITTLE_PROXIMAL = 22
    const val JOINT_LITTLE_INTERMEDIATE = 23
    const val JOINT_LITTLE_DISTAL = 24
    const val JOINT_LITTLE_TIP = 25

    /** `XrSpaceLocationFlags` bits we care about. */
    const val LOCATION_ORIENTATION_VALID = 0x00000001
    const val LOCATION_POSITION_VALID = 0x00000002
    const val LOCATION_ORIENTATION_TRACKED = 0x00000004
    const val LOCATION_POSITION_TRACKED = 0x00000008

    private var loadAttempted = false
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loadAttempted) return loaded
        loadAttempted = true
        loaded = try {
            System.loadLibrary("gtahandtracker")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "libgtahandtracker.so unavailable (${t.javaClass.simpleName}: ${t.message}); " +
                    "falling back to Spatial SDK body-tracking hand data")
            false
        }
        return loaded
    }

    external fun nativeInit(instance: Long, session: Long, getInstanceProcAddr: Long): Int

    external fun nativeCreateSpace(spaceSel: Int): Int

    /** Destroys and re-creates both hand trackers; returns the same mask shape as [nativeInit]. */
    external fun nativeRecreateTrackers(): Int

    /** 1 = multimodal (simultaneous hands + controllers) supported, 0 = not, negative = error. */
    external fun nativeMultimodalSupported(): Int

    /**
     * `xrResumeSimultaneousHandsAndControllersTrackingMETA`. Returns the `XrResult` (0 == success) or a
     * negative code when the extension is unavailable.
     */
    external fun nativeEnableMultimodal(): Int

    external fun nativeLocate(
        poses: FloatArray,
        flags: IntArray,
        status: IntArray,
        viewPose: FloatArray?,
        spaceSel: Int,
        timeNanos: Long
    ): Int

    external fun nativeIsReady(): Boolean

    external fun nativeSpaceType(spaceSel: Int): Int

    /** `[locateCalls, locateFailures, lastError, resolvedTimeOffsetNs]`. */
    external fun nativeStats(): IntArray

    external fun nativeShutdown()
}

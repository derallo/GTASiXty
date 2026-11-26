package com.gtasixty.spatial

import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.JointSet
import com.meta.spatial.runtime.JointType
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SkeletonJoint

/**
 * Supplies live left/right hand joint poses in **SDK world space** for [GtaHandsControllerSystem].
 *
 * Two backends, tried in order:
 *
 * 1. [Backend.OPENXR] â€” `libgtahandtracker.so` (see `hand_tracker_jni.cpp`). Gives all 26
 *    `XR_EXT_hand_tracking` joints per hand with per-joint validity flags and radii. Needs
 *    `oculus.software.handtracking` in the manifest (always declared) and hand tracking enabled on
 *    the headset. Does **not** need the BODY_TRACKING permission and keeps working while
 *    `avatarSystem.setShowHands(false)` hides Meta's translucent hand meshes.
 *
 * 2. [Backend.SDK] â€” `Scene.updateBodyTrackingBuffersAtTime`. Zero JNI, guaranteed to be expressed in
 *    the same space as `Scene.getViewerPose()`, but it is gated on the Spatial SDK exposing body
 *    tracking joints. Used automatically when the native library is missing or the runtime refuses
 *    `XR_EXT_hand_tracking`, and selectable explicitly for comparison.
 *
 * Reference-space alignment
 * -------------------------
 * The native path locates joints against an OpenXR reference space *we* create. The SDK's world space
 * may be STAGE (floor origin) or LOCAL (head-at-session-start origin). We infer which by looking at
 * the viewer pose height once at start-up: a floor-origin SDK space puts the eye at ~1.2â€“2.0 m, a
 * LOCAL-origin space puts it near 0. [spaceCalibration] then applies a persisted manual nudge for the
 * rare case the inference is wrong.
 */
@OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
internal class HandTrackingSource {

    enum class Backend { NONE, OPENXR, SDK }

    /** One immutable-ish snapshot of both hands, reused across frames to avoid per-frame garbage. */
    class HandJointFrame {
        val pos = Array(HandTrackerJni.HAND_COUNT) { Array(HandTrackerJni.JOINT_COUNT) { Vector3(0f, 0f, 0f) } }
        val rot = Array(HandTrackerJni.HAND_COUNT) { Array(HandTrackerJni.JOINT_COUNT) { Quaternion() } }
        val valid = Array(HandTrackerJni.HAND_COUNT) { BooleanArray(HandTrackerJni.JOINT_COUNT) }
        val radius = Array(HandTrackerJni.HAND_COUNT) { FloatArray(HandTrackerJni.JOINT_COUNT) }
        val active = BooleanArray(HandTrackerJni.HAND_COUNT)

        fun hasJoint(hand: Int, joint: Int): Boolean = valid[hand][joint]
    }

    companion object {
        private const val TAG = "GTA60Hands"
        private const val SPACE_INFER_FLOOR_EYE_M = 0.35f

        /** How often the diagnostic space probe runs (costs 3 extra locate calls). */
        private const val PROBE_INTERVAL_MS = 2000L

        /** Cap on tracker re-creation attempts while no joints are delivered. */
        private const val MAX_TRACKER_RECREATES = 5
    }

    val frame = HandJointFrame()

    var backend: Backend = Backend.NONE
        private set

    /** OpenXR reference-space slot currently used by the native backend. */
    var spaceSlot: Int = HandTrackerJni.SPACE_STAGE
        private set

    /** Label for logging / the UI diagnostics line. */
    var backendLabel: String = "off"
        private set

    /** Manual world-space nudge applied to every joint (metres), for reference-space mismatches. */
    var spaceCalibration = Vector3(0f, 0f, 0f)

    /** Set once we determined the SDK's world space origin by looking at the viewer pose. */
    private var inferredFloorOrigin: Boolean? = null
    /** False until we have seen a real viewer pose and could pick a matching reference space. */
    private var spaceResolved = false
    private var nativeInitAttempted = false
    private var nativeMask = 0
    private var sdkPathWarned = false
    private var lastProbeMs = 0L
    private var multimodalState = -999
    private var emptyProbes = 0
    private var trackerRecreates = 0
    private var nativeFailureWarned = 0
    private var lastErrorLogMs = 0L
    private var updateCount = 0

    private val nativePoses =
        FloatArray(HandTrackerJni.HAND_COUNT * HandTrackerJni.JOINT_COUNT * HandTrackerJni.FLOATS_PER_JOINT)
    private val nativeFlags = IntArray(HandTrackerJni.HAND_COUNT * HandTrackerJni.JOINT_COUNT)
    private val nativeStatus = IntArray(HandTrackerJni.HAND_COUNT)
    private val nativeViewPose = FloatArray(7)

    private val sdkControllers = ArrayList<ControllerPose>(2)
    private val sdkSkeleton = ArrayList<SkeletonJoint>(128)
    private var sdkJointMap: Map<Int, IntArray>? = null
    private var sdkEnabled = false
    private var sdkConsecutiveFailures = 0

    val isNativeAvailable: Boolean get() = (nativeMask and 0x20) != 0

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /** Tries the native backend. Safe to call more than once; only the first call does work. */
    fun initNative(scene: Scene, viewerPose: Pose): Boolean {
        if (nativeInitAttempted) return backend == Backend.OPENXR
        nativeInitAttempted = true
        if (!HandTrackerJni.ensureLoaded()) {
            return false
        }
        try {
            nativeMask = HandTrackerJni.nativeInit(
                scene.getOpenXrInstanceHandle(),
                scene.getOpenXrSessionHandle(),
                scene.getOpenXrGetInstanceProcAddrHandle()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "nativeInit threw ${t.javaClass.simpleName}: ${t.message}")
            nativeMask = 0
        }
        Log.i(
            TAG,
            "OpenXR hand tracking init mask=0x%02X (left=%b right=%b stage=%b local=%b floor=%b ext=%b)"
                .format(
                    nativeMask,
                    (nativeMask and 0x01) != 0, (nativeMask and 0x02) != 0,
                    (nativeMask and 0x04) != 0, (nativeMask and 0x08) != 0, (nativeMask and 0x10) != 0,
                    (nativeMask and 0x20) != 0
                )
        )
        if ((nativeMask and 0x03) == 0 || (nativeMask and 0x20) == 0) {
            Log.i(TAG, "XR_EXT_hand_tracking not usable on this runtime/app; using SDK body-tracking fallback")
            return false
        }

        // Prefer STAGE until we actually see a head pose and can infer which convention the SDK uses.
        spaceSlot = if ((nativeMask and 0x04) != 0) HandTrackerJni.SPACE_STAGE else HandTrackerJni.SPACE_LOCAL
        ensureSpace()
        backend = Backend.OPENXR
        backendLabel = "OpenXR ${spaceSlotLabel(spaceSlot)}"
        Log.i(TAG, "OpenXR hand backend started (space=${spaceSlotLabel(spaceSlot)}, awaiting head pose)")
        requestMultimodal("init")
        refineSpace(viewerPose)
        return true
    }

    /**
     * Asks the runtime for simultaneous hands + controllers ("multimodal").
     *
     * Without this Horizon OS runs the app in the classic either/or mode: as soon as a Touch
     * controller is detected — even just resting beside the user — `xrLocateHandJointsEXT` reports
     * `isActive = false` and every joint is invalid, which is exactly the failure mode we hit.
     */
    private fun requestMultimodal(reason: String) {
        val supported = runCatching { HandTrackerJni.nativeMultimodalSupported() }.getOrElse { -99 }
        val result = runCatching { HandTrackerJni.nativeEnableMultimodal() }.getOrElse { -98 }
        multimodalState = result
        Log.i(
            TAG,
            "multimodal($reason): supported=$supported resumeResult=$result" +
                    (if (result == 0) "  <-- hands + controllers now simultaneous" else "")
        )
    }

    private fun ensureSpace() {
        try {
            HandTrackerJni.nativeCreateSpace(spaceSlot)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeCreateSpace threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Picks the OpenXR reference space that matches the SDK's world-space convention once a real head
     * pose is available: a STAGE local-floor origin puts the eye at ~1.2–2.0 m, a LOCAL origin puts it
     * near zero. Called every frame; does nothing after the first successful resolution.
     */
    fun refineSpace(viewerPose: Pose) {
        if (backend != Backend.OPENXR || spaceResolved) return
        if (viewerPose.t.length() < 0.01f) return
        inferredFloorOrigin = kotlin.math.abs(viewerPose.t.y) > SPACE_INFER_FLOOR_EYE_M
        val chosen = when {
            inferredFloorOrigin == true && (nativeMask and 0x04) != 0 -> HandTrackerJni.SPACE_STAGE
            inferredFloorOrigin == true && (nativeMask and 0x10) != 0 -> HandTrackerJni.SPACE_LOCAL_FLOOR
            inferredFloorOrigin == false && (nativeMask and 0x08) != 0 -> HandTrackerJni.SPACE_LOCAL
            (nativeMask and 0x04) != 0 -> HandTrackerJni.SPACE_STAGE
            (nativeMask and 0x08) != 0 -> HandTrackerJni.SPACE_LOCAL
            else -> HandTrackerJni.SPACE_LOCAL_FLOOR
        }
        spaceResolved = true
        if (chosen == spaceSlot) {
            Log.i(
                TAG,
                "Hand reference space confirmed: ${spaceSlotLabel(spaceSlot)} " +
                        "(viewerY=${"%.3f".format(viewerPose.t.y)} floorOrigin=$inferredFloorOrigin)"
            )
            return
        }
        spaceSlot = chosen
        ensureSpace()
        backendLabel = "OpenXR ${spaceSlotLabel(spaceSlot)}"
        Log.i(
            TAG,
            "Hand reference space switched to ${spaceSlotLabel(spaceSlot)} " +
                    "(viewerY=${"%.3f".format(viewerPose.t.y)} floorOrigin=$inferredFloorOrigin)"
        )
    }

    fun enableSdkFallback(scene: Scene) {
        if (sdkEnabled) return
        sdkEnabled = true
        try {
            scene.setBodyTrackingJointSet(JointSet.DEFAULT)
        } catch (t: Throwable) {
            Log.w(TAG, "setBodyTrackingJointSet failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        sdkJointMap = buildSdkJointMap()
        Log.i(TAG, "SDK body-tracking hand fallback enabled (mapped ${sdkJointMap?.size ?: 0} joint types)")
        if (backend == Backend.NONE) {
            backend = Backend.SDK
            backendLabel = "SDK body tracking"
        }
    }

    fun shutdown() {
        if (nativeInitAttempted && HandTrackerJni.ensureLoaded()) {
            try {
                HandTrackerJni.nativeShutdown()
            } catch (_: Throwable) {
            }
        }
        backend = Backend.NONE
        backendLabel = "off"
        nativeInitAttempted = false
        nativeMask = 0
        sdkEnabled = false
    }

    // ---------------------------------------------------------------------------------------------
    // Per-frame update
    // ---------------------------------------------------------------------------------------------

    /**
     * Refreshes [frame]. Returns true when at least one hand has a usable wrist + palm + thumb.
     * [timeNanos] should be `System.nanoTime()` â€” the Meta runtime's `XrTime` is CLOCK_MONOTONIC ns.
     */
    fun update(scene: Scene, viewerPose: Pose, timeNanos: Long, nowMs: Long): Boolean {
        updateCount++
        when (backend) {
            Backend.OPENXR -> if (updateNative(viewerPose, timeNanos)) return anyHandUsable()
            Backend.SDK -> if (updateSdk(scene, nowMs)) return anyHandUsable()
            Backend.NONE -> return false
        }
        return anyHandUsable()
    }

    private fun anyHandUsable(): Boolean =
        isHandUsable(HandTrackerJni.HAND_LEFT) || isHandUsable(HandTrackerJni.HAND_RIGHT)

    fun isHandUsable(hand: Int): Boolean {
        if (!frame.active[hand]) return false
        val f = frame
        return f.hasJoint(hand, HandTrackerJni.JOINT_WRIST) &&
                f.hasJoint(hand, HandTrackerJni.JOINT_PALM) &&
                f.hasJoint(hand, HandTrackerJni.JOINT_THUMB_TIP)
    }

    private fun updateNative(viewerPose: Pose, timeNanos: Long): Boolean {
        val rc = try {
            HandTrackerJni.nativeLocate(
                nativePoses, nativeFlags, nativeStatus, nativeViewPose, spaceSlot, timeNanos
            )
        } catch (t: Throwable) {
            Log.w(TAG, "nativeLocate threw ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        if (rc != 0) {
            if (nativeFailureWarned < 5 || nowMsForLog() - lastErrorLogMs > 5000L) {
                nativeFailureWarned++
                lastErrorLogMs = nowMsForLog()
                Log.w(TAG, "nativeLocate failed rc=$rc (space=${spaceSlotLabel(spaceSlot)})")
            }
            return false
        }

        // Exact OpenXR STAGE -> Meta Spatial SDK world-space conversion:
        // Verified directly on Quest 3 live logs:
        //   OpenXR STAGE:  (-1.131, +0.288, -0.980)   (+X right, +Y up, -Z forward)
        //   Spatial SDK:   (-1.386, +0.561, +0.931)   (+X right, +Y up, +Z forward)
        // Meta Spatial SDK world space is literally OpenXR STAGE with Z negated (x, y, -z).
        // Keeping this purely static (independent of viewerPose) ensures hands and controller stay
        // 100% locked in real-world space when moving/turning your head.
        val cal = spaceCalibration
        for (h in 0 until HandTrackerJni.HAND_COUNT) {
            frame.active[h] = (nativeStatus[h] and 0x02) != 0
            for (j in 0 until HandTrackerJni.JOINT_COUNT) {
                val o = (h * HandTrackerJni.JOINT_COUNT + j) * HandTrackerJni.FLOATS_PER_JOINT
                val flags = nativeFlags[h * HandTrackerJni.JOINT_COUNT + j]
                val positionValid = (flags and HandTrackerJni.LOCATION_POSITION_VALID) != 0
                val orientationValid = (flags and HandTrackerJni.LOCATION_ORIENTATION_VALID) != 0
                frame.valid[h][j] = positionValid
                frame.radius[h][j] = nativePoses[o + 7]

                frame.pos[h][j] = Vector3(
                    nativePoses[o] + cal.x,
                    nativePoses[o + 1] + cal.y,
                    -nativePoses[o + 2] + cal.z
                )
                frame.rot[h][j] = if (orientationValid) {
                    // Store the raw OpenXR joint quaternion (w, x, y, z) so GtaHandsControllerSystem
                    // can apply the exact GLTFLoader X-reflection (w, x, -y, -z) matching oculus-hand's
                    // imported inverseBindMatrices.
                    Quaternion(nativePoses[o + 6], nativePoses[o + 3], nativePoses[o + 4], nativePoses[o + 5])
                } else {
                    Quaternion()
                }
            }
        }
        if (updateCount == 1 || (updateCount % 900) == 0) {
            val stats = runCatching { HandTrackerJni.nativeStats() }.getOrNull()
            Log.i(
                TAG,
                "Hand frame #$updateCount active=L${frame.active[0]} R${frame.active[1]} " +
                        "L-wrist=${frame.pos[0][HandTrackerJni.JOINT_WRIST]} " +
                        "R-wrist=${frame.pos[1][HandTrackerJni.JOINT_WRIST]} " +
                        "viewer=${viewerPose.t} stats=${stats?.joinToString(",")}"
            )
        }
        probeSpacesIfDue(timeNanos)
        return true
    }

    /** Number of joints the last [probeSpace] call saw with a valid position. */
    private var probeLastValid = 0

    private fun probeSpacesIfDue(timeNanos: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastProbeMs < PROBE_INTERVAL_MS) return
        lastProbeMs = now
        val slots = intArrayOf(
            HandTrackerJni.SPACE_STAGE,
            HandTrackerJni.SPACE_LOCAL,
            HandTrackerJni.SPACE_LOCAL_FLOOR
        )
        val report = StringBuilder("space probe:")
        var bestSlot = -1
        var bestValid = 0
        var bestActive = 0
        for (slot in slots) {
            val rc = runCatching {
                HandTrackerJni.nativeLocate(nativePoses, nativeFlags, nativeStatus, null, slot, timeNanos)
            }.getOrElse { -999 }
            var valid = 0
            for (h in 0 until HandTrackerJni.HAND_COUNT) {
                for (j in 0 until HandTrackerJni.JOINT_COUNT) {
                    if ((nativeFlags[h * HandTrackerJni.JOINT_COUNT + j] and
                                HandTrackerJni.LOCATION_POSITION_VALID) != 0) {
                        valid++
                    }
                }
            }
            val active = nativeStatus[0] or nativeStatus[1]
            val wrist = "(%+.3f,%+.3f,%+.3f)".format(
                nativePoses[HandTrackerJni.JOINT_WRIST * HandTrackerJni.FLOATS_PER_JOINT],
                nativePoses[HandTrackerJni.JOINT_WRIST * HandTrackerJni.FLOATS_PER_JOINT + 1],
                nativePoses[HandTrackerJni.JOINT_WRIST * HandTrackerJni.FLOATS_PER_JOINT + 2]
            )
            report.append(" | ${spaceSlotLabel(slot)} rc=$rc act=$active valid=$valid Lwrist=$wrist")
            if (valid > bestValid) {
                bestValid = valid
                bestSlot = slot
                bestActive = active
            }
        }
        Log.i(TAG, report.toString())

        // Self-heal: if the space we picked delivers nothing but another one does, adopt it.
        if (bestSlot >= 0 && bestSlot != spaceSlot) {
            Log.w(
                TAG,
                "Switching hand reference space ${spaceSlotLabel(spaceSlot)} -> " +
                        "${spaceSlotLabel(bestSlot)} (valid=$bestValid active=$bestActive)"
            )
            spaceSlot = bestSlot
            ensureSpace()
            backendLabel = "OpenXR ${spaceSlotLabel(spaceSlot)}"
        }

        // Second self-heal: no joints at all for a few probes in a row means the trackers may have
        // been created before the session was focusable. Recreate them (bounded number of attempts).
        if (bestValid == 0 && bestActive == 0) {
            emptyProbes++
            if (emptyProbes >= 3 && multimodalState != 0) {
                requestMultimodal("probe")
            }
            if (emptyProbes >= 3 && trackerRecreates < MAX_TRACKER_RECREATES) {
                trackerRecreates++
                emptyProbes = 0
                val mask = runCatching { HandTrackerJni.nativeRecreateTrackers() }.getOrElse { -1 }
                Log.w(TAG, "No hand joints for 3 probes; recreated trackers (attempt $trackerRecreates, mask=0x%02X)"
                    .format(mask))
            }
        } else {
            emptyProbes = 0
        }
    }

    private fun nowMsForLog(): Long = System.currentTimeMillis()

    private fun updateSdk(scene: Scene, nowMs: Long): Boolean {
        val map = sdkJointMap ?: buildSdkJointMap().also { sdkJointMap = it }
        if (map.isEmpty()) {
            if (!sdkPathWarned) {
                sdkPathWarned = true
                Log.w(TAG, "SDK hand joint map is empty; hand tracking unavailable")
            }
            return false
        }
        sdkControllers.clear()
        sdkSkeleton.clear()
        val ok = try {
            @Suppress("UNUSED_EXPRESSION")
            scene.updateBodyTrackingBuffersAtTime(sdkControllers, sdkSkeleton, System.nanoTime())
        } catch (t: Throwable) {
            // The SDK throws (not returns) when body tracking was never initialised — e.g. body
            // tracking is off in headset settings. Stop calling it so we do not pay the cost (and the
            // log spam) every frame for the rest of the session.
            sdkConsecutiveFailures++
            if (sdkConsecutiveFailures >= 3) {
                sdkEnabled = false
                if (backend == Backend.SDK) {
                    backend = Backend.NONE
                    backendLabel = "SDK body tracking unavailable"
                }
                Log.w(
                    TAG,
                    "updateBodyTrackingBuffersAtTime unavailable after $sdkConsecutiveFailures " +
                            "attempts (${t.javaClass.simpleName}: ${t.message}); hand tracking disabled " +
                            "until the native OpenXR path is present"
                )
            } else if (!sdkPathWarned) {
                sdkPathWarned = true
                Log.w(TAG, "updateBodyTrackingBuffersAtTime threw ${t.javaClass.simpleName}: ${t.message}")
            }
            return false
        }
        sdkConsecutiveFailures = 0
        if (!ok || sdkSkeleton.isEmpty()) {
            if (!sdkPathWarned) {
                sdkPathWarned = true
                Log.w(
                    TAG,
                    "SDK body tracking returned ok=$ok joints=${sdkSkeleton.size}; " +
                            "hand tracking will use the native backend only"
                )
            }
            return false
        }
        for (h in 0 until HandTrackerJni.HAND_COUNT) {
            frame.active[h] = false
            for (j in 0 until HandTrackerJni.JOINT_COUNT) {
                frame.valid[h][j] = false
            }
        }
        var mapped = 0
        for (joint in sdkSkeleton) {
            val pair = map[joint.jointIndex] ?: continue
            val h = pair[0]
            val j = pair[1]
            val p = joint.pose
            if (p.t.length() < 1.0e-5f) continue
            frame.pos[h][j] = p.t
            frame.rot[h][j] = p.q
            frame.valid[h][j] = true
            mapped++
        }
        if (mapped == 0) {
            if (!sdkPathWarned) {
                sdkPathWarned = true
                Log.w(TAG, "SDK body tracking reports ${sdkSkeleton.size} joints but none are hand joints")
            }
            return false
        }
        for (h in 0 until HandTrackerJni.HAND_COUNT) {
            // Require the core grip joints before claiming a hand is live.
            frame.active[h] = frame.valid[h][HandTrackerJni.JOINT_WRIST] &&
                    frame.valid[h][HandTrackerJni.JOINT_PALM] &&
                    frame.valid[h][HandTrackerJni.JOINT_THUMB_TIP]
        }
        if (updateCount == 1 || (updateCount % 900) == 0) {
            Log.i(TAG, "SDK hand frame #$updateCount mapped=$mapped active=L${frame.active[0]} R${frame.active[1]}")
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun spaceSlotLabel(slot: Int): String = when (slot) {
        HandTrackerJni.SPACE_STAGE -> "STAGE"
        HandTrackerJni.SPACE_LOCAL -> "LOCAL"
        HandTrackerJni.SPACE_LOCAL_FLOOR -> "LOCAL_FLOOR"
        else -> "?"
    }

    /**
     * Builds `JointType.index -> [hand, openXrJointIndex]` from the enum names, so we never have to
     * hard-code 52 verbose enum references (and keep working if Meta reorders them).
     */
    private fun buildSdkJointMap(): Map<Int, IntArray> {
        val out = HashMap<Int, IntArray>(64)
        val suffixToJoint = mapOf(
            "PALM" to HandTrackerJni.JOINT_PALM,
            "WRIST" to HandTrackerJni.JOINT_WRIST,
            "THUMB_METACARPAL" to HandTrackerJni.JOINT_THUMB_METACARPAL,
            "THUMB_PROXIMAL" to HandTrackerJni.JOINT_THUMB_PROXIMAL,
            "THUMB_DISTAL" to HandTrackerJni.JOINT_THUMB_DISTAL,
            "THUMB_TIP" to HandTrackerJni.JOINT_THUMB_TIP,
            "INDEX_METACARPAL" to HandTrackerJni.JOINT_INDEX_METACARPAL,
            "INDEX_PROXIMAL" to HandTrackerJni.JOINT_INDEX_PROXIMAL,
            "INDEX_INTERMEDIATE" to HandTrackerJni.JOINT_INDEX_INTERMEDIATE,
            "INDEX_DISTAL" to HandTrackerJni.JOINT_INDEX_DISTAL,
            "INDEX_TIP" to HandTrackerJni.JOINT_INDEX_TIP,
            "MIDDLE_METACARPAL" to HandTrackerJni.JOINT_MIDDLE_METACARPAL,
            "MIDDLE_PROXIMAL" to HandTrackerJni.JOINT_MIDDLE_PROXIMAL,
            "MIDDLE_INTERMEDIATE" to HandTrackerJni.JOINT_MIDDLE_INTERMEDIATE,
            "MIDDLE_DISTAL" to HandTrackerJni.JOINT_MIDDLE_DISTAL,
            "MIDDLE_TIP" to HandTrackerJni.JOINT_MIDDLE_TIP,
            "RING_METACARPAL" to HandTrackerJni.JOINT_RING_METACARPAL,
            "RING_PROXIMAL" to HandTrackerJni.JOINT_RING_PROXIMAL,
            "RING_INTERMEDIATE" to HandTrackerJni.JOINT_RING_INTERMEDIATE,
            "RING_DISTAL" to HandTrackerJni.JOINT_RING_DISTAL,
            "RING_TIP" to HandTrackerJni.JOINT_RING_TIP,
            "LITTLE_METACARPAL" to HandTrackerJni.JOINT_LITTLE_METACARPAL,
            "LITTLE_PROXIMAL" to HandTrackerJni.JOINT_LITTLE_PROXIMAL,
            "LITTLE_INTERMEDIATE" to HandTrackerJni.JOINT_LITTLE_INTERMEDIATE,
            "LITTLE_DISTAL" to HandTrackerJni.JOINT_LITTLE_DISTAL,
            "LITTLE_TIP" to HandTrackerJni.JOINT_LITTLE_TIP
        )
        for (type in JointType.values()) {
            val name = type.name
            val hand = when {
                name.startsWith("LEFT_HAND_") -> HandTrackerJni.HAND_LEFT
                name.startsWith("RIGHT_HAND_") -> HandTrackerJni.HAND_RIGHT
                else -> continue
            }
            val suffix = if (hand == HandTrackerJni.HAND_LEFT) {
                name.removePrefix("LEFT_HAND_")
            } else {
                name.removePrefix("RIGHT_HAND_")
            }
            val jointIndex = suffixToJoint[suffix] ?: continue
            out[type.index] = intArrayOf(hand, jointIndex)
        }
        return out
    }
}

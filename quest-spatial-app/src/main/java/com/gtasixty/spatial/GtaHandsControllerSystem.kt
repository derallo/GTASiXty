package com.gtasixty.spatial

import android.net.Uri
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * GTA V hands + auto-spawning 3D Xbox controller, driven entirely by Quest 3 optical hand tracking.
 *
 * Design notes (see GTA_HANDS_CONTROLLER_PLAN.md for the full rationale):
 *
 * * **Pure CV first.** In the normal capture-card play mode the physical Xbox pad is paired to the
 *   Xbox, so the Quest sees no gamepad events at all. Every joint animation here is derived from
 *   [HandTrackingSource] alone; [GamepadVisualState] is layered on only when a pad really is talking
 *   to the headset (`lastInputUptimeMs` within 15 s).
 * * **Thumb/wrist-centric hold detection.** With a pad in your hands the indexâ†’pinky fingers are
 *   occluded by the plastic grips, so the detector only uses palm/thumb spans and posture.
 * * **Rigid segment rig.** The GLBs contain one mesh per bone (`PALM`, each phalanx, `FOREARM`) in a
 *   frame of "+Y along the bone, +Z dorsal". Every frame we rewrite each node's *local* transform
 *   from world joint poses via `SceneObject.setLocalNodeTransformsBatch`, a batched native call with
 *   no per-frame allocation. Because node orientation is rebuilt from joint **positions**, we never
 *   depend on Meta's joint-orientation convention.
 * * **Unlit.** Assets use `KHR_materials_unlit` with baked linear `COLOR_0`, so the whole rig is one
 *   texture fetch + multiply per fragment and adds no lights or shadow casters to the scene.
 *
 * Bone/joint frames
 * -----------------
 * `WRIST`/`PALM`: `+Y` = wristâ†’knuckles, `+Z` = dorsal, `+X` = `+Y x +Z`.
 * Phalanx `k`: `+Y` = direction toward joint `k+1`, `+Z` = dorsal carried down the chain.
 * `FOREARM`: `+Y` = elbowâ†’wrist, mesh occupies local `y in [-0.10, +0.01]`.
 * `ControllerRoot`: `+X` = player's right, `+Y` = face normal (toward the player's face),
 * `+Z` = toward the player's chest (so LT/RT/LB/RB sit at -Z and the grips reach to +Z/-Y).
 */
internal class GtaHandsControllerSystem(
    private val scene: Scene,
    private val handSource: HandTrackingSource,
    private val sceneObjectProvider: (Entity) -> SceneObject?,
    /** Guards `Mesh(uri)` creation: the SDK's GLTF loader throws on a worker coroutine (and kills the
     *  process) when an asset is missing, so a caught exception is not enough here. */
    private val assetExists: (String) -> Boolean = { true },
) {

    companion object {
        private const val TAG = "GTA60Hands"

        val LEFT_HAND_URI: Uri = Uri.parse("scenes/gta_hand_left.glb")
        val RIGHT_HAND_URI: Uri = Uri.parse("scenes/gta_hand_right.glb")
        val CONTROLLER_URI: Uri = Uri.parse("scenes/xbox_controller.glb")

        const val LEFT_HAND_ASSET = "scenes/gta_hand_left.glb"
        const val RIGHT_HAND_ASSET = "scenes/gta_hand_right.glb"
        const val CONTROLLER_ASSET = "scenes/xbox_controller.glb"

        private const val HAND_LEFT = HandTrackerJni.HAND_LEFT
        private const val HAND_RIGHT = HandTrackerJni.HAND_RIGHT

        // ---- OpenXR hand joint indices ---------------------------------------------------------
        private const val J_PALM = HandTrackerJni.JOINT_PALM
        private const val J_WRIST = HandTrackerJni.JOINT_WRIST
        private const val J_THUMB_MC = HandTrackerJni.JOINT_THUMB_METACARPAL
        private const val J_THUMB_TIP = HandTrackerJni.JOINT_THUMB_TIP
        private const val J_INDEX_MC = HandTrackerJni.JOINT_INDEX_METACARPAL
        private const val J_INDEX_PROX = HandTrackerJni.JOINT_INDEX_PROXIMAL
        private const val J_INDEX_TIP = HandTrackerJni.JOINT_INDEX_TIP
        private const val J_LITTLE_MC = HandTrackerJni.JOINT_LITTLE_METACARPAL
        private const val J_MIDDLE_PROX = HandTrackerJni.JOINT_MIDDLE_PROXIMAL
        private const val J_RING_PROX = HandTrackerJni.JOINT_RING_PROXIMAL
        private const val J_LITTLE_PROX = HandTrackerJni.JOINT_LITTLE_PROXIMAL

        /** Slot value meaning "this is the forearm cuff node, not a tracked joint". */
        private const val NODE_FOREARM = 26

        /**
         * Node names in the official skinned OpenXR hand GLBs (oculus-hand), index-aligned with
         * OpenXR XrHandJointEXT (index 0 = PALM unused in 25-joint Oculus skin; 1..25 = wrist..pinky-tip).
         */
        private val HAND_NODE_NAMES = arrayOf(
            "palm", "wrist",
            "thumb-metacarpal", "thumb-phalanx-proximal", "thumb-phalanx-distal", "thumb-tip",
            "index-finger-metacarpal", "index-finger-phalanx-proximal", "index-finger-phalanx-intermediate", "index-finger-phalanx-distal", "index-finger-tip",
            "middle-finger-metacarpal", "middle-finger-phalanx-proximal", "middle-finger-phalanx-intermediate", "middle-finger-phalanx-distal", "middle-finger-tip",
            "ring-finger-metacarpal", "ring-finger-phalanx-proximal", "ring-finger-phalanx-intermediate", "ring-finger-phalanx-distal", "ring-finger-tip",
            "pinky-finger-metacarpal", "pinky-finger-phalanx-proximal", "pinky-finger-phalanx-intermediate", "pinky-finger-phalanx-distal", "pinky-finger-tip",
            "forearm"
        )

        /** Parent joint per OpenXR joint index (-1 = direct child of the entity root). */
        private val JOINT_PARENT = intArrayOf(
            J_WRIST,                       // 0  PALM
            -1,                            // 1  WRIST
            J_WRIST,                       // 2  THUMB_METACARPAL
            2, 3, 4,                       // 3..5 THUMB proximal/distal/tip
            J_WRIST, 6, 7, 8, 9,           // 6..10 INDEX
            J_WRIST, 11, 12, 13, 14,       // 11..15 MIDDLE
            J_WRIST, 16, 17, 18, 19,       // 16..20 RING
            J_WRIST, 21, 22, 23, 24        // 21..25 LITTLE
        )

        /** Next joint down the bone, used to derive the segment's +Y direction (-1 = leaf). */
        private val JOINT_NEXT = intArrayOf(
            -1,                            // 0  PALM (leaf)
            11,                            // 1  WRIST (uses the hand forward axis)
            3, 4, 5, -1,                   // 2..5 THUMB
            7, 8, 9, 10, -1,               // 6..10 INDEX
            12, 13, 14, 15, -1,            // 11..15 MIDDLE
            17, 18, 19, 20, -1,            // 16..20 RING
            22, 23, 24, 25, -1             // 21..25 LITTLE
        )

        /** Hierarchy order: every parent appears before its children. */
        private val JOINT_ORDER = intArrayOf(
            J_WRIST,
            0,
            2, 3, 4, 5,
            6, 7, 8, 9, 10,
            11, 12, 13, 14, 15,
            16, 17, 18, 19, 20,
            21, 22, 23, 24, 25
        )

        /** Rest bone lengths in metres â€” must match scripts/build_gta_hands_and_controller_glb.py. */
        private val FINGER_LENGTHS = arrayOf(
            floatArrayOf(0.042f, 0.026f, 0.021f),   // index
            floatArrayOf(0.046f, 0.029f, 0.022f),   // middle
            floatArrayOf(0.043f, 0.027f, 0.021f),   // ring
            floatArrayOf(0.034f, 0.021f, 0.019f)    // little
        )

        private val FINGER_MC = intArrayOf(
            HandTrackerJni.JOINT_INDEX_METACARPAL,
            HandTrackerJni.JOINT_MIDDLE_METACARPAL,
            HandTrackerJni.JOINT_RING_METACARPAL,
            HandTrackerJni.JOINT_LITTLE_METACARPAL
        )

        /** Extra curl (degrees about the finger's local +X) applied when gripping the controller. */
        private val GRIP_CURL = arrayOf(
            floatArrayOf(30f, 45f, 25f),   // index rests on the trigger
            floatArrayOf(46f, 62f, 36f),
            floatArrayOf(50f, 66f, 38f),
            floatArrayOf(52f, 68f, 40f)
        )

        /** How strongly each finger is pulled toward the ergonomic grip pose. */
        private val FINGER_GRIP_WEIGHT = floatArrayOf(0.45f, 0.95f, 1.0f, 1.0f)

        // ---- Hold-detector tuning (pure CV) ----------------------------------------------------
        private const val PALM_SPAN_MIN_M = 0.100f
        private const val PALM_SPAN_MAX_M = 0.265f
        private const val PALM_SPAN_EXIT_M = 0.300f
        private const val THUMB_SPAN_MIN_M = 0.030f
        private const val THUMB_SPAN_MAX_M = 0.190f
        private const val ENTER_HOLD_MS = 250f
        private const val EXIT_LOST_MS = 650f
        private const val BLEND_IN_S = 0.180f
        private const val BLEND_OUT_S = 0.140f

        // ---- Virtual-controller placement calibration (metres, controller-local axes) ----------
        private const val CTRL_UP_OFFSET_M = -0.022f
        private const val CTRL_BACK_OFFSET_M = -0.036f
        private const val CTRL_SIDE_OFFSET_M = 0.0f

        private const val STICK_TILT_MAX_DEG = 22f
        private const val STICK_GRAB_RADIUS_M = 0.025f
        private const val BUTTON_PRESS_RADIUS_M = 0.016f

        /** A thumb must be at least this high in controller space to count as touching the face. */
        private const val FACE_MIN_Y_M = 0.008f

        /**
         * Controller socket rest positions in ControllerRoot space.
         *
         * These mirror `scripts/build_gta_hands_and_controller_glb.py` and are verified by
         * `scripts/validate_gta_hands_assets.py` (it prints the same table). Keep them in sync if the
         * asset layout changes â€” a mismatch only makes the CV stick/button picking land in the wrong
         * place, it cannot break the rig.
         */
        private val STICK_L_LOCAL = Vector3(-0.0450f, 0.0060f, -0.0220f)
        private val STICK_R_LOCAL = Vector3(-0.0030f, 0.0060f, 0.0140f)
        private val DPAD_LOCAL = Vector3(-0.0360f, 0.0180f, 0.0140f)
        private val BTN_TARGETS = arrayOf(
            Vector3(0.0490f, 0.0180f, 0.0200f),   // A (bottom of the diamond, toward the player)
            Vector3(0.0680f, 0.0180f, 0.0010f),   // B (right)
            Vector3(0.0490f, 0.0180f, -0.0180f),  // X (top of the diamond, toward the TV)
            Vector3(0.0300f, 0.0180f, 0.0010f)    // Y (left)
        )

        private val CONTROLLER_NODE_NAMES = arrayOf(
            "Body", "LB", "RB", "LT", "RT", "LS_Stick", "RS_Stick",
            "DpadUp", "DpadDown", "DpadLeft", "DpadRight",
            "BtnA", "BtnB", "BtnX", "BtnY", "BtnView", "BtnMenu", "Guide"
        )

        /** Rest translations in ControllerRoot space (must match xbox_controller.glb!). */
        private val CTRL_NODE_REST_POS = arrayOf(
            Vector3(0.000f, 0.000f, 0.000f),   // Body
            Vector3(-0.020f, 0.022f, -0.042f), // LB
            Vector3(0.020f, 0.022f, -0.042f),  // RB
            Vector3(-0.036f, 0.016f, -0.043f), // LT
            Vector3(0.036f, 0.016f, -0.043f),  // RT
            Vector3(-0.045f, 0.006f, -0.022f), // LS_Stick
            Vector3(-0.003f, 0.006f, 0.014f),  // RS_Stick
            Vector3(-0.036f, 0.018f, 0.014f),  // DpadUp
            Vector3(-0.036f, 0.018f, 0.014f),  // DpadDown
            Vector3(-0.036f, 0.018f, 0.014f),  // DpadLeft
            Vector3(-0.036f, 0.018f, 0.014f),  // DpadRight
            Vector3(0.049f, 0.018f, 0.020f),   // BtnA
            Vector3(0.068f, 0.018f, 0.001f),   // BtnB
            Vector3(0.049f, 0.018f, -0.018f),  // BtnX
            Vector3(0.030f, 0.018f, 0.001f),   // BtnY
            Vector3(-0.016f, 0.018f, -0.006f), // BtnView
            Vector3(0.016f, 0.018f, -0.006f),  // BtnMenu
            Vector3(0.000f, 0.018f, -0.026f)   // Guide
        )

        private fun v(x: Float, y: Float, z: Float) = Vector3(x, y, z)
    }

    // -----------------------------------------------------------------------------------------
    // Scene entities
    // -----------------------------------------------------------------------------------------

    private var leftHandEntity: Entity? = null
    private var rightHandEntity: Entity? = null
    private var controllerEntity: Entity? = null

    private var leftHandObject: SceneObject? = null
    private var rightHandObject: SceneObject? = null
    private var controllerObject: SceneObject? = null

    private val handRigReady = BooleanArray(2)
    private val meshFailed = BooleanArray(3)   // left hand, right hand, controller
    private var handSlotNode = Array(2) { IntArray(0) }
    private var handSlotJoint = Array(2) { IntArray(0) }
    private var handBatchFloats = Array(2) { FloatArray(0) }

    private var controllerRigReady = false
    private var ctrlSlotName = IntArray(0)
    private var ctrlSlotNode = IntArray(0)
    private var ctrlBatchFloats = FloatArray(0)

    var enabled: Boolean = false
        private set

    /** Persisted manual nudge for the virtual controller (metres, controller-local axes). */
    var controllerCalibration = Vector3(0f, 0f, 0f)

    // -----------------------------------------------------------------------------------------
    // Per-frame state
    // -----------------------------------------------------------------------------------------

    private val worldPos = Array(2) { Array(HandTrackerJni.JOINT_COUNT) { Vector3(0f, 0f, 0f) } }
    private val worldRot = Array(2) { Array(HandTrackerJni.JOINT_COUNT) { Quaternion() } }
    private val handDorsal = Array(2) { Vector3(0f, 1f, 0f) }
    private val handForward = Array(2) { Vector3(0f, 0f, -1f) }
    private val handUsable = BooleanArray(2)

    private val panFilters = Array(3) { OneEuroFilter(1.8f, 0.04f) }
    private var filteredRotation = Quaternion()
    private var rotationInitialized = false

    private enum class HoldState { IDLE, HOLDING }

    private var holdState = HoldState.IDLE
    private var holdCandidateMs = 0f
    private var lostHandsMs = 0f
    private var controllerBlend = 0f
    private var ctrlTargetPose = Pose()
    private var ctrlPose = Pose()

    private var stickLTiltX = 0f
    private var stickLTiltZ = 0f
    private var stickRTiltX = 0f
    private var stickRTiltZ = 0f
    private var leftTriggerVisual = 0f
    private var rightTriggerVisual = 0f
    private var bumperLVisual = 0f
    private var bumperRVisual = 0f
    private val buttonPressVisual = FloatArray(4)
    private val dpadPressVisual = FloatArray(4)

    private var lastLogMs = 0L
    private var tickCount = 0
    private var handLogCount = 0

    // Visibility caches so `Visible` components (native calls) are only written when they change.
    private var leftHandVisible = false
    private var rightHandVisible = false
    private var controllerVisible = false

    /** Entity scale applied while the controller blends in; needed to un-scale picked positions. */
    private var controllerScale = 1f

    // =========================================================================================
    // Public API
    // =========================================================================================

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            holdState = HoldState.IDLE
            controllerBlend = 0f
            holdCandidateMs = 0f
            lostHandsMs = 0f
            rotationInitialized = false
            panFilters.forEach { it.reset() }
            hideAll()
        }
        Log.i(TAG, "GTA hands & controller visuals enabled=$value")
    }

    fun shutdown() {
        enabled = false
        hideAll()
        leftHandEntity = null
        rightHandEntity = null
        controllerEntity = null
        leftHandObject = null
        rightHandObject = null
        controllerObject = null
        handRigReady.fill(false)
        controllerRigReady = false
        handSource.shutdown()
    }

    /** True when at least one hand is being drawn by us, so Meta's default hands must stay hidden. */
    fun isDrawingHands(): Boolean = enabled && (handUsable[0] || handUsable[1])

    /** True when the virtual controller is at least partly visible. */
    fun isControllerVisible(): Boolean = enabled && controllerBlend > 0.02f

    fun statusLine(): String {
        if (!enabled) return "GTA Hands: off"
        val hold = if (holdState == HoldState.HOLDING) "holding" else "free"
        return "GTA Hands: ${handSource.backendLabel} L=${handUsable[0]} R=${handUsable[1]} " +
                "$hold blend=${"%.2f".format(controllerBlend)}"
    }

    // =========================================================================================
    // Tick
    // =========================================================================================

    /** Called from `SpatialMainActivity.onSceneTick()`. */
    fun tick(nowMs: Long, dtSec: Float, viewerPose: Pose) {
        if (!enabled) {
            if (controllerEntity != null || leftHandEntity != null) hideAll()
            return
        }
        tickCount++
        val dt = dtSec.coerceIn(0.001f, 0.05f)
        if (leftHandEntity == null || rightHandEntity == null || controllerEntity == null) {
            createEntities()
        }
        refreshSceneObjects()

        handSource.update(scene, viewerPose, System.nanoTime(), nowMs)

        for (h in 0 until 2) {
            handUsable[h] = handSource.isHandUsable(h) && computeHandBasis(h)
            if (handUsable[h]) copyJoints(h)
        }
        updateHandVisibility()

        // ---- Hold detector ------------------------------------------------------------------
        val bothHands = handUsable[HAND_LEFT] && handUsable[HAND_RIGHT]
        val anyUsable = handUsable[HAND_LEFT] || handUsable[HAND_RIGHT]
        val grip = if (bothHands) evaluateGrip() else null
        updateHoldState(grip, anyUsable, dt)

        // ---- Controller placement -----------------------------------------------------------
        val target = if (holdState == HoldState.HOLDING) 1f else 0f
        val rate = if (target > controllerBlend) dt / BLEND_IN_S else dt / BLEND_OUT_S
        controllerBlend = if (target > controllerBlend) min(target, controllerBlend + rate)
        else max(target, controllerBlend - rate)

        if (holdState == HoldState.HOLDING) {
            updateControllerTarget()
        } else {
            rotationInitialized = false
        }
        if (controllerBlend > 0.001f) {
            applyFilteredPose(dt)
        }

        // ---- Write the rigs -----------------------------------------------------------------
        if (handUsable[HAND_LEFT]) solveHand(HAND_LEFT)
        if (handUsable[HAND_RIGHT]) solveHand(HAND_RIGHT)
        applyControllerTransform(dt)

        if (nowMs - lastLogMs > 4000L) {
            lastLogMs = nowMs
            Log.i(
                TAG,
                statusLine() + " dPalm=${fmt(grip?.palmSpan ?: -1f)} dThumb=${fmt(grip?.thumbSpan ?: -1f)} " +
                        "viewer=${viewerPose.t.y}"
            )
        }
    }

    private fun fmt(value: Float): String = "%.3f".format(value)

    private fun updateHoldState(grip: GripEval?, anyUsable: Boolean, dt: Float) {
        val eval = grip
        when (holdState) {
            HoldState.IDLE -> {
                if (eval != null && eval.enter) {
                    holdCandidateMs += dt * 1000f
                    if (holdCandidateMs >= ENTER_HOLD_MS) {
                        holdState = HoldState.HOLDING
                        lostHandsMs = 0f
                        Log.i(TAG, "Enter HOLDING_CONTROLLER (${eval.describe()})")
                    }
                } else {
                    holdCandidateMs = max(0f, holdCandidateMs - dt * 1500f)
                }
            }
            HoldState.HOLDING -> {
                if (!anyUsable) {
                    lostHandsMs += dt * 1000f
                    if (lostHandsMs >= EXIT_LOST_MS) {
                        holdState = HoldState.IDLE
                        Log.i(TAG, "Exit HOLDING_CONTROLLER (hands lost ${lostHandsMs.toInt()} ms)")
                    }
                } else {
                    lostHandsMs = 0f
                    if (eval != null && eval.exit) {
                        holdState = HoldState.IDLE
                        Log.i(TAG, "Exit HOLDING_CONTROLLER (${eval.describe()})")
                    }
                }
            }
        }
    }

    // =========================================================================================
    // Entity plumbing
    // =========================================================================================

    /**
     * Creates the three mesh entities. A missing or malformed GLB must never take the app down, so
     * each creation is guarded and permanently disabled on failure (the hand-tracking logs still tell
     * us whether the OpenXR plumbing itself is healthy).
     */
    private fun createEntities() {
        if (leftHandEntity == null && !meshFailed[0]) {
            leftHandEntity = tryCreateMeshEntity(LEFT_HAND_URI, LEFT_HAND_ASSET, "left GTA hand")
            if (leftHandEntity == null) meshFailed[0] = true
        }
        if (rightHandEntity == null && !meshFailed[1]) {
            rightHandEntity = tryCreateMeshEntity(RIGHT_HAND_URI, RIGHT_HAND_ASSET, "right GTA hand")
            if (rightHandEntity == null) meshFailed[1] = true
        }
        if (controllerEntity == null && !meshFailed[2]) {
            controllerEntity = tryCreateMeshEntity(CONTROLLER_URI, CONTROLLER_ASSET, "Xbox controller")
            if (controllerEntity == null) meshFailed[2] = true
        }
    }

    private fun tryCreateMeshEntity(uri: Uri, assetPath: String, label: String): Entity? {
        val present = try {
            assetExists(assetPath)
        } catch (t: Throwable) {
            false
        }
        if (!present) {
            Log.e(
                TAG,
                "Asset '$assetPath' is missing from the APK; skipping $label " +
                        "(run scripts/build_gta_hands_and_controller_glb.py)"
            )
            return null
        }
        return try {
            Entity.create(
                listOf(
                    Mesh(uri),
                    Transform(Pose()),
                    Scale(Vector3(1f, 1f, 1f)),
                    Visible(false)
                )
            ).also { Log.i(TAG, "Created $label entity ($uri)") }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create $label entity from $uri: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun refreshSceneObjects() {
        if (leftHandObject == null) {
            leftHandObject = leftHandEntity?.let { sceneObjectProvider(it) }
            leftHandObject?.let { bindHandRig(HAND_LEFT, it) }
        }
        if (rightHandObject == null) {
            rightHandObject = rightHandEntity?.let { sceneObjectProvider(it) }
            rightHandObject?.let { bindHandRig(HAND_RIGHT, it) }
        }
        if (controllerObject == null) {
            controllerObject = controllerEntity?.let { sceneObjectProvider(it) }
            controllerObject?.let { bindControllerRig(it) }
        }
    }

    private fun nodeIndexOrMinusOne(so: SceneObject, name: String): Int = try {
        so.getNodeIndex(name)
    } catch (t: Throwable) {
        -1
    }

    private fun bindHandRig(hand: Int, so: SceneObject) {
        if (handRigReady[hand]) return
        val nodeOfJoint = IntArray(HandTrackerJni.JOINT_COUNT + 1) { -1 }
        var found = 0
        for (j in 0 until HandTrackerJni.JOINT_COUNT) {
            val idx = nodeIndexOrMinusOne(so, HAND_NODE_NAMES[j])
            nodeOfJoint[j] = idx
            if (idx >= 0) found++
        }
        val forearmIdx = nodeIndexOrMinusOne(so, HAND_NODE_NAMES[NODE_FOREARM])
        nodeOfJoint[NODE_FOREARM] = forearmIdx
        if (forearmIdx >= 0) found++
        if (found == 0) return  // GLTFLoader is still streaming the mesh; retry next tick.

        // Build a compact slot table so the per-frame batch call needs no allocation.
        val order = ArrayList<Int>(found)
        for (j in 0 until HandTrackerJni.JOINT_COUNT) if (nodeOfJoint[j] >= 0) order.add(j)
        if (forearmIdx >= 0) order.add(NODE_FOREARM)
        handSlotJoint[hand] = IntArray(order.size) { order[it] }
        handSlotNode[hand] = IntArray(order.size) { nodeOfJoint[order[it]] }
        handBatchFloats[hand] = FloatArray(order.size * 7)
        handRigReady[hand] = true

        Log.i(TAG, "Bound ${if (hand == HAND_LEFT) "left" else "right"} hand rig: $found/${HAND_NODE_NAMES.size} nodes")
        if (found < HAND_NODE_NAMES.size) {
            val missing = HAND_NODE_NAMES.filterIndexed { i, _ -> nodeOfJoint[i] < 0 }
            Log.w(TAG, "Hand rig is missing nodes: $missing")
        }
    }

    private fun bindControllerRig(so: SceneObject) {
        if (controllerRigReady) return
        val nodes = IntArray(CONTROLLER_NODE_NAMES.size) { nodeIndexOrMinusOne(so, CONTROLLER_NODE_NAMES[it]) }
        val found = nodes.count { it >= 0 }
        if (found == 0) return
        val slots = ArrayList<Int>(found)
        for (i in nodes.indices) if (nodes[i] >= 0) slots.add(i)
        ctrlSlotName = IntArray(slots.size) { slots[it] }
        ctrlSlotNode = IntArray(slots.size) { nodes[slots[it]] }
        ctrlBatchFloats = FloatArray(slots.size * 7)
        controllerRigReady = true
        Log.i(TAG, "Bound controller rig: $found/${CONTROLLER_NODE_NAMES.size} nodes")
        if (found < CONTROLLER_NODE_NAMES.size) {
            Log.w(
                TAG,
                "Controller rig is missing nodes: " +
                        CONTROLLER_NODE_NAMES.filterIndexed { i, _ -> nodes[i] < 0 }
            )
        }
    }

    private fun hideAll() {
        setVisible(0, false)
        setVisible(1, false)
        setVisible(2, false)
        handUsable.fill(false)
    }

    private fun setVisible(which: Int, visible: Boolean) {
        when (which) {
            0 -> {
                if (leftHandVisible == visible) return
                leftHandEntity?.setComponent(Visible(visible))
                leftHandVisible = visible
            }
            1 -> {
                if (rightHandVisible == visible) return
                rightHandEntity?.setComponent(Visible(visible))
                rightHandVisible = visible
            }
            else -> {
                if (controllerVisible == visible) return
                controllerEntity?.setComponent(Visible(visible))
                controllerVisible = visible
            }
        }
    }

    private fun updateHandVisibility() {
        setVisible(0, handUsable[HAND_LEFT])
        setVisible(1, handUsable[HAND_RIGHT])
        if (handLogCount == 0 && (handUsable[0] || handUsable[1])) {
            handLogCount = 1
            Log.i(TAG, "First GTA hand frame drawn (L=${handUsable[0]} R=${handUsable[1]})")
        }
    }

    private fun updateControllerVisibility() {
        setVisible(2, controllerBlend > 0.02f)
    }

    // =========================================================================================
    // Hand solving
    // =========================================================================================

    private fun copyJoints(hand: Int) {
        val f = handSource.frame
        for (j in 0 until HandTrackerJni.JOINT_COUNT) {
            worldPos[hand][j] = f.pos[hand][j]
            worldRot[hand][j] = f.rot[hand][j]
        }
    }

    /**
     * Builds the palm frame from joint positions only:
     * `+Y` = wristâ†’knuckles, `+Z` = dorsal (back of the hand), `+X` = `+Y x +Z`.
     *
     * The anatomical sign of `side x forward` flips between hands, hence [handSign].
     */
    private fun computeHandBasis(hand: Int): Boolean {
        val f = handSource.frame
        if (!f.hasJoint(hand, J_WRIST) || !f.hasJoint(hand, J_PALM) ||
            !f.hasJoint(hand, J_INDEX_PROX) || !f.hasJoint(hand, J_MIDDLE_PROX) ||
            !f.hasJoint(hand, J_RING_PROX) || !f.hasJoint(hand, J_LITTLE_PROX) ||
            !f.hasJoint(hand, J_INDEX_MC) || !f.hasJoint(hand, J_LITTLE_MC)
        ) {
            return false
        }
        val wrist = f.pos[hand][J_WRIST]
        val knuckles = (f.pos[hand][J_INDEX_PROX] + f.pos[hand][J_MIDDLE_PROX] +
                f.pos[hand][J_RING_PROX] + f.pos[hand][J_LITTLE_PROX]) * 0.25f
        val forward = knuckles - wrist
        if (forward.length() < 1e-4f) return false
        val side = f.pos[hand][J_LITTLE_MC] - f.pos[hand][J_INDEX_MC]
        if (side.length() < 1e-4f) return false
        // Meta Spatial SDK world space is left-handed (+X right, +Y up, +Z forward), so the
        // algebraic cross product side x forward flips sign vs right-handed OpenXR.
        val handSign = if (hand == HAND_RIGHT) -1f else 1f
        val dorsalRaw = side.normalize().cross(forward.normalize()) * handSign
        if (dorsalRaw.length() < 1e-5f) return false
        val fwd = forward.normalize()
        val dorsal = dorsalRaw.normalize()
        handForward[hand] = fwd
        handDorsal[hand] = dorsal
        return true
    }

    /**
     * Derives a **position-only** world orientation for every hand joint.
     *
     * Done before the grip blend so `worldRot[hand][metacarpal]` is our own basis (fingers = `+Y`,
     * dorsal = `+Z`) rather than Meta's arbitrary joint-orientation convention, and again afterwards
     * so the blended positions re-drive the roll. Cheap and idempotent.
     */
    private fun rebuildHandOrientations(hand: Int) {
        if (!handUsable[hand]) return
        val pos = worldPos[hand]
        val rot = worldRot[hand]
        val fwd = handForward[hand]
        val dorsal = handDorsal[hand]

        rot[J_WRIST] = quatFromBasis(fwd.cross(dorsal).normalize(), fwd, dorsal)

        // Collapse any joint the runtime did not report onto its parent so a single invalid joint can
        // never fling a segment off to the world origin.
        val valid = handSource.frame.valid[hand]
        for (k in 1 until JOINT_ORDER.size) {
            val j = JOINT_ORDER[k]
            val p = JOINT_PARENT[j]
            if (p < 0 || p == J_WRIST) continue
            if (!valid[j]) pos[j] = pos[p]
        }

        for (k in 1 until JOINT_ORDER.size) {
            val j = JOINT_ORDER[k]
            val p = JOINT_PARENT[j]
            if (p < 0) continue
            val parentDir = rot[p] * v(0f, 1f, 0f)
            val next = JOINT_NEXT[j]
            val dir = if (next >= 0) {
                val d = pos[next] - pos[j]
                if (d.length() > 1e-5f) d.normalize() else parentDir
            } else {
                parentDir
            }
            rot[j] = shortestArc(parentDir, dir) * rot[p]
        }

        // PALM shares the wrist frame; its mesh is authored with the origin at the wrist joint.
        rot[J_PALM] = rot[J_WRIST]
        pos[J_PALM] = pos[J_WRIST]
    }

    /**
     * Drives Meta's official 25-joint skinned OpenXR hand GLB (`oculus-hand`, where all 25 joint
     * nodes are flat siblings under `Armature`).
     *
     * Exact coordinate proof:
     *   - Meta's GLTFLoader imports `oculus-hand` vertices & `inverseBindMatrices` via X-reflection
     *     `M_x = diag(-1, +1, +1)`.
     *   - OpenXR STAGE maps to Meta Spatial SDK world space via Z-reflection `M_z = diag(+1, +1, -1)`.
     *   - Since `M_z * RotY(180°) = M_x`, setting:
     *       ent.Transform = Pose(wristPos_sdk, Identity)
     *       localP        = pos_sdk - wristPos_sdk                  [= M_z * (p_xr - wrist_xr)]
     *       localQ        = (M_z * q_xr * M_z) * RotY(180°)         [= M_z * R_xr * M_x]
     *     makes `M_local * IBM_imported * v_imported = M_z * (R_xr * IBM_xr * v_xr)` so `M_x * M_x`
     *     cancels cleanly to identity: zero smashing, exact forward direction, exact joint bending!
     */
    private fun solveHand(hand: Int) {
        val ent = if (hand == HAND_LEFT) leftHandEntity else rightHandEntity
        val so = if (hand == HAND_LEFT) leftHandObject else rightHandObject
        if (ent == null || so == null || !handRigReady[hand] || !handUsable[hand]) return

        val pos = worldPos[hand]
        val rot = worldRot[hand]
        val valid = handSource.frame.valid[hand]
        val wristPos = pos[J_WRIST]

        // Meta's GLTFLoader places a RotY(180°) on the glTF scene root above Armature, which flips
        // child node localP = (dx, dy, dz) to (-dx, +dy, -dz). Putting rotY180 on the entity root
        // cancels that root flip so localP = (p - wristPos) lands at the exact real-world joint pos.
        val rotY180 = Quaternion(0f, 0f, 1f, 0f)
        ent.setComponent(Transform(Pose(wristPos, rotY180)))

        val slots = handSlotJoint[hand]
        val floats = handBatchFloats[hand]
        for (s in slots.indices) {
            val j = slots[s]
            if (j >= HandTrackerJni.JOINT_COUNT) {
                writeBatch(floats, s, Pose())
                continue
            }
            val p = if (valid[j]) pos[j] else wristPos
            val qXr = if (valid[j]) rot[j] else rot[J_WRIST]
            val localP = p - wristPos
            // GLTFLoader imports vertices/IBMs under Armature with M_z = diag(+1,+1,-1) and places
            // RotY(180°) on the scene root (cancelled by ent.Transform = rotY180 above).
            // Therefore localQ = M_z * R_xr * M_z = (w, -x, -y, z) matches IBM_armature = M_z*IBM*M_z
            // with zero bone twist or knuckle kink.
            val localQ = Quaternion(qXr.w, -qXr.x, -qXr.y, qXr.z).normalize()
            writeBatch(floats, s, Pose(localP, localQ))
        }
        if (slots.isEmpty()) return
        try {
            so.setLocalNodeTransformsBatch(
                handSlotNode[hand], floats, SceneObject.TRANSFORM_TRANSLATION_ROTATION
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setLocalNodeTransformsBatch(hand) failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun writeBatch(floats: FloatArray, slot: Int, pose: Pose) {
        val o = slot * 7
        floats[o] = pose.t.x
        floats[o + 1] = pose.t.y
        floats[o + 2] = pose.t.z
        // Verified from Meta Spatial SDK 0.15.0 SceneObject.setLocalNodePoses bytecode:
        // setLocalNodeTransformsBatch expects quaternion components in (w, x, y, z) order!
        floats[o + 3] = pose.q.w
        floats[o + 4] = pose.q.x
        floats[o + 5] = pose.q.y
        floats[o + 6] = pose.q.z
    }

    // =========================================================================================
    // Grip detection
    // =========================================================================================

    private class GripEval(
        val enter: Boolean,
        val exit: Boolean,
        val palmSpan: Float,
        val thumbSpan: Float,
        val inwardness: Float,
        val thumbRise: Float
    ) {
        fun describe(): String =
            "dPalm=${"%.3f".format(palmSpan)} dThumb=${"%.3f".format(thumbSpan)} " +
                    "inward=${"%.2f".format(inwardness)} thumbRise=${"%.3f".format(thumbRise)}"
    }

    private fun evaluateGrip(): GripEval {
        val f = handSource.frame
        val palmL = f.pos[HAND_LEFT][J_PALM]
        val palmR = f.pos[HAND_RIGHT][J_PALM]
        val wristL = f.pos[HAND_LEFT][J_WRIST]
        val wristR = f.pos[HAND_RIGHT][J_WRIST]
        val thumbTipL = f.pos[HAND_LEFT][J_THUMB_TIP]
        val thumbTipR = f.pos[HAND_RIGHT][J_THUMB_TIP]

        val span = palmR - palmL
        val palmSpan = span.length()
        val thumbSpan = (thumbTipR - thumbTipL).length()

        var inward = 0f
        if (palmSpan > 1e-4f) {
            val d = span.normalize()
            val left = (-handDorsal[HAND_LEFT]).dot(d)
            val right = (-handDorsal[HAND_RIGHT]).dot(d * -1f)
            inward = (left + right) * 0.5f
        }

        val thumbsMid = (thumbTipL + thumbTipR) * 0.5f
        val wristsMid = (wristL + wristR) * 0.5f
        var upHint = handDorsal[HAND_LEFT] + handDorsal[HAND_RIGHT]
        if (upHint.length() < 1e-4f) upHint = v(0f, 1f, 0f)
        val thumbRise = (thumbsMid - wristsMid).dot(upHint.normalize())

        val spanOk = palmSpan in PALM_SPAN_MIN_M..PALM_SPAN_MAX_M
        val thumbOk = thumbSpan in THUMB_SPAN_MIN_M..THUMB_SPAN_MAX_M
        val postureOk = thumbRise > -0.030f
        val inwardOk = inward > 0.05f
        val score = (if (spanOk) 1 else 0) + (if (thumbOk) 1 else 0) +
                (if (postureOk) 1 else 0) + (if (inwardOk) 1 else 0)

        val enter = spanOk && thumbOk && score >= 3
        val exit = palmSpan > PALM_SPAN_EXIT_M || thumbRise < -0.090f
        return GripEval(enter, exit, palmSpan, thumbSpan, inward, thumbRise)
    }

    // =========================================================================================
    // Controller pose
    // =========================================================================================

    private fun updateControllerTarget() {
        val f = handSource.frame
        if (!handUsable[HAND_LEFT] || !handUsable[HAND_RIGHT]) return

        val anchorL = f.pos[HAND_LEFT][J_PALM].lerp(f.pos[HAND_LEFT][J_THUMB_MC], 0.45f)
        val anchorR = f.pos[HAND_RIGHT][J_PALM].lerp(f.pos[HAND_RIGHT][J_THUMB_MC], 0.45f)

        val rightRaw = anchorR - anchorL
        if (rightRaw.length() < 1e-4f) return
        val rightVec = rightRaw.normalize()

        // Wrists -> thumbs points FORWARD toward the TV (triggers direction = -Z in GLB).
        val thumbsMid = (f.pos[HAND_LEFT][J_THUMB_TIP] + f.pos[HAND_RIGHT][J_THUMB_TIP]) * 0.5f
        val wristsMid = (f.pos[HAND_LEFT][J_WRIST] + f.pos[HAND_RIGHT][J_WRIST]) * 0.5f
        var fwd = thumbsMid - wristsMid
        fwd = fwd - rightVec * fwd.dot(rightVec)
        if (fwd.length() < 1e-4f) return
        fwd = fwd.normalize()

        // In Meta Spatial SDK's left-handed world (+X right, +Y up, +Z forward):
        // algebraic fwd x rightVec = (0,0,1) x (1,0,0) = (0, +1, 0) = UP (face normal)!
        var up = fwd.cross(rightVec)
        if (up.y < 0f) up = up * -1f
        if (up.length() < 1e-4f) up = v(0f, 1f, 0f) else up = up.normalize()
        // Blend slightly with world-up so slight thumb pitch doesn't over-tilt the controller
        var y = up * 0.72f + v(0f, 1f, 0f) * 0.28f
        y = (y - rightVec * y.dot(rightVec))
        if (y.length() < 1e-4f) return
        y = y.normalize()

        // +Z in GLB = grips (toward player's chest = -fwd = y x rightVec).
        // Meta's GLTFLoader negates X on import, so local +X = player's LEFT (-rightVec),
        // making (x = -rightVec, y = up, z = chest) a det=+1 rotation with ABXY on the right,
        // triggers facing the TV, and face buttons pointing straight up toward your eyes!
        // Tilt the top edge ~18 deg toward the player so the thumbsticks sit squarely under the thumbs.
        val zBase = y.cross(rightVec).normalize()
        val cosT = 0.9511f  // cos(18 deg)
        val sinT = 0.3090f  // sin(18 deg)
        val yTilted = (y * cosT + zBase * sinT).normalize()
        val z = (zBase * cosT - y * sinT).normalize()
        val x = yTilted.cross(z).normalize()

        val mid = (anchorL + anchorR) * 0.5f
        val center = mid +
                yTilted * (CTRL_UP_OFFSET_M + controllerCalibration.y) +
                z * (CTRL_BACK_OFFSET_M + controllerCalibration.z) +
                rightVec * (CTRL_SIDE_OFFSET_M + controllerCalibration.x)

        ctrlTargetPose = Pose(center, quatFromBasis(x, yTilted, z))
    }

    private fun applyFilteredPose(dt: Float) {
        val target = ctrlTargetPose
        val center = v(
            panFilters[0].filter(target.t.x, dt),
            panFilters[1].filter(target.t.y, dt),
            panFilters[2].filter(target.t.z, dt)
        )
        if (!rotationInitialized) {
            filteredRotation = target.q
            rotationInitialized = true
        } else {
            val angularSpeed = angularChangeDeg(filteredRotation, target.q) / max(dt, 1e-4f)
            val tau = lerp(0.040f, 0.006f, (angularSpeed / 420f).coerceIn(0f, 1f))
            val alpha = (1f - exp(-dt / tau)).coerceIn(0f, 1f)
            filteredRotation = filteredRotation.slerp(target.q, alpha)
        }
        ctrlPose = Pose(center, filteredRotation)
    }

    /**
     * Pulls the fingers that the physical controller occludes toward an ergonomic grip pose so they
     * stop flickering, while leaving the wrists/forearms and (mostly) the thumbs on live CV data.
     */
    private fun blendHandsToGrip() {
        for (hand in 0 until 2) {
            if (!handUsable[hand]) continue
            for (fi in FINGER_MC.indices) {
                val mc = FINGER_MC[fi]
                var p = worldPos[hand][mc]
                var q = worldRot[hand][mc]
                val lengths = FINGER_LENGTHS[fi]
                val curls = GRIP_CURL[fi]
                val weight = (controllerBlend * FINGER_GRIP_WEIGHT[fi]).coerceIn(0f, 1f)
                if (weight <= 0.001f) continue
                for (s in 0 until 3) {
                    q = q * Quaternion.fromAxisAngle(v(1f, 0f, 0f), -curls[s])
                    p = p + q * v(0f, lengths[s], 0f)
                    val joint = mc + 1 + s
                    worldPos[hand][joint] = worldPos[hand][joint].lerp(p, weight)
                    worldRot[hand][joint] = worldRot[hand][joint].slerp(q, weight)
                }
                val tip = mc + 4
                val tipPos = p + q * v(0f, lengths[2] * 0.6f, 0f)
                worldPos[hand][tip] = worldPos[hand][tip].lerp(tipPos, weight)
                worldRot[hand][tip] = worldRot[hand][tip].slerp(q, weight)
            }
        }
    }

    // =========================================================================================
    // Controller node animation
    // =========================================================================================

    private fun applyControllerTransform(dt: Float) {
        val ent = controllerEntity ?: return
        val visible = controllerBlend > 0.02f
        updateControllerVisibility()
        if (!visible) return

        val s = 0.72f + 0.28f * controllerBlend
        controllerScale = s
        ent.setComponent(Transform(ctrlPose))
        ent.setComponent(Scale(v(s, s, s)))

        val so = controllerObject ?: return
        if (!controllerRigReady) return

        updateStickVisuals(dt)
        updateTriggerVisuals(dt)
        updateButtonVisuals(dt)

        val floats = ctrlBatchFloats
        for (slot in ctrlSlotName.indices) {
            writeBatch(floats, slot, controllerNodeLocal(ctrlSlotName[slot]))
        }
        if (ctrlSlotNode.isEmpty()) return
        try {
            so.setLocalNodeTransformsBatch(
                ctrlSlotNode, floats, SceneObject.TRANSFORM_TRANSLATION_ROTATION
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setLocalNodeTransformsBatch(controller) failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun controllerNodeLocal(nameIndex: Int): Pose {
        val id = Quaternion()
        val base = CTRL_NODE_REST_POS[nameIndex]
        return when (CONTROLLER_NODE_NAMES[nameIndex]) {
            "LS_Stick" -> Pose(
                base,
                Quaternion.fromAxisAngle(v(1f, 0f, 0f), stickLTiltZ) *
                        Quaternion.fromAxisAngle(v(0f, 0f, 1f), -stickLTiltX)
            )
            "RS_Stick" -> Pose(
                base,
                Quaternion.fromAxisAngle(v(1f, 0f, 0f), stickRTiltZ) *
                        Quaternion.fromAxisAngle(v(0f, 0f, 1f), -stickRTiltX)
            )
            "LT" -> Pose(base, Quaternion.fromAxisAngle(v(1f, 0f, 0f), -30f * leftTriggerVisual))
            "RT" -> Pose(base, Quaternion.fromAxisAngle(v(1f, 0f, 0f), -30f * rightTriggerVisual))
            "LB" -> Pose(base + v(0f, -0.0035f * bumperLVisual, 0f), id)
            "RB" -> Pose(base + v(0f, -0.0035f * bumperRVisual, 0f), id)
            "DpadUp" -> Pose(base + v(0f, -0.0028f * dpadPressVisual[0], 0f), id)
            "DpadDown" -> Pose(base + v(0f, -0.0028f * dpadPressVisual[1], 0f), id)
            "DpadLeft" -> Pose(base + v(0f, -0.0028f * dpadPressVisual[2], 0f), id)
            "DpadRight" -> Pose(base + v(0f, -0.0028f * dpadPressVisual[3], 0f), id)
            "BtnA" -> Pose(base + v(0f, -0.0032f * buttonPressVisual[0], 0f), id)
            "BtnB" -> Pose(base + v(0f, -0.0032f * buttonPressVisual[1], 0f), id)
            "BtnX" -> Pose(base + v(0f, -0.0032f * buttonPressVisual[2], 0f), id)
            "BtnY" -> Pose(base + v(0f, -0.0032f * buttonPressVisual[3], 0f), id)
            else -> Pose(base, id)
        }
    }

    private fun updateStickVisuals(dt: Float) {
        var tiltLX = 0f
        var tiltLZ = 0f
        var tiltRX = 0f
        var tiltRZ = 0f

        if (GamepadVisualState.hasRecentInput(15000L)) {
            tiltLX = GamepadVisualState.leftStickX() * STICK_TILT_MAX_DEG
            tiltLZ = GamepadVisualState.leftStickY() * STICK_TILT_MAX_DEG
            tiltRX = GamepadVisualState.rightStickX() * STICK_TILT_MAX_DEG
            tiltRZ = GamepadVisualState.rightStickY() * STICK_TILT_MAX_DEG
        } else {
            // CV inverse kinematics: tilt each virtual stick toward the real thumb tip. Only the
            // horizontal (face-plane) offset matters â€” the thumb hovers above the stick cap.
            val localL = thumbInControllerSpace(HAND_LEFT)
            if (localL != null && localL.y > FACE_MIN_Y_M) {
                val dx = localL.x - STICK_L_LOCAL.x
                val dz = localL.z - STICK_L_LOCAL.z
                if (hypot(dx, dz) < STICK_GRAB_RADIUS_M * 2.0f) {
                    tiltLX = (dx / STICK_GRAB_RADIUS_M).coerceIn(-1f, 1f) * STICK_TILT_MAX_DEG
                    tiltLZ = (dz / STICK_GRAB_RADIUS_M).coerceIn(-1f, 1f) * STICK_TILT_MAX_DEG
                }
            }
            val localR = thumbInControllerSpace(HAND_RIGHT)
            if (localR != null && localR.y > FACE_MIN_Y_M) {
                val dx = localR.x - STICK_R_LOCAL.x
                val dz = localR.z - STICK_R_LOCAL.z
                if (hypot(dx, dz) < STICK_GRAB_RADIUS_M * 2.0f) {
                    tiltRX = (dx / STICK_GRAB_RADIUS_M).coerceIn(-1f, 1f) * STICK_TILT_MAX_DEG
                    tiltRZ = (dz / STICK_GRAB_RADIUS_M).coerceIn(-1f, 1f) * STICK_TILT_MAX_DEG
                }
            }
        }
        stickLTiltX = smooth(stickLTiltX, tiltLX, dt)
        stickLTiltZ = smooth(stickLTiltZ, tiltLZ, dt)
        stickRTiltX = smooth(stickRTiltX, tiltRX, dt)
        stickRTiltZ = smooth(stickRTiltZ, tiltRZ, dt)
    }

    private fun updateTriggerVisuals(dt: Float) {
        var lt = 0f
        var rt = 0f
        var lb = 0f
        var rb = 0f
        if (GamepadVisualState.hasRecentInput(15000L)) {
            lt = GamepadVisualState.leftTrigger()
            rt = GamepadVisualState.rightTrigger()
            lb = if (GamepadVisualState.bumperL()) 1f else 0f
            rb = if (GamepadVisualState.bumperR()) 1f else 0f
        } else {
            // Visible INDEX_PROXIMAL knuckle flex only â€” the distal joints are behind the trigger.
            lt = knuckleFlex(HAND_LEFT)
            rt = knuckleFlex(HAND_RIGHT)
        }
        leftTriggerVisual = smooth(leftTriggerVisual, lt, dt)
        rightTriggerVisual = smooth(rightTriggerVisual, rt, dt)
        bumperLVisual = smooth(bumperLVisual, lb, dt)
        bumperRVisual = smooth(bumperRVisual, rb, dt)
    }

    private fun updateButtonVisuals(dt: Float) {
        val a = floatArrayOf(0f, 0f, 0f, 0f)
        val d = floatArrayOf(0f, 0f, 0f, 0f)
        if (GamepadVisualState.hasRecentInput(15000L)) {
            a[0] = if (GamepadVisualState.buttonA()) 1f else 0f
            a[1] = if (GamepadVisualState.buttonB()) 1f else 0f
            a[2] = if (GamepadVisualState.buttonX()) 1f else 0f
            a[3] = if (GamepadVisualState.buttonY()) 1f else 0f
            d[0] = if (GamepadVisualState.dpadUp()) 1f else 0f
            d[1] = if (GamepadVisualState.dpadDown()) 1f else 0f
            d[2] = if (GamepadVisualState.dpadLeft()) 1f else 0f
            d[3] = if (GamepadVisualState.dpadRight()) 1f else 0f
        } else {
            val thumbR = thumbInControllerSpace(HAND_RIGHT)
            if (thumbR != null && thumbR.y > FACE_MIN_Y_M) {
                for (i in BTN_TARGETS.indices) {
                    if (planarDistance(thumbR, BTN_TARGETS[i]) < BUTTON_PRESS_RADIUS_M) a[i] = 1f
                }
                if (planarDistance(thumbR, DPAD_LOCAL) < BUTTON_PRESS_RADIUS_M * 1.3f) {
                    val dx = thumbR.x - DPAD_LOCAL.x
                    val dz = thumbR.z - DPAD_LOCAL.z
                    if (abs(dx) > abs(dz)) {
                        if (dx > 0f) d[3] = 1f else d[2] = 1f
                    } else {
                        // +Z points at the player, so a thumb past the D-pad centre is "down".
                        if (dz > 0f) d[1] = 1f else d[0] = 1f
                    }
                }
            }
        }
        for (i in 0 until 4) {
            buttonPressVisual[i] = smooth(buttonPressVisual[i], a[i], dt)
            dpadPressVisual[i] = smooth(dpadPressVisual[i], d[i], dt)
        }
    }

    /** Distance in the controller's face plane (+X/+Z), ignoring how high the thumb hovers. */
    private fun planarDistance(a: Vector3, b: Vector3): Float = hypot(a.x - b.x, a.z - b.z)

    private fun thumbInControllerSpace(hand: Int): Vector3? {
        if (!handUsable[hand] || controllerBlend <= 0.3f) return null
        // Divide out the pop-in Scale on the controller entity so picked positions are in metres.
        val raw = Pose(ctrlPose.t, ctrlPose.q).inverse() * handSource.frame.pos[hand][J_THUMB_TIP]
        val local = raw.div(if (controllerScale > 1e-3f) controllerScale else 1f)
        return if (local.length() > 1f) null else local
    }

    /** 0..1 from how far the index knuckle has folded, ignoring the occluded distal joints. */
    private fun knuckleFlex(hand: Int): Float {
        if (!handUsable[hand]) return 0f
        val mc = handSource.frame.pos[hand][J_INDEX_MC]
        val prox = handSource.frame.pos[hand][J_INDEX_PROX]
        val dir = prox - mc
        if (dir.length() < 1e-4f) return 0f
        val straight = dir.normalize().dot(handForward[hand])
        return ((1f - straight) / 0.9f).coerceIn(0f, 1f)
    }

    // =========================================================================================
    // Math helpers
    // =========================================================================================

    private fun smooth(current: Float, target: Float, dt: Float): Float {
        val tau = 0.035f
        val alpha = (1f - exp(-dt / tau)).coerceIn(0f, 1f)
        return current + (target - current) * alpha
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun angularChangeDeg(a: Quaternion, b: Quaternion): Float {
        val dot = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceIn(0f, 1f)
        return (2.0 * acos(dot.toDouble()) * 180.0 / Math.PI).toFloat()
    }

    private fun quatFromBasis(x: Vector3, y: Vector3, z: Vector3): Quaternion {
        val m00 = x.x; val m01 = y.x; val m02 = z.x
        val m10 = x.y; val m11 = y.y; val m12 = z.y
        val m20 = x.z; val m21 = y.z; val m22 = z.z
        val trace = m00 + m11 + m22
        val w: Float
        val qx: Float
        val qy: Float
        val qz: Float
        if (trace > 0f) {
            val s = kotlin.math.sqrt(trace + 1f) * 2f
            w = 0.25f * s
            qx = (m21 - m12) / s
            qy = (m02 - m20) / s
            qz = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
            w = (m21 - m12) / s
            qx = 0.25f * s
            qy = (m01 + m10) / s
            qz = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
            w = (m02 - m20) / s
            qx = (m01 + m10) / s
            qy = 0.25f * s
            qz = (m12 + m21) / s
        } else {
            val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
            w = (m10 - m01) / s
            qx = (m02 + m20) / s
            qy = (m12 + m21) / s
            qz = 0.25f * s
        }
        return Quaternion(w, qx, qy, qz).normalize()
    }

    private fun shortestArc(from: Vector3, to: Vector3): Quaternion {
        if (from.length() < 1e-6f || to.length() < 1e-6f) return Quaternion()
        val f = from.normalize()
        val t = to.normalize()
        val d = f.dot(t).coerceIn(-1f, 1f)
        if (d > 0.99999f) return Quaternion()
        if (d < -0.99999f) {
            var axis = v(1f, 0f, 0f).cross(f)
            if (axis.length() < 1e-3f) axis = v(0f, 1f, 0f).cross(f)
            if (axis.length() < 1e-3f) return Quaternion()
            return Quaternion.fromAxisAngle(axis.normalize(), 180f)
        }
        val axis = f.cross(t).normalize()
        val angleDeg = (Math.toDegrees(acos(d.toDouble()))).toFloat()
        return Quaternion.fromAxisAngle(axis, angleDeg)
    }
}

/**
 * Classic One-Euro filter (Casiez et al.): low jitter at rest, low lag when moving fast.
 * Used for the virtual controller translation so it does not shimmer in your lap.
 */
internal class OneEuroFilter(
    private val minCutoff: Float = 1.8f,
    private val beta: Float = 0.04f,
    private val derivativeCutoff: Float = 1.0f
) {
    private var filteredValue = Float.NaN
    private var filteredDerivative = 0f

    fun reset() {
        filteredValue = Float.NaN
        filteredDerivative = 0f
    }

    fun filter(value: Float, dtSec: Float): Float {
        val dt = dtSec.coerceIn(1.0e-4f, 0.1f)
        if (filteredValue.isNaN()) {
            filteredValue = value
            filteredDerivative = 0f
            return value
        }
        val derivative = (value - filteredValue) / dt
        val dAlpha = alpha(derivativeCutoff, dt)
        filteredDerivative += (derivative - filteredDerivative) * dAlpha
        val cutoff = minCutoff + beta * abs(filteredDerivative)
        val a = alpha(cutoff, dt)
        filteredValue += (value - filteredValue) * a
        return filteredValue
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * max(cutoff, 1.0e-4f))
        return (dt / (dt + tau)).coerceIn(0f, 1f)
    }
}

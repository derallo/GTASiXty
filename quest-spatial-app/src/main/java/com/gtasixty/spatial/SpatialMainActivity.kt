package com.gtasixty.spatial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Lut
import com.meta.spatial.core.PerformanceLevel
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.isdk.IsdkBoxCollider
import com.meta.spatial.isdk.IsdkDefaultCursorSystem
import com.meta.spatial.isdk.IsdkGrabMovementType
import com.meta.spatial.isdk.IsdkGrabState
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.isdk.IsdkPanelGrabHandle
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.isdk.ResizeCornerState
import com.meta.spatial.isdk.ResizeMode
import com.meta.spatial.isdk.updateIsdkComponentProperties
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.LayerFilters
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.runtime.PointerEvent
import com.meta.spatial.runtime.PointerEventType
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SemanticType
import com.meta.spatial.toolkit.ActivityPanelRegistration
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.CylinderShapeOptions
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.UIPanelShapeOptions
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.InputSystem
import com.meta.spatial.vr.VRFeature
import com.gtasixty.spatial.voice.JimmySpatialPanel
import kotlin.math.abs
import kotlin.math.acos

/**
 * Meta Spatial SDK Passthrough MR host activity for GTA-Sixty.
 *
 * Critical swapchain invariant: ALWAYS keep `mips = 1`, `enableLayer = true`,
 * `forceSceneTexture = false`, and `fragmentShader = ""` so PanelSurface remains on
 * OpenXR's zero-copy AndroidSurfaceSwapchain instead of ImageReaderSwapchain (which
 * stalls Horizon's `endFrameJob` and hangs on 3 pulsing dots).
 */
class SpatialMainActivity : AppSystemActivity(), SpatialDisplayBridge.SpatialListener {

    companion object {
        private const val TAG = "GTA60Spatial"
        private const val ACTION_SET_DISPLAY = "com.gtasixty.spatial.SET_DISPLAY"
        private const val ACTION_STOP_STREAM = "com.gtasixty.spatial.STOP_STREAM"

        // 1600x900 dp scaled by panelScale DPI (256->2560x1440, 320->3200x1800, 384->3840x2160).
        private const val PANEL_DP_W = 1600f
        private const val PANEL_DP_H = 900f
        private const val MIN_RESHAPE_INTERVAL_MS = 45L
        private const val CONTROLLER_HIDE_IDLE_MS = 5000L
        private const val USER_RECENTER_DELAY_MS = 1500L

        private const val HANDLE_BOTTOM_GAP_M = 0.065f
        private val HANDLE_COLLIDER_SIZE = Vector3(0.56f, 0.14f, 0.08f)
        private val HANDLE_RAY_HALF_EXTENTS = Vector3(0.30f, 0.09f, 0.06f)
        private val HANDLE_COLOR_IDLE = Color4(0.88f, 0.93f, 1.0f, 0.85f)
        private val HANDLE_COLOR_HOVER = Color4(0.35f, 0.88f, 1.0f, 0.98f)
        private val HANDLE_COLOR_DRAG = Color4(0.18f, 0.95f, 0.65f, 1.0f)
        private const val HANDLE_VISUAL_IDLE = 0
        private const val HANDLE_VISUAL_HOVER = 1
        private const val HANDLE_VISUAL_DRAG = 2

        // Real GTA V Jimmy's Room 3D scene constants (must match scripts/build_real_jimmys_room_glb.py)
        private val JIMMYS_ROOM_URI: Uri = Uri.parse("scenes/jimmys_room.glb")
        private const val JIMMYS_ROOM_EYE_HEIGHT_M = 1.56f
        private const val JIMMYS_ROOM_TV_WIDTH_M = 2.100f
        private val JIMMYS_ROOM_TV_LOCAL_POS = Vector3(0f, 1.750f, 2.420f)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var panelEntity: Entity? = null
    private var jimmysRoomEntity: Entity? = null
    private var roomOrigin = Vector3(0f, 0f, 0f)
    private var grabHandleEntity: Entity? = null
    private var grabHandleVisualEntity: Entity? = null
    private var grabHandleMaterial: Material? = null
    private var grabHandleVisualState = -1
    private var handleObserverHandle: IsdkSystem.InteractableObserverHandle? = null
    private var jimmyPanel: JimmySpatialPanel? = null
    private var sceneReady = false

    private var appliedCurvePct = -1
    private var appliedWidthMeters = -1f
    private var appliedRadiusMeters = 0f
    private var appliedPanelScale = -1
    private val defaultGrabHandleColor: Vector4 by lazy { IsdkPanelGrabHandle().color }
    private var currentSettings = SpatialDisplayBridge.DisplaySettings()
    private var anchorCenter = Vector3(0f, 1.35f, 2.2f)
    private var anchorYaw = Quaternion()
    private var lastWrittenPose: Pose? = null

    private var pendingReshape = false
    private var lastReshapeMs = 0L
    private var reshapeScheduled = false

    private var needsInitialRecenter = true
    private var initialRecenterDeadlineMs = 0L
    private var pendingUserRecenter = false
    private var userRecenterAtMs = 0L
    private var wasCornerResizing = false
    private var wasGrabbing = false
    private var wasHandleGrabbing = false
    private val hoveredHandleSources = HashSet<Entity>()
    private var rayHoveredHandle = false
    private val prevTriggerDown = HashMap<Long, Boolean>()
    private var triggerDragSource: Entity? = null
    private var triggerDragDist = 2.2f
    private var triggerDragOffset = Vector3(0f, 0f, 0f)
    private var lastSceneTickMs = 0L
    private var lastViewerPos: Vector3? = null
    private var lastViewerHorizFwd: Vector3? = null
    private var lastControllerActivityMs = 0L
    private var controllerVisualsVisible = true

    // GTA V hands + auto-spawning Xbox controller (see GTA_HANDS_CONTROLLER_PLAN.md).
    private var handTracking: HandTrackingSource? = null
    private var gtaHands: GtaHandsControllerSystem? = null
    private var gtaHandsInitAttempted = false
    private var avatarHandsVisible = true
    private var pointerRaysVisible = true

    private val reshapeRunnable = Runnable {
        reshapeScheduled = false
        flushPendingReshape()
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (ACTION_STOP_STREAM == intent.action) {
                SpatialDisplayBridge.stopActiveStream()
                Log.i(TAG, "Debug broadcast: stopActiveStream")
                return
            }
            if (ACTION_SET_DISPLAY != intent.action) return
            if (intent.getBooleanExtra("reset", false)) {
                SpatialDisplayBridge.applyFromUi(
                    this@SpatialMainActivity,
                    SpatialDisplayBridge.DisplaySettings()
                )
                SpatialDisplayBridge.requestRecenter()
                return
            }
            val updated = SpatialDisplayBridge.getSettings()
            if (intent.hasExtra("curve")) updated.curvePct = intent.getIntExtra("curve", updated.curvePct)
            if (intent.hasExtra("tilt")) updated.tiltDeg = intent.getFloatExtra("tilt", updated.tiltDeg)
            if (intent.hasExtra("height")) updated.heightOffsetM = intent.getFloatExtra("height", updated.heightOffsetM)
            if (intent.hasExtra("width")) updated.widthMeters = intent.getFloatExtra("width", updated.widthMeters)
            if (intent.hasExtra("distance")) updated.distanceMeters = intent.getFloatExtra("distance", updated.distanceMeters)
            if (intent.hasExtra("backdrop")) updated.backdropMode = intent.getIntExtra("backdrop", updated.backdropMode)
            if (intent.hasExtra("panelScale")) updated.panelScale = intent.getIntExtra("panelScale", updated.panelScale)
            if (intent.hasExtra("taa")) updated.taaEnabled = intent.getBooleanExtra("taa", updated.taaEnabled)
            if (intent.hasExtra("computeFlow")) updated.computeFlowEnabled = intent.getBooleanExtra("computeFlow", updated.computeFlowEnabled)
            if (intent.hasExtra("hands")) updated.gtaHandsEnabled = intent.getBooleanExtra("hands", updated.gtaHandsEnabled)
            if (intent.hasExtra("handsUp")) updated.gtaHandsCtrlUpM = intent.getFloatExtra("handsUp", updated.gtaHandsCtrlUpM)
            if (intent.hasExtra("handsBack")) updated.gtaHandsCtrlBackM = intent.getFloatExtra("handsBack", updated.gtaHandsCtrlBackM)
            if (intent.hasExtra("autoHideMenu")) {
                updated.autoHideMenuOnController = intent.getBooleanExtra("autoHideMenu", updated.autoHideMenuOnController)
            }
            SpatialDisplayBridge.applyFromUi(this@SpatialMainActivity, updated)
            if (intent.getBooleanExtra("recenter", false)) {
                SpatialDisplayBridge.requestRecenter()
            }
        }
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))

    override fun registerPanels(): List<PanelRegistration> {
        val initial = SpatialDisplayBridge.load(this)
        return listOf(
            ActivityPanelRegistration(
                registrationId = R.id.main_stream_panel,
                classIdCreator = { MainActivity::class.java },
                settingsCreator = {
                    val s = SpatialDisplayBridge.getSettings()
                    object : com.meta.spatial.toolkit.PanelSettings {
                        override fun toPanelConfigOptions(): PanelConfigOptions {
                            val w = if (s.backdropMode == SpatialDisplayBridge.BACKDROP_JIMMYS_ROOM) JIMMYS_ROOM_TV_WIDTH_M else s.widthMeters
                            val c = if (s.backdropMode == SpatialDisplayBridge.BACKDROP_JIMMYS_ROOM) 0 else s.curvePct
                            return buildPanelConfigOptions(w, c, s.panelScale)
                        }
                    }
                }
            ),
            JimmySpatialPanel.createRegistration()
        ).also {
            val inRoom = initial.backdropMode == SpatialDisplayBridge.BACKDROP_JIMMYS_ROOM
            appliedCurvePct = if (inRoom) 0 else initial.curvePct
            appliedWidthMeters = if (inRoom) JIMMYS_ROOM_TV_WIDTH_M else initial.widthMeters
            appliedRadiusMeters = SpatialDisplayBridge.cylinderRadiusMeters(appliedWidthMeters, appliedCurvePct)
            appliedPanelScale = initial.panelScale
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        currentSettings = SpatialDisplayBridge.load(this)
        SpatialDisplayBridge.setPendingLaunchExtras(intent?.extras)
        SpatialDisplayBridge.setSpatialListener(this)
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction(ACTION_SET_DISPLAY)
            addAction(ACTION_STOP_STREAM)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(debugReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(debugReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.extras != null) {
            SpatialDisplayBridge.setPendingLaunchExtras(intent.extras)
        }
    }

    override fun onResume() {
        super.onResume()
        if (sceneReady) {
            applyPerformanceBoost()
            needsInitialRecenter = true
            initialRecenterDeadlineMs = SystemClock.uptimeMillis() + 600L
            Log.i(TAG, "onResume: scheduled panel recenter")
        }
    }

    override fun onVRReady() {
        super.onVRReady()
        if (sceneReady) {
            applyPerformanceBoost()
            needsInitialRecenter = true
            initialRecenterDeadlineMs = SystemClock.uptimeMillis() + 600L
            Log.i(TAG, "onVRReady: scheduled panel recenter")
        }
    }

    override fun onSceneReady() {
        super.onSceneReady()
        sceneReady = true

        applyPerformanceBoost()
        applyDisplayRefreshRate(SpatialDisplayBridge.getRequestedRefreshHz())
        applyBackdrop(currentSettings.backdropMode)
        lastControllerActivityMs = SystemClock.uptimeMillis()

        val minH = SpatialDisplayBridge.MIN_WIDTH_M / SpatialDisplayBridge.ASPECT_RATIO
        val maxH = SpatialDisplayBridge.MAX_WIDTH_M / SpatialDisplayBridge.ASPECT_RATIO
        val startInRoom = isJimmysRoomActive()
        appliedCurvePct = if (startInRoom) 0 else currentSettings.curvePct
        appliedWidthMeters = if (startInRoom) JIMMYS_ROOM_TV_WIDTH_M else currentSettings.widthMeters
        appliedPanelScale = currentSettings.panelScale
        appliedRadiusMeters = SpatialDisplayBridge.cylinderRadiusMeters(
            appliedWidthMeters,
            appliedCurvePct
        )

        computeRecenterAnchor(currentSettings.distanceMeters)
        val initialPose = composePanelPose(
            anchorCenter,
            anchorYaw,
            if (startInRoom) 0f else currentSettings.tiltDeg,
            if (startInRoom) 0f else currentSettings.heightOffsetM,
            appliedRadiusMeters
        )
        lastWrittenPose = initialPose

        val ent = Entity.createPanelEntity(
            R.id.main_stream_panel,
            Transform(initialPose),
            Grabbable(!startInRoom, GrabbableType.PIVOT_Y),
            IsdkPanelGrabHandle(),
            IsdkPanelDimensions(
                Vector2(
                    appliedWidthMeters,
                    appliedWidthMeters / SpatialDisplayBridge.ASPECT_RATIO
                )
            ),
            IsdkPanelResize(
                !startInRoom && appliedCurvePct == 0,
                ResizeMode.Simple,
                Vector2(SpatialDisplayBridge.MIN_WIDTH_M, minH),
                Vector2(SpatialDisplayBridge.MAX_WIDTH_M, maxH),
                true
            )
        )
        panelEntity = ent
        syncHandleComponentsForCurvature(ent, appliedCurvePct > 0)

        val handlePose = computeGrabHandlePose()
        val handleEnt = Entity.create(
            listOf(
                Transform(handlePose),
                IsdkBoxCollider(HANDLE_COLLIDER_SIZE, Vector3(0f, 0f, 0f)),
                IsdkGrabbable(
                    enabled = true,
                    grabState = IsdkGrabState.NotGrabbed,
                    movementType = IsdkGrabMovementType.AxialBillboard
                ),
                Hittable(MeshCollision.LineTest),
                Visible(true)
            )
        )
        grabHandleEntity = handleEnt

        val handleMat = Material().apply {
            baseColor = HANDLE_COLOR_IDLE
            unlit = true
            alphaMode = AlphaMode.TRANSLUCENT.ordinal
        }
        grabHandleMaterial = handleMat
        grabHandleVisualState = HANDLE_VISUAL_IDLE
        grabHandleVisualEntity = Entity.create(
            listOf(
                Mesh(Box.URI),
                Box(Vector3(-0.22f, -0.014f, -0.008f), Vector3(0.22f, 0.014f, 0.008f)),
                handleMat,
                Transform(handlePose),
                Hittable(MeshCollision.NoCollision),
                Visible(true)
            )
        )

        handleObserverHandle = systemManager.tryFindSystem<IsdkSystem>()
            ?.registerInteractableObserver(handleEnt) { event ->
                onHandlePointerEvent(event)
            }

        jimmyPanel = JimmySpatialPanel().also { it.createEntity() }

        if (startInRoom) {
            computeJimmysRoomAnchor()
            applyJimmysRoomPoses()
        } else {
            updateJimmyFloatingPose()
        }

        needsInitialRecenter = true
        initialRecenterDeadlineMs = SystemClock.uptimeMillis() + 1500L
        val pxW = SpatialDisplayBridge.panelPxW(currentSettings.panelScale)
        val pxH = SpatialDisplayBridge.panelPxH(currentSettings.panelScale)
        val dpi = SpatialDisplayBridge.panelDpi(currentSettings.panelScale)
        Log.i(
            TAG,
            "Scene ready: 16:9 panel (${pxW}x${pxH} @ ${dpi}dpi) entityPos=${initialPose.t} screenCenter=$anchorCenter radius=${appliedRadiusMeters}m"
        )
    }

    override fun onSceneTick() {
        super.onSceneTick()
        val ent = panelEntity ?: return
        val now = SystemClock.uptimeMillis()
        val dtSec = if (lastSceneTickMs > 0L) ((now - lastSceneTickMs) / 1000.0f).coerceIn(0.001f, 0.05f) else 0.011f
        lastSceneTickMs = now

        val viewer = scene.getViewerPose()
        val hasRealHead = viewer.t.length() > 0.01f || abs(viewer.q.w - 1f) > 0.001f

        val rawViewerFwd = viewer.forward()
        val horizViewerFwd = Vector3(rawViewerFwd.x, 0f, rawViewerFwd.z).let {
            if (it.length() > 1e-4f) it.normalize() else null
        }

        if (needsInitialRecenter) {
            if (hasRealHead || now >= initialRecenterDeadlineMs) {
                needsInitialRecenter = false
                lastViewerPos = viewer.t
                lastViewerHorizFwd = horizViewerFwd
                recenterPanelNow()
            }
        } else if (pendingUserRecenter && now >= userRecenterAtMs) {
            pendingUserRecenter = false
            lastViewerPos = viewer.t
            lastViewerHorizFwd = horizViewerFwd
            recenterPanelNow()
        } else if (hasRealHead && !wasGrabbing && !wasCornerResizing && !wasHandleGrabbing && triggerDragSource == null) {
            val prevPos = lastViewerPos
            val prevFwd = lastViewerHorizFwd
            lastViewerPos = viewer.t
            lastViewerHorizFwd = horizViewerFwd
            val headJump = if (prevPos != null) (viewer.t - prevPos).length() else 0f
            val yawJumpDeg = if (prevFwd != null && horizViewerFwd != null) {
                val dot = (prevFwd.x * horizViewerFwd.x + prevFwd.z * horizViewerFwd.z).coerceIn(-1f, 1f)
                Math.toDegrees(acos(dot.toDouble())).toFloat()
            } else {
                0f
            }
            val distToAnchor = (viewer.t - anchorCenter).length()
            val maxReachableDist = maxOf(currentSettings.distanceMeters + 3.5f, 7.5f)
            if (headJump > 0.35f || yawJumpDeg > 15.0f || distToAnchor > maxReachableDist) {
                Log.i(
                    TAG,
                    "Meta-button recenter / origin jump detected (headJump=${headJump}m yawJump=${yawJumpDeg}deg dist=${distToAnchor}m); auto-recentering"
                )
                recenterPanelNow()
            }
        }

        syncHandleComponentsForCurvature(ent, appliedCurvePct > 0)

        updateGtaHands(now, dtSec, viewer, hasRealHead)

        if (isJimmysRoomActive()) {
            wasCornerResizing = false
            wasGrabbing = false
            wasHandleGrabbing = false
            triggerDragSource = null
            rayHoveredHandle = false
            updateControllerVisibility()
            jimmyPanel?.syncVisibility()
            return
        }

        val pso = getPanelSceneObject(ent)
        if (appliedCurvePct == 0) {
            val resizeComp = ent.tryGetComponent<IsdkPanelResize>()
            if (resizeComp != null) {
                val active = resizeComp.activeResizeCorner != ResizeCornerState.NONE
                if (active) {
                    wasCornerResizing = true
                } else if (wasCornerResizing) {
                    wasCornerResizing = false
                    val scaleComp = ent.tryGetComponent<Scale>()
                    val sc = scaleComp?.scale ?: Vector3(1f, 1f, 1f)
                    if (abs(sc.x - 1f) > 0.005f || abs(sc.y - 1f) > 0.005f) {
                        val baseW = if (appliedWidthMeters > 0f) appliedWidthMeters else currentSettings.widthMeters
                        val newW = (baseW * sc.x).coerceIn(
                            SpatialDisplayBridge.MIN_WIDTH_M,
                            SpatialDisplayBridge.MAX_WIDTH_M
                        )
                        val unit = Vector3(1f, 1f, 1f)
                        ent.setComponent(Scale(unit))
                        pso?.setScale(unit)
                        currentSettings.widthMeters = newW
                        appliedWidthMeters = newW
                        pendingReshape = false
                        reshapePanelNow(newW, currentSettings.curvePct, currentSettings.panelScale)
                        SpatialDisplayBridge.notifySpatialPanelResized(this, newW)
                        Log.i(
                            TAG,
                            "Corner resize locked 16:9 -> width=${newW}m height=${newW / SpatialDisplayBridge.ASPECT_RATIO}m"
                        )
                    }
                }
            }
        } else {
            wasCornerResizing = false
        }

        val handleEnt = grabHandleEntity
        val triggerMask = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
        val pushMask = ButtonBits.ButtonThumbLU or ButtonBits.ButtonThumbRU
        val pullMask = ButtonBits.ButtonThumbLD or ButtonBits.ButtonThumbRD
        rayHoveredHandle = false
        val handlePose = handleEnt?.tryGetComponent<Transform>()?.transform

        for (cEnt in Query.where { has(Controller.id, Transform.id) }.eval()) {
            val c = cEnt.tryGetComponent<Controller>() ?: continue
            val triggerDown = (c.buttonState and triggerMask) != 0
            val wasDown = prevTriggerDown[cEnt.id] ?: false
            prevTriggerDown[cEnt.id] = triggerDown

            val cPose = cEnt.tryGetComponent<Transform>()?.transform ?: continue
            if (handlePose != null) {
                val rawFwd = cPose.forward()
                val rayDir = if (rawFwd.length() > 1e-4f) rawFwd.normalize() else Vector3(0f, 0f, 1f)
                val hitT = intersectRayHandleObb(cPose.t, rayDir, handlePose, HANDLE_RAY_HALF_EXTENTS)
                if (hitT != null) {
                    rayHoveredHandle = true
                    if (triggerDown && !wasDown && triggerDragSource == null && !wasHandleGrabbing && !wasGrabbing) {
                        beginTriggerDrag(cEnt, cPose.t + rayDir * hitT)
                    }
                }
            }
        }

        val dragSrc = triggerDragSource
        if (dragSrc != null) {
            val srcCtrl = dragSrc.tryGetComponent<Controller>()
            if (srcCtrl != null && (srcCtrl.buttonState and triggerMask) == 0) {
                endTriggerDrag()
            } else {
                if (srcCtrl != null) {
                    if ((srcCtrl.buttonState and pushMask) != 0) {
                        triggerDragDist = (triggerDragDist + 1.4f * dtSec).coerceIn(
                            SpatialDisplayBridge.MIN_DIST_M,
                            SpatialDisplayBridge.MAX_DIST_M
                        )
                    } else if ((srcCtrl.buttonState and pullMask) != 0) {
                        triggerDragDist = (triggerDragDist - 1.4f * dtSec).coerceIn(
                            SpatialDisplayBridge.MIN_DIST_M,
                            SpatialDisplayBridge.MAX_DIST_M
                        )
                    }
                }
                val srcPose = dragSrc.tryGetComponent<Transform>()?.transform
                if (srcPose != null) {
                    val rawFwd = srcPose.forward()
                    val rayDir = if (rawFwd.length() > 1e-4f) rawFwd.normalize() else Vector3(0f, 0f, 1f)
                    val targetHandlePos = srcPose.t + rayDir * triggerDragDist + triggerDragOffset
                    val toTarget = Vector3(targetHandlePos.x - viewer.t.x, 0f, targetHandlePos.z - viewer.t.z)
                    if (toTarget.length() > 0.05f) {
                        anchorYaw = Quaternion.lookRotationAroundY(toTarget.normalize())
                    }
                    val rot = anchorYaw * Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), currentSettings.tiltDeg)
                    val up = Pose(Vector3(0f, 0f, 0f), rot).up()
                    val panelW = if (appliedWidthMeters > 0f) appliedWidthMeters else currentSettings.widthMeters
                    val panelH = panelW / SpatialDisplayBridge.ASPECT_RATIO
                    val visualCenter = targetHandlePos + up * (panelH * 0.5f + HANDLE_BOTTOM_GAP_M)
                    anchorCenter = Vector3(
                        visualCenter.x,
                        visualCenter.y - currentSettings.heightOffsetM,
                        visualCenter.z
                    )
                    applyCurrentPose(ent)
                }
            }
        }

        val isPanelGrabbed = triggerDragSource == null && (
            (ent.tryGetComponent<IsdkGrabbable>()?.grabState == IsdkGrabState.Grabbed) ||
                (ent.tryGetComponent<Grabbable>()?.isGrabbed == true)
            )
        if (isPanelGrabbed) {
            wasGrabbing = true
            syncAnchorFromEntity(ent)
            val hp = computeGrabHandlePose()
            grabHandleEntity?.setComponent(Transform(hp))
            grabHandleVisualEntity?.setComponent(Transform(hp))
            updateJimmyFloatingPose()
        } else if (wasGrabbing) {
            wasGrabbing = false
            syncAnchorFromEntity(ent)
            applyCurrentPose(ent)
        }

        val isHandleGrabbed = triggerDragSource == null && handleEnt != null && (
            (handleEnt.tryGetComponent<IsdkGrabbable>()?.grabState == IsdkGrabState.Grabbed) ||
                (handleEnt.tryGetComponent<Grabbable>()?.isGrabbed == true)
            )
        if (isHandleGrabbed && handleEnt != null) {
            wasHandleGrabbing = true
            syncAnchorFromGrabHandle(handleEnt)
            applyPanelPoseOnly(ent)
            handleEnt.tryGetComponent<Transform>()?.transform?.let { hp ->
                grabHandleVisualEntity?.setComponent(Transform(hp))
            }
        } else if (wasHandleGrabbing && handleEnt != null) {
            wasHandleGrabbing = false
            syncAnchorFromGrabHandle(handleEnt)
            applyCurrentPose(ent)
        }

        updateControllerVisibility()
        updateGrabHandleVisualState()
        jimmyPanel?.syncVisibility()
    }

    override fun onDisplaySettingsChanged(settings: SpatialDisplayBridge.DisplaySettings) {
        val prev = currentSettings
        currentSettings = SpatialDisplayBridge.DisplaySettings(settings)
        if (!sceneReady) return

        val backdropChanged = prev.backdropMode != currentSettings.backdropMode
        if (backdropChanged) {
            applyBackdrop(currentSettings.backdropMode)
        }

        if (prev.gtaHandsEnabled != currentSettings.gtaHandsEnabled) {
            gtaHands?.setEnabled(currentSettings.gtaHandsEnabled)
            if (!currentSettings.gtaHandsEnabled) {
                syncAvatarHandVisibility()
                syncPointerRayVisibility()
            }
        }

        if (isJimmysRoomActive()) {
            if (abs(prev.distanceMeters - currentSettings.distanceMeters) > 0.001f ||
                abs(prev.heightOffsetM - currentSettings.heightOffsetM) > 0.002f
            ) {
                applyJimmysRoomPoses()
            }
            if (currentSettings.panelScale != appliedPanelScale ||
                appliedCurvePct != 0 ||
                abs(appliedWidthMeters - JIMMYS_ROOM_TV_WIDTH_M) > 0.005f
            ) {
                scheduleReshape()
            }
            return
        }

        val ent = panelEntity
        if (ent != null) {
            if (abs(prev.distanceMeters - currentSettings.distanceMeters) > 0.001f) {
                updateAnchorDistance(currentSettings.distanceMeters)
                applyCurrentPose(ent)
            } else if (abs(prev.tiltDeg - currentSettings.tiltDeg) > 0.05f ||
                abs(prev.heightOffsetM - currentSettings.heightOffsetM) > 0.002f
            ) {
                syncAnchorFromEntity(ent)
                applyCurrentPose(ent)
            }
        }

        if (currentSettings.curvePct != appliedCurvePct ||
            abs(currentSettings.widthMeters - appliedWidthMeters) > 0.005f ||
            currentSettings.panelScale != appliedPanelScale
        ) {
            scheduleReshape()
        }
    }

    override fun onRecenterRequested() {
        if (!sceneReady) return
        needsInitialRecenter = false
        pendingUserRecenter = true
        userRecenterAtMs = SystemClock.uptimeMillis() + USER_RECENTER_DELAY_MS
        Log.i(TAG, "Scheduled user recenter in ${USER_RECENTER_DELAY_MS}ms")
    }

    override fun onRefreshRateRequested(hz: Int) {
        if (!sceneReady) return
        applyDisplayRefreshRate(hz)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            GamepadVisualState.onKeyEvent(event)
            if (SpatialDisplayBridge.dispatchGamepadKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            GamepadVisualState.onMotionEvent(event)
            if (SpatialDisplayBridge.dispatchGamepadMotion(event)) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(reshapeRunnable)
        SpatialDisplayBridge.setVirtualXboxControllerVisible(false)
        gtaHands?.shutdown()
        gtaHands = null
        handTracking = null
        handleObserverHandle?.let { h ->
            systemManager.tryFindSystem<IsdkSystem>()?.unregisterInteractableObserver(h)
        }
        handleObserverHandle = null
        jimmyPanel?.destroy()
        jimmyPanel = null
        SpatialDisplayBridge.setSpatialListener(null)
        try {
            unregisterReceiver(debugReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildPanelConfigOptions(widthM: Float, curvePct: Int, panelScale: Int): PanelConfigOptions {
        val heightM = widthM / SpatialDisplayBridge.ASPECT_RATIO
        val pxW = SpatialDisplayBridge.panelPxW(panelScale)
        val pxH = SpatialDisplayBridge.panelPxH(panelScale)
        val dpi = SpatialDisplayBridge.panelDpi(panelScale)
        val shape: UIPanelShapeOptions = if (curvePct <= 0) {
            QuadShapeOptions(widthM, heightM)
        } else {
            val radius = SpatialDisplayBridge.cylinderRadiusMeters(widthM, curvePct)
            CylinderShapeOptions(radius, widthM, heightM)
        }
        val settings = UIPanelSettings(
            shape = shape,
            display = DpDisplayOptions(PANEL_DP_W, PANEL_DP_H, dpi),
            rendering = UIPanelRenderOptions(
                PanelRenderMode.Layer(
                    layerBlendType = PanelShapeLayerBlendType.OPAQUE,
                    enableLayerFeatheredEdge = false,
                    filters = LayerFilters.HIGHEST_QUALITY
                )
            )
        )
        return settings.toPanelConfigOptions().apply {
            layoutWidthInPx = pxW
            layoutHeightInPx = pxH
            layoutDpi = dpi
            alphaMode = AlphaMode.OPAQUE
            mips = 1 // Keep strictly = 1 for zero-copy AndroidSurfaceSwapchain!
            enableLayer = true
            forceSceneTexture = false
        }
    }

    private fun scheduleReshape() {
        pendingReshape = true
        val elapsed = SystemClock.uptimeMillis() - lastReshapeMs
        if (elapsed >= MIN_RESHAPE_INTERVAL_MS && !reshapeScheduled) {
            flushPendingReshape()
        } else if (!reshapeScheduled) {
            reshapeScheduled = true
            mainHandler.postDelayed(reshapeRunnable, (MIN_RESHAPE_INTERVAL_MS - elapsed).coerceAtLeast(1L))
        }
    }

    private fun flushPendingReshape() {
        if (!pendingReshape) return
        pendingReshape = false
        lastReshapeMs = SystemClock.uptimeMillis()
        val inRoom = isJimmysRoomActive()
        val w = if (inRoom) JIMMYS_ROOM_TV_WIDTH_M else currentSettings.widthMeters
        val curve = if (inRoom) 0 else currentSettings.curvePct
        val scale = currentSettings.panelScale
        val shapeChanged = (curve != appliedCurvePct) || abs(w - appliedWidthMeters) > 0.005f
        val scaleChanged = (scale != appliedPanelScale)
        appliedWidthMeters = w
        appliedCurvePct = curve
        appliedPanelScale = scale
        reshapePanelNow(w, curve, scale, shapeChanged, scaleChanged)
        if (inRoom) {
            applyJimmysRoomPoses()
        }
    }

    @OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
    private fun reshapePanelNow(
        widthM: Float,
        curvePct: Int,
        panelScale: Int,
        shapeChanged: Boolean = true,
        scaleChanged: Boolean = false
    ) {
        val ent = panelEntity ?: return
        val pso = getPanelSceneObject(ent) ?: return
        val inRoom = isJimmysRoomActive()
        if (shapeChanged) {
            if (!inRoom) {
                syncAnchorFromEntity(ent)
            }
            val sc = ent.tryGetComponent<Scale>()?.scale
            if (sc != null && (abs(sc.x - 1f) > 0.001f || abs(sc.y - 1f) > 0.001f || abs(sc.z - 1f) > 0.001f)) {
                val unit = Vector3(1f, 1f, 1f)
                ent.setComponent(Scale(unit))
                pso.setScale(unit)
            }
            val newCfg = buildPanelConfigOptions(widthM, curvePct, panelScale)
            val newRadius = if (curvePct > 0) newCfg.radiusForCylinderOrSphere else 0f
            appliedRadiusMeters = newRadius
            pso.reshape(newCfg)
            pso.clearCrop()
            pso.updateIsdkComponentProperties(ent)
            syncHandleComponentsForCurvature(ent, curvePct > 0)
            if (inRoom) {
                applyJimmysRoomPoses()
            } else {
                applyCurrentPose(ent)
            }
        }
        if (scaleChanged) {
            // PanelSceneObject.reshape() only recreates PanelShape and keeps the old VirtualDisplay
            // backing surface. Call resize(pxW, pxH, dpi) so VirtualDevice matches SurfaceView 1:1
            // and never introduces a Y-offset / bottom crop after runtime panelScale changes.
            val pxW = SpatialDisplayBridge.panelPxW(panelScale)
            val pxH = SpatialDisplayBridge.panelPxH(panelScale)
            val dpi = SpatialDisplayBridge.panelDpi(panelScale)
            pso.resize(pxW, pxH, dpi, null)
            pso.clearCrop()
            if (inRoom) {
                applyJimmysRoomPoses()
            }
        }
        Log.i(
            TAG,
            "Reshaped panel: width=${widthM}m height=${widthM / SpatialDisplayBridge.ASPECT_RATIO}m curve=$curvePct% scale=${SpatialDisplayBridge.panelScaleLabel(panelScale)} radius=${appliedRadiusMeters}m shapeChanged=$shapeChanged scaleChanged=$scaleChanged entityPos=${lastWrittenPose?.t} screenCenter=$anchorCenter resizeEnabled=${curvePct == 0}"
        )
    }

    private fun syncHandleComponentsForCurvature(ent: Entity, curved: Boolean) {
        val inRoom = isJimmysRoomActive()
        val allowResize = !curved && !inRoom
        val resizeComp = ent.tryGetComponent<IsdkPanelResize>()
        if (resizeComp != null && resizeComp.enabled != allowResize) {
            resizeComp.enabled = allowResize
            ent.setComponent(resizeComp)
        }
        val grabHandle = ent.tryGetComponent<IsdkPanelGrabHandle>()
        if (grabHandle != null) {
            val targetColor = if (controllerVisualsVisible && !inRoom) defaultGrabHandleColor else Vector4(0f, 0f, 0f, 0f)
            if (grabHandle.color != targetColor) {
                grabHandle.color = targetColor
                ent.setComponent(grabHandle)
            }
        }
    }

    private fun getPanelSceneObject(ent: Entity): PanelSceneObject? {
        return sceneObjectFor(ent) as? PanelSceneObject
    }

    private fun sceneObjectFor(ent: Entity): SceneObject? {
        val sos = systemManager.tryFindSystem<SceneObjectSystem>() ?: return null
        return sos.getSceneObject(ent)?.getNow(null)
    }

    /** True when a bundled asset exists (checks the APK asset table, not the network). */
    private fun assetExists(path: String): Boolean = try {
        assets.open(path).use { true }
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------------------------------------------
    // GTA V hands + auto-spawning Xbox controller
    // ---------------------------------------------------------------------------------------------

    /**
     * Lazily creates the hand-tracking backend and the GTA hands/controller system.
     *
     * Native OpenXR init is deferred until the headset reports a real viewer pose because the
     * reference-space inference (STAGE vs LOCAL) compares the viewer's eye height against zero.
     */
    private fun initGtaHands(viewer: Pose) {
        gtaHandsInitAttempted = true
        val source = HandTrackingSource()
        source.initNative(scene, viewer)
        if (source.backend != HandTrackingSource.Backend.OPENXR) {
            source.enableSdkFallback(scene)
        }
        handTracking = source
        val system = GtaHandsControllerSystem(scene, source, { ent -> sceneObjectFor(ent) }, { path -> assetExists(path) })
        system.setEnabled(currentSettings.gtaHandsEnabled)
        system.controllerCalibration = Vector3(
            0f,
            currentSettings.gtaHandsCtrlUpM,
            currentSettings.gtaHandsCtrlBackM
        )
        gtaHands = system
        Log.i(
            TAG,
            "GTA hands system ready: backend=${source.backendLabel} enabled=${system.enabled} " +
                    "native=${source.isNativeAvailable} viewer=${viewer.t}"
        )
    }

    private fun updateGtaHands(now: Long, dtSec: Float, viewer: Pose, hasRealHead: Boolean) {
        val settings = currentSettings
        if (!settings.gtaHandsEnabled) {
            gtaHands?.setEnabled(false)
            syncPointerRayVisibility()
            return
        }
        if (gtaHands == null) {
            if (gtaHandsInitAttempted) return
            initGtaHands(viewer)
        }
        val system = gtaHands ?: return
        if (hasRealHead) {
            handTracking?.refineSpace(viewer)
        }
        if (!system.enabled) {
            system.setEnabled(true)
        }
        system.controllerCalibration = Vector3(
            0f,
            settings.gtaHandsCtrlUpM,
            settings.gtaHandsCtrlBackM
        )
        system.tick(now, dtSec, viewer)
        syncAvatarHandVisibility()
        syncPointerRayVisibility()
    }

    /**
     * Hides Meta Spatial SDK hand/controller pointer rays & cursors while the virtual Xbox
     * controller is visible, and restores them as soon as the controller is put away.
     */
    private fun syncPointerRayVisibility() {
        val controllerShown = gtaHands?.isControllerVisible() == true
        val desired = !controllerShown
        if (pointerRaysVisible == desired) return
        pointerRaysVisible = desired
        SpatialDisplayBridge.setVirtualXboxControllerVisible(controllerShown)
        systemManager.tryFindSystem<IsdkDefaultCursorSystem>()?.enableInput(desired)
        systemManager.tryFindSystem<InputSystem>()?.enableInput(desired)
        Log.i(TAG, "Pointer rays visible=$desired (virtualController=$controllerShown)")
    }

    /**
     * Keeps Meta's translucent hand meshes hidden while our GTA hands are on screen.
     *
     * While the GTA Hands feature is enabled but our own rig is *not* being drawn, Meta's default
     * hands are deliberately left visible: they double as a "hand tracking is alive" indicator, and
     * they prove whether a missing-hand problem is ours or the runtime's.
     */
    private fun syncAvatarHandVisibility() {
        val gtaActive = gtaHands?.isDrawingHands() == true
        val desired = !gtaActive && (currentSettings.gtaHandsEnabled || controllerVisualsVisible)
        setAvatarHandsVisible(desired)
    }

    /**
     * Cached wrapper around [AvatarSystem.setShowHands] so we do not have to read the SDK property
     * back (its Kotlin metadata marks the backing property private).
     */
    private fun setAvatarHandsVisible(visible: Boolean) {
        if (avatarHandsVisible == visible) return
        val avatarSystem = systemManager.tryFindSystem<AvatarSystem>() ?: return
        avatarSystem.setShowHands(visible)
        avatarHandsVisible = visible
    }

    private fun isJimmysRoomActive(): Boolean {
        return currentSettings.backdropMode == SpatialDisplayBridge.BACKDROP_JIMMYS_ROOM
    }

    private fun ensureJimmysRoomEntity(): Entity {
        val existing = jimmysRoomEntity
        if (existing != null) return existing
        val ent = Entity.create(
            listOf(
                Mesh(JIMMYS_ROOM_URI),
                Transform(Pose(roomOrigin, anchorYaw)),
                Hittable(MeshCollision.NoCollision),
                Visible(true)
            )
        )
        jimmysRoomEntity = ent
        Log.i(TAG, "Created Jimmy's Room 3D scene entity ($JIMMYS_ROOM_URI)")
        return ent
    }

    private fun computeJimmysRoomAnchor() {
        val viewer = scene.getViewerPose()
        val rawFwd = viewer.forward()
        val horiz = Vector3(rawFwd.x, 0f, rawFwd.z)
        val dir = if (horiz.length() > 1e-4f) horiz.normalize() else Vector3(0f, 0f, 1f)
        val eyePos = if (viewer.t.length() > 1e-4f) viewer.t else Vector3(0f, JIMMYS_ROOM_EYE_HEIGHT_M, 0f)
        anchorYaw = Quaternion.lookRotationAroundY(dir)
        roomOrigin = Vector3(eyePos.x, eyePos.y - JIMMYS_ROOM_EYE_HEIGHT_M, eyePos.z)
    }

    private fun applyJimmysRoomPoses() {
        val roomEnt = ensureJimmysRoomEntity()
        val fwd = Pose(Vector3(0f, 0f, 0f), anchorYaw).forward()
        val depthOffset = (currentSettings.distanceMeters - SpatialDisplayBridge.DEFAULT_DIST_M)
            .coerceIn(-1.10f, 1.15f)
        val effectiveRoomOrigin = Vector3(
            roomOrigin.x + fwd.x * depthOffset,
            roomOrigin.y + currentSettings.heightOffsetM,
            roomOrigin.z + fwd.z * depthOffset
        )
        roomEnt.setComponent(Transform(Pose(effectiveRoomOrigin, anchorYaw)))
        roomEnt.setComponent(Visible(true))

        val tvWorldPos = effectiveRoomOrigin + (anchorYaw * JIMMYS_ROOM_TV_LOCAL_POS)
        anchorCenter = tvWorldPos
        val tvPose = Pose(tvWorldPos, anchorYaw)
        lastWrittenPose = tvPose
        panelEntity?.let { ent ->
            ent.setComponent(Transform(tvPose))
            ent.setComponent(Grabbable(false, GrabbableType.PIVOT_Y))
            ent.tryGetComponent<IsdkPanelResize>()?.let { rc ->
                if (rc.enabled) {
                    rc.enabled = false
                    ent.setComponent(rc)
                }
            }
            ent.tryGetComponent<IsdkPanelGrabHandle>()?.let { gh ->
                val transparent = Vector4(0f, 0f, 0f, 0f)
                if (gh.color != transparent) {
                    gh.color = transparent
                    ent.setComponent(gh)
                }
            }
        }
        grabHandleEntity?.setComponent(Visible(false))
        grabHandleVisualEntity?.setComponent(Visible(false))
        jimmyPanel?.updatePoseJimmysRoom(
            tvWorldPos,
            anchorYaw,
            JIMMYS_ROOM_TV_WIDTH_M,
            JIMMYS_ROOM_TV_WIDTH_M / SpatialDisplayBridge.ASPECT_RATIO
        )
    }

    private fun applyBackdrop(mode: Int) {
        when (mode) {
            SpatialDisplayBridge.BACKDROP_DARK -> {
                jimmysRoomEntity?.setComponent(Visible(false))
                scene.enablePassthrough(false)
            }
            SpatialDisplayBridge.BACKDROP_JIMMYS_ROOM -> {
                scene.enablePassthrough(false)
                computeJimmysRoomAnchor()
                applyJimmysRoomPoses()
                scheduleReshape()
            }
            else -> {
                jimmysRoomEntity?.setComponent(Visible(false))
                scene.enablePassthrough(true)
                scene.setPassthroughLUT(Lut.fromScale(Vector3(1f, 1f, 1f)))
            }
        }
        if (mode != SpatialDisplayBridge.BACKDROP_JIMMYS_ROOM) {
            panelEntity?.let { ent ->
                ent.setComponent(Grabbable(true, GrabbableType.PIVOT_Y))
                syncHandleComponentsForCurvature(ent, currentSettings.curvePct > 0)
            }
            grabHandleEntity?.setComponent(Visible(true))
            grabHandleVisualEntity?.setComponent(Visible(controllerVisualsVisible))
            if (panelEntity != null &&
                (appliedCurvePct != currentSettings.curvePct ||
                    abs(appliedWidthMeters - currentSettings.widthMeters) > 0.005f)
            ) {
                recenterPanelNow()
                scheduleReshape()
            }
        }
        Log.i(TAG, "Backdrop applied: ${currentSettings.backdropLabel()} (mode=$mode)")
    }

    @OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
    private fun applyPerformanceBoost() {
        try {
            val ok = spatial.setPerformanceLevel(PerformanceLevel.SUSTAINED_HIGH)
            Log.i(TAG, "Requested Spatial PerformanceLevel.SUSTAINED_HIGH (ok=$ok)")
        } catch (t: Throwable) {
            Log.w(TAG, "setPerformanceLevel(SUSTAINED_HIGH) failed: ${t.message}")
        }
    }

    private fun updateControllerVisibility() {
        val now = SystemClock.uptimeMillis()
        var controllerActive = wasGrabbing ||
            wasCornerResizing ||
            wasHandleGrabbing ||
            triggerDragSource != null ||
            rayHoveredHandle ||
            hoveredHandleSources.isNotEmpty()
        if (!controllerActive) {
            for (entity in Query.where { has(Controller.id) }.eval()) {
                val c = entity.tryGetComponent<Controller>() ?: continue
                if (c.buttonState != 0 || c.changedButtons != 0) {
                    controllerActive = true
                    break
                }
            }
        }
        if (controllerActive) {
            lastControllerActivityMs = now
            if (!controllerVisualsVisible) {
                setControllerVisualsVisible(true)
            }
        } else if (controllerVisualsVisible && (now - lastControllerActivityMs) >= CONTROLLER_HIDE_IDLE_MS) {
            setControllerVisualsVisible(false)
        }
    }

    private fun setControllerVisualsVisible(visible: Boolean) {
        val avatarSystem = systemManager.tryFindSystem<AvatarSystem>() ?: return
        avatarSystem.setShowControllers(visible)
        controllerVisualsVisible = visible
        // Single source of truth for hand visibility so the 5 s idle hide can never clobber the
        // "keep Meta's hands visible while GTA hands are enabled" rule.
        syncAvatarHandVisibility()
        panelEntity?.let { syncHandleComponentsForCurvature(it, appliedCurvePct > 0) }
        val inRoom = isJimmysRoomActive()
        grabHandleEntity?.setComponent(Visible(!inRoom))
        grabHandleVisualEntity?.setComponent(Visible(!inRoom && visible))
        Log.i(TAG, "Quest controller/hand visuals & bottom handle visible=$visible")
    }

    private fun onHandlePointerEvent(event: PointerEvent) {
        if (isJimmysRoomActive()) return
        when (event.type) {
            PointerEventType.Hover.id -> {
                hoveredHandleSources.add(event.source)
                lastControllerActivityMs = SystemClock.uptimeMillis()
                if (!controllerVisualsVisible) {
                    setControllerVisualsVisible(true)
                }
            }
            PointerEventType.Unhover.id -> {
                hoveredHandleSources.remove(event.source)
            }
            PointerEventType.Select.id -> {
                if (event.semanticType == SemanticType.Select.id &&
                    triggerDragSource == null &&
                    !wasHandleGrabbing &&
                    !wasGrabbing
                ) {
                    beginTriggerDrag(event.source, event.hitInfo.point)
                }
            }
            PointerEventType.Unselect.id, PointerEventType.Cancel.id -> {
                if (event.source == triggerDragSource) {
                    endTriggerDrag()
                }
            }
        }
    }

    private fun beginTriggerDrag(source: Entity, hitPoint: Vector3) {
        if (triggerDragSource != null) return
        val handlePose = grabHandleEntity?.tryGetComponent<Transform>()?.transform ?: return
        val srcPose = source.tryGetComponent<Transform>()?.transform ?: return
        triggerDragSource = source
        val dist = (hitPoint - srcPose.t).length().coerceIn(0.4f, SpatialDisplayBridge.MAX_DIST_M + 1.5f)
        triggerDragDist = dist
        val rawFwd = srcPose.forward()
        val rayDir = if (rawFwd.length() > 1e-4f) rawFwd.normalize() else Vector3(0f, 0f, 1f)
        triggerDragOffset = handlePose.t - (srcPose.t + rayDir * dist)
        lastControllerActivityMs = SystemClock.uptimeMillis()
        if (!controllerVisualsVisible) {
            setControllerVisualsVisible(true)
        }
        Log.i(TAG, "Started bottom handle trigger drag (dist=${dist}m)")
    }

    private fun endTriggerDrag() {
        if (triggerDragSource == null) return
        triggerDragSource = null
        panelEntity?.let { applyCurrentPose(it) }
        Log.i(TAG, "Ended bottom handle trigger drag")
    }

    private fun updateGrabHandleVisualState() {
        val mat = grabHandleMaterial ?: return
        val nextState = when {
            triggerDragSource != null || wasHandleGrabbing -> HANDLE_VISUAL_DRAG
            rayHoveredHandle || hoveredHandleSources.isNotEmpty() -> HANDLE_VISUAL_HOVER
            else -> HANDLE_VISUAL_IDLE
        }
        if (nextState == grabHandleVisualState) return
        grabHandleVisualState = nextState
        mat.baseColor = when (nextState) {
            HANDLE_VISUAL_DRAG -> HANDLE_COLOR_DRAG
            HANDLE_VISUAL_HOVER -> HANDLE_COLOR_HOVER
            else -> HANDLE_COLOR_IDLE
        }
        grabHandleVisualEntity?.setComponent(mat)
    }

    private fun intersectRayHandleObb(
        rayOrigin: Vector3,
        rayDir: Vector3,
        handlePose: Pose,
        halfExtents: Vector3
    ): Float? {
        val invQ = handlePose.q.inverse()
        val o = invQ * (rayOrigin - handlePose.t)
        val d = invQ * rayDir
        var tMin = 0.1f
        var tMax = 8.0f

        val axes = arrayOf(
            floatArrayOf(o.x, d.x, halfExtents.x),
            floatArrayOf(o.y, d.y, halfExtents.y),
            floatArrayOf(o.z, d.z, halfExtents.z)
        )
        for (a in axes) {
            val origin = a[0]
            val dir = a[1]
            val half = a[2]
            if (abs(dir) < 1e-5f) {
                if (origin < -half || origin > half) return null
            } else {
                var t1 = (-half - origin) / dir
                var t2 = (half - origin) / dir
                if (t1 > t2) {
                    val tmp = t1
                    t1 = t2
                    t2 = tmp
                }
                if (t1 > tMin) tMin = t1
                if (t2 < tMax) tMax = t2
                if (tMin > tMax) return null
            }
        }
        return tMin
    }

    private fun applyDisplayRefreshRate(hz: Int) {
        val target = if (hz == 90) 90f else 120f
        val exactOk = scene.requestExactDisplayRate(target)
        if (!exactOk) {
            scene.setPreferredDisplayRate(target)
        }
        Log.i(TAG, "Requested Spatial display rate ${target}Hz (exact=$exactOk, confirmed=${scene.getConfirmedFrameRate()})")
    }

    private fun computeRecenterAnchor(distanceMeters: Float) {
        val viewer = scene.getViewerPose()
        val rawFwd = viewer.forward()
        val horiz = Vector3(rawFwd.x, 0f, rawFwd.z)
        val dir = if (horiz.length() > 1e-4f) horiz.normalize() else Vector3(0f, 0f, 1f)
        val eyePos = if (viewer.t.length() > 1e-4f) viewer.t else Vector3(0f, 1.35f, 0f)
        anchorCenter = eyePos + dir * distanceMeters
        anchorYaw = Quaternion.lookRotationAroundY(dir)
    }

    private fun recenterPanelNow() {
        if (isJimmysRoomActive()) {
            computeJimmysRoomAnchor()
            applyJimmysRoomPoses()
            Log.i(TAG, "Recentered Jimmy's Room at roomOrigin=$roomOrigin tvPos=$anchorCenter")
            return
        }
        computeRecenterAnchor(currentSettings.distanceMeters)
        val ent = panelEntity ?: return
        applyCurrentPose(ent)
        Log.i(
            TAG,
            "Recentered panel at screenCenter=$anchorCenter height=${currentSettings.heightOffsetM}m tilt=${currentSettings.tiltDeg}deg curve=${currentSettings.curvePct}%"
        )
    }

    private fun updateAnchorDistance(newDistance: Float) {
        val ent = panelEntity
        if (ent != null) {
            syncAnchorFromEntity(ent)
        }
        val viewer = scene.getViewerPose()
        val eyePos = if (viewer.t.length() > 1e-4f) viewer.t else Vector3(0f, anchorCenter.y, 0f)
        val toPanelHoriz = Vector3(anchorCenter.x - eyePos.x, 0f, anchorCenter.z - eyePos.z)
        val dir = if (toPanelHoriz.length() > 1e-4f) {
            toPanelHoriz.normalize()
        } else {
            val rawFwd = viewer.forward()
            val h = Vector3(rawFwd.x, 0f, rawFwd.z)
            if (h.length() > 1e-4f) h.normalize() else Vector3(0f, 0f, 1f)
        }
        anchorCenter = Vector3(
            eyePos.x + dir.x * newDistance,
            anchorCenter.y,
            eyePos.z + dir.z * newDistance
        )
    }

    private fun syncAnchorFromEntity(ent: Entity) {
        val tComp = ent.tryGetComponent<Transform>() ?: return
        val pose = tComp.transform
        val last = lastWrittenPose
        if (last != null && pose.isApproximatelyEqual(last, 0.002f, 0.2f)) {
            return
        }
        val fwd = pose.forward()
        val visualCenter = if (appliedRadiusMeters > 0f) {
            pose.t + fwd * appliedRadiusMeters
        } else {
            pose.t
        }
        anchorCenter = Vector3(
            visualCenter.x,
            visualCenter.y - currentSettings.heightOffsetM,
            visualCenter.z
        )
        val horiz = Vector3(fwd.x, 0f, fwd.z)
        if (horiz.length() > 1e-4f) {
            anchorYaw = Quaternion.lookRotationAroundY(horiz.normalize())
        }
    }

    private fun composePanelPose(
        center: Vector3,
        yawOnly: Quaternion,
        tiltDeg: Float,
        heightOffsetM: Float,
        radiusMeters: Float
    ): Pose {
        val visualCenter = Vector3(center.x, center.y + heightOffsetM, center.z)
        val rot = yawOnly * Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), tiltDeg)
        val fwd = Pose(Vector3(0f, 0f, 0f), rot).forward()
        val entityPos = if (radiusMeters > 0f) visualCenter - fwd * radiusMeters else visualCenter
        return Pose(entityPos, rot)
    }

    private fun computeGrabHandlePose(): Pose {
        val visualCenter = Vector3(
            anchorCenter.x,
            anchorCenter.y + currentSettings.heightOffsetM,
            anchorCenter.z
        )
        val rot = anchorYaw * Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), currentSettings.tiltDeg)
        val up = Pose(Vector3(0f, 0f, 0f), rot).up()
        val panelW = if (appliedWidthMeters > 0f) appliedWidthMeters else currentSettings.widthMeters
        val panelH = panelW / SpatialDisplayBridge.ASPECT_RATIO
        return Pose(visualCenter - up * (panelH * 0.5f + HANDLE_BOTTOM_GAP_M), rot)
    }

    private fun syncAnchorFromGrabHandle(handleEnt: Entity) {
        val tComp = handleEnt.tryGetComponent<Transform>() ?: return
        val pose = tComp.transform
        val fwd = pose.forward()
        val horiz = Vector3(fwd.x, 0f, fwd.z)
        if (horiz.length() > 1e-4f) {
            anchorYaw = Quaternion.lookRotationAroundY(horiz.normalize())
        }
        val up = pose.up()
        val panelW = if (appliedWidthMeters > 0f) appliedWidthMeters else currentSettings.widthMeters
        val panelH = panelW / SpatialDisplayBridge.ASPECT_RATIO
        val visualCenter = pose.t + up * (panelH * 0.5f + HANDLE_BOTTOM_GAP_M)
        anchorCenter = Vector3(
            visualCenter.x,
            visualCenter.y - currentSettings.heightOffsetM,
            visualCenter.z
        )
    }

    private fun updateJimmyFloatingPose() {
        val panelW = if (appliedWidthMeters > 0f) appliedWidthMeters else currentSettings.widthMeters
        val panelH = panelW / SpatialDisplayBridge.ASPECT_RATIO
        val rot = anchorYaw * Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), currentSettings.tiltDeg)
        val visualCenter = Vector3(
            anchorCenter.x,
            anchorCenter.y + currentSettings.heightOffsetM,
            anchorCenter.z
        )
        jimmyPanel?.updatePoseFloating(visualCenter, rot, panelW, panelH, appliedRadiusMeters)
    }

    private fun applyPanelPoseOnly(ent: Entity) {
        val pose = composePanelPose(
            anchorCenter,
            anchorYaw,
            currentSettings.tiltDeg,
            currentSettings.heightOffsetM,
            appliedRadiusMeters
        )
        lastWrittenPose = pose
        ent.setComponent(Transform(pose))
        if (!isJimmysRoomActive()) {
            updateJimmyFloatingPose()
        }
    }

    private fun applyCurrentPose(ent: Entity) {
        applyPanelPoseOnly(ent)
        if (!wasHandleGrabbing) {
            val handlePose = computeGrabHandlePose()
            grabHandleEntity?.setComponent(Transform(handlePose))
            grabHandleVisualEntity?.setComponent(Transform(handlePose))
        }
    }
}

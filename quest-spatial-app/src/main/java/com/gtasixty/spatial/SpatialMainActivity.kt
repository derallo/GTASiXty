package com.gtasixty.spatial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.meta.spatial.isdk.IsdkPanelGrabHandle
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.ResizeCornerState
import com.meta.spatial.isdk.ResizeMode
import com.meta.spatial.isdk.updateIsdkComponentProperties
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.LayerFilters
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.ActivityPanelRegistration
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.CylinderShapeOptions
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.UIPanelShapeOptions
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import kotlin.math.abs

/**
 * Meta Spatial SDK Passthrough MR host activity for GTA-Sixty Spatial.
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

        // 1600x900 dp @ 256 DPI = exact 2560x1440 px 1:1 backing surface.
        private const val PANEL_PX_W = 2560
        private const val PANEL_PX_H = 1440
        private const val PANEL_DP_W = 1600f
        private const val PANEL_DP_H = 900f
        private const val PANEL_DPI = 256
        private const val MIN_RESHAPE_INTERVAL_MS = 45L
        private const val CONTROLLER_HIDE_IDLE_MS = 5000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var panelEntity: Entity? = null
    private var sceneReady = false

    private var appliedCurvePct = -1
    private var appliedWidthMeters = -1f
    private var appliedRadiusMeters = 0f
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
    private var wasCornerResizing = false
    private var wasGrabbing = false
    private var lastViewerPos: Vector3? = null
    private var lastControllerActivityMs = 0L
    private var controllerVisualsVisible = true

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
                            return buildPanelConfigOptions(s.widthMeters, s.curvePct)
                        }
                    }
                }
            )
        ).also {
            appliedCurvePct = initial.curvePct
            appliedWidthMeters = initial.widthMeters
            appliedRadiusMeters = initial.cylinderRadiusMeters()
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
        appliedCurvePct = currentSettings.curvePct
        appliedWidthMeters = currentSettings.widthMeters
        appliedRadiusMeters = SpatialDisplayBridge.cylinderRadiusMeters(
            currentSettings.widthMeters,
            currentSettings.curvePct
        )

        computeRecenterAnchor(currentSettings.distanceMeters)
        val initialPose = composePanelPose(
            anchorCenter,
            anchorYaw,
            currentSettings.tiltDeg,
            currentSettings.heightOffsetM,
            appliedRadiusMeters
        )
        lastWrittenPose = initialPose

        val ent = Entity.createPanelEntity(
            R.id.main_stream_panel,
            Transform(initialPose),
            Grabbable(true, GrabbableType.PIVOT_Y),
            IsdkPanelResize(
                currentSettings.curvePct == 0,
                ResizeMode.Simple,
                Vector2(SpatialDisplayBridge.MIN_WIDTH_M, minH),
                Vector2(SpatialDisplayBridge.MAX_WIDTH_M, maxH),
                true
            )
        )
        panelEntity = ent
        syncHandleComponentsForCurvature(ent, currentSettings.curvePct > 0)

        needsInitialRecenter = true
        initialRecenterDeadlineMs = SystemClock.uptimeMillis() + 1500L
        Log.i(
            TAG,
            "Scene ready: 16:9 panel (${PANEL_PX_W}x${PANEL_PX_H} @ ${PANEL_DPI}dpi) entityPos=${initialPose.t} screenCenter=$anchorCenter radius=${appliedRadiusMeters}m"
        )
    }

    override fun onSceneTick() {
        super.onSceneTick()
        val ent = panelEntity ?: return
        val viewer = scene.getViewerPose()
        val hasRealHead = viewer.t.length() > 0.01f || abs(viewer.q.w - 1f) > 0.001f

        if (needsInitialRecenter) {
            if (hasRealHead || SystemClock.uptimeMillis() >= initialRecenterDeadlineMs) {
                needsInitialRecenter = false
                lastViewerPos = viewer.t
                recenterPanelNow()
            }
        } else if (hasRealHead && !wasGrabbing && !wasCornerResizing) {
            val prevPos = lastViewerPos
            lastViewerPos = viewer.t
            val headJump = if (prevPos != null) (viewer.t - prevPos).length() else 0f
            val distToAnchor = (viewer.t - anchorCenter).length()
            val maxReachableDist = currentSettings.distanceMeters + 3.0f
            if (headJump > 0.85f || distToAnchor > maxReachableDist) {
                Log.i(
                    TAG,
                    "Tracking origin / boundary jump detected (headJump=${headJump}m dist=${distToAnchor}m); auto-recentering"
                )
                recenterPanelNow()
            }
        }

        syncHandleComponentsForCurvature(ent, appliedCurvePct > 0)

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
                        reshapePanelNow(newW, currentSettings.curvePct)
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

        val grabComp = ent.tryGetComponent<Grabbable>()
        if (grabComp != null) {
            if (grabComp.isGrabbed) {
                wasGrabbing = true
            } else if (wasGrabbing) {
                wasGrabbing = false
                syncAnchorFromEntity(ent)
                applyCurrentPose(ent)
            }
        }

        updateControllerVisibility()
    }

    override fun onDisplaySettingsChanged(settings: SpatialDisplayBridge.DisplaySettings) {
        val prev = currentSettings
        currentSettings = SpatialDisplayBridge.DisplaySettings(settings)
        if (!sceneReady) return

        if (prev.backdropMode != currentSettings.backdropMode) {
            applyBackdrop(currentSettings.backdropMode)
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
            abs(currentSettings.widthMeters - appliedWidthMeters) > 0.005f
        ) {
            scheduleReshape()
        }
    }

    override fun onRecenterRequested() {
        if (!sceneReady) return
        needsInitialRecenter = false
        recenterPanelNow()
    }

    override fun onRefreshRateRequested(hz: Int) {
        if (!sceneReady) return
        applyDisplayRefreshRate(hz)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            if (SpatialDisplayBridge.dispatchGamepadKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            if (SpatialDisplayBridge.dispatchGamepadMotion(event)) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(reshapeRunnable)
        SpatialDisplayBridge.setSpatialListener(null)
        try {
            unregisterReceiver(debugReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildPanelConfigOptions(widthM: Float, curvePct: Int): PanelConfigOptions {
        val heightM = widthM / SpatialDisplayBridge.ASPECT_RATIO
        val shape: UIPanelShapeOptions = if (curvePct <= 0) {
            QuadShapeOptions(widthM, heightM)
        } else {
            val radius = SpatialDisplayBridge.cylinderRadiusMeters(widthM, curvePct)
            CylinderShapeOptions(radius, widthM, heightM)
        }
        val settings = UIPanelSettings(
            shape = shape,
            display = DpDisplayOptions(PANEL_DP_W, PANEL_DP_H, PANEL_DPI),
            rendering = UIPanelRenderOptions(
                PanelRenderMode.Layer(
                    layerBlendType = PanelShapeLayerBlendType.OPAQUE,
                    enableLayerFeatheredEdge = false,
                    filters = LayerFilters.HIGHEST_QUALITY
                )
            )
        )
        return settings.toPanelConfigOptions().apply {
            layoutWidthInPx = PANEL_PX_W
            layoutHeightInPx = PANEL_PX_H
            layoutDpi = PANEL_DPI
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
        val w = currentSettings.widthMeters
        val curve = currentSettings.curvePct
        appliedWidthMeters = w
        appliedCurvePct = curve
        reshapePanelNow(w, curve)
    }

    private fun reshapePanelNow(widthM: Float, curvePct: Int) {
        val ent = panelEntity ?: return
        val pso = getPanelSceneObject(ent) ?: return
        syncAnchorFromEntity(ent)
        val sc = ent.tryGetComponent<Scale>()?.scale
        if (sc != null && (abs(sc.x - 1f) > 0.001f || abs(sc.y - 1f) > 0.001f || abs(sc.z - 1f) > 0.001f)) {
            val unit = Vector3(1f, 1f, 1f)
            ent.setComponent(Scale(unit))
            pso.setScale(unit)
        }
        val newCfg = buildPanelConfigOptions(widthM, curvePct)
        val newRadius = if (curvePct > 0) newCfg.radiusForCylinderOrSphere else 0f
        appliedRadiusMeters = newRadius
        pso.reshape(newCfg)
        pso.updateIsdkComponentProperties(ent)
        syncHandleComponentsForCurvature(ent, curvePct > 0)
        applyCurrentPose(ent)
        Log.i(
            TAG,
            "Reshaped panel: width=${widthM}m height=${widthM / SpatialDisplayBridge.ASPECT_RATIO}m curve=$curvePct% radius=${newRadius}m entityPos=${lastWrittenPose?.t} screenCenter=$anchorCenter resizeEnabled=${curvePct == 0}"
        )
    }

    private fun syncHandleComponentsForCurvature(ent: Entity, curved: Boolean) {
        val resizeComp = ent.tryGetComponent<IsdkPanelResize>()
        if (resizeComp != null && resizeComp.enabled != !curved) {
            resizeComp.enabled = !curved
            ent.setComponent(resizeComp)
        }
        val grabHandle = ent.tryGetComponent<IsdkPanelGrabHandle>()
        if (grabHandle != null) {
            val targetColor = if (controllerVisualsVisible) defaultGrabHandleColor else Vector4(0f, 0f, 0f, 0f)
            if (grabHandle.color != targetColor) {
                grabHandle.color = targetColor
                ent.setComponent(grabHandle)
            }
        }
    }

    private fun getPanelSceneObject(ent: Entity): PanelSceneObject? {
        val sos = systemManager.tryFindSystem<SceneObjectSystem>() ?: return null
        val so: SceneObject = sos.getSceneObject(ent)?.getNow(null) ?: return null
        return so as? PanelSceneObject
    }

    private fun applyBackdrop(mode: Int) {
        when (mode) {
            SpatialDisplayBridge.BACKDROP_DIM -> {
                scene.enablePassthrough(true)
                scene.setPassthroughLUT(Lut.fromScale(Vector3(0.22f, 0.22f, 0.22f)))
            }
            SpatialDisplayBridge.BACKDROP_DARK -> {
                scene.enablePassthrough(false)
            }
            else -> {
                scene.enablePassthrough(true)
                scene.setPassthroughLUT(Lut.fromScale(Vector3(1f, 1f, 1f)))
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
        var controllerActive = wasGrabbing || wasCornerResizing
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
        avatarSystem.setShowHands(visible)
        controllerVisualsVisible = visible
        panelEntity?.let { syncHandleComponentsForCurvature(it, appliedCurvePct > 0) }
        Log.i(TAG, "Quest controller/hand visuals & bottom handle visible=$visible")
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

    private fun applyCurrentPose(ent: Entity) {
        val pose = composePanelPose(
            anchorCenter,
            anchorYaw,
            currentSettings.tiltDeg,
            currentSettings.heightOffsetM,
            appliedRadiusMeters
        )
        lastWrittenPose = pose
        ent.setComponent(Transform(pose))
    }
}

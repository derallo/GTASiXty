package com.gtasixty.spatial.voice

import android.util.Log
import com.gtasixty.spatial.R
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.LayerFilters
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.ViewPanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import kotlin.math.cos
import kotlin.math.sin

/**
 * Self-contained Meta Spatial SDK 3D panel for Jimmy AI, anchored cleanly below the bottom-right
 * edge of the virtual screen in both Floating theater modes and Jimmy's Room so it never obscures
 * gameplay or overlaps the bottom-center grab handle.
 *
 * Maintains the strict zero-copy swapchain invariant (`mips = 1`, `enableLayer = true`,
 * `forceSceneTexture = false`) so Horizon OS endFrameJob never stalls.
 */
class JimmySpatialPanel {

    companion object {
        private const val TAG = "GTA60-Jimmy"
        private const val PANEL_WIDTH_M = 0.72f
        private const val PANEL_HEIGHT_M = 0.16f
        private const val PANEL_DP_W = 576f
        private const val PANEL_DP_H = 128f
        private const val PANEL_DPI = 160

        private const val FLOATING_BELOW_EDGE_GAP_M = 0.035f
        private const val JIMMYS_ROOM_BELOW_EDGE_GAP_M = 0.030f
        private const val JIMMYS_ROOM_FORWARD_M = 0.04f

        fun createRegistration(): PanelRegistration {
            return ViewPanelRegistration(
                R.id.jimmy_voice_panel,
                { _, ctx -> JimmyVoiceModule.createSpatialOverlayView(ctx) },
                { _ ->
                    object : PanelSettings {
                        override fun toPanelConfigOptions(): PanelConfigOptions {
                            return UIPanelSettings(
                                shape = QuadShapeOptions(PANEL_WIDTH_M, PANEL_HEIGHT_M),
                                display = DpDisplayOptions(PANEL_DP_W, PANEL_DP_H, PANEL_DPI),
                                rendering = UIPanelRenderOptions(
                                    PanelRenderMode.Layer(
                                        layerBlendType = PanelShapeLayerBlendType.ALPHA_BLEND,
                                        enableLayerFeatheredEdge = false,
                                        filters = LayerFilters.DEFAULT_QUALITY
                                    )
                                )
                            ).toPanelConfigOptions().apply {
                                layoutWidthInPx = PANEL_DP_W.toInt()
                                layoutHeightInPx = PANEL_DP_H.toInt()
                                layoutDpi = PANEL_DPI
                                alphaMode = AlphaMode.TRANSLUCENT
                                mips = 1
                                enableLayer = true
                                forceSceneTexture = false
                            }
                        }
                    }
                }
            )
        }
    }

    private var entity: Entity? = null
    private var lastVisible: Boolean? = null

    fun createEntity() {
        if (entity != null) return
        val enabled = JimmyVoiceModule.isEnabled()
        entity = Entity.createPanelEntity(
            R.id.jimmy_voice_panel,
            Transform(Pose()),
            Visible(enabled)
        )
        lastVisible = enabled
        Log.i(TAG, "Created Jimmy spatial panel entity (visible=$enabled)")
    }

    fun updatePoseFloating(
        visualCenter: Vector3,
        rot: Quaternion,
        panelWidthM: Float,
        panelHeightM: Float,
        radiusMeters: Float = 0f
    ) {
        val ent = entity ?: return
        val basis = Pose(Vector3(0f, 0f, 0f), rot)
        val right = rot * Vector3(1f, 0f, 0f)
        val up = basis.up()
        val fwd = basis.forward()
        val xOff = ((panelWidthM - PANEL_WIDTH_M) * 0.5f).coerceAtLeast(0f)
        val yOff = panelHeightM * 0.5f + PANEL_HEIGHT_M * 0.5f + FLOATING_BELOW_EDGE_GAP_M

        val pos: Vector3
        val finalRot: Quaternion
        if (radiusMeters > 0.05f) {
            val theta = xOff / radiusMeters
            pos = visualCenter +
                right * (radiusMeters * sin(theta)) -
                fwd * (radiusMeters * (1f - cos(theta))) -
                up * yOff
            finalRot = rot * Quaternion.fromAxisAngle(Vector3(0f, 1f, 0f), Math.toDegrees(theta.toDouble()).toFloat())
        } else {
            pos = visualCenter + right * xOff - up * yOff
            finalRot = rot
        }
        ent.setComponent(Transform(Pose(pos, finalRot)))
        syncVisibility()
    }

    fun updatePoseJimmysRoom(tvWorldPos: Vector3, anchorYaw: Quaternion, tvWidthM: Float, tvHeightM: Float) {
        val ent = entity ?: return
        val poseBasis = Pose(Vector3(0f, 0f, 0f), anchorYaw)
        val right = anchorYaw * Vector3(1f, 0f, 0f)
        val up = poseBasis.up()
        val fwd = poseBasis.forward()
        val xOff = ((tvWidthM - PANEL_WIDTH_M) * 0.5f).coerceAtLeast(0f)
        val yOff = tvHeightM * 0.5f + PANEL_HEIGHT_M * 0.5f + JIMMYS_ROOM_BELOW_EDGE_GAP_M
        val pos = tvWorldPos + right * xOff - up * yOff - fwd * JIMMYS_ROOM_FORWARD_M
        ent.setComponent(Transform(Pose(pos, anchorYaw)))
        syncVisibility()
    }

    fun syncVisibility() {
        val ent = entity ?: return
        val desired = JimmyVoiceModule.isEnabled()
        if (lastVisible != desired) {
            lastVisible = desired
            ent.setComponent(Visible(desired))
            Log.i(TAG, "Jimmy spatial panel visible=$desired")
        }
    }

    fun destroy() {
        val ent = entity
        entity = null
        lastVisible = null
        try {
            ent?.destroy()
        } catch (_: Throwable) {
        }
    }
}

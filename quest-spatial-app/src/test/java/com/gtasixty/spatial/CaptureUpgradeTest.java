package com.gtasixty.spatial;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

/**
 * JVM unit tests for the 3-phase Quest upscaling, anti-screendoor frame generation,
 * VSync pacing, CPU global motion estimation, and GT60 protocol helpers.
 */
public class CaptureUpgradeTest {

    @Test
    public void cadenceController_doesNotDiscard12RealFramesAfterSingleHitch() {
        CaptureCadenceController ctrl = new CaptureCadenceController();
        // 60 fps stream with one isolated duplicate hitch: U, U, D, U, U, U, U
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.EXTRAP_GAP_FILL,
                ctrl.onInputFrame(true, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertFalse(ctrl.isCadenceLocked30());
    }

    @Test
    public void cadenceController_locks30fpsAlternationAndUnlocksOnStaticMenu() {
        CaptureCadenceController ctrl = new CaptureCadenceController();
        // U, D, U, D -> locks on 3rd U
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(true, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.EXTRAP_GAP_FILL,
                ctrl.onInputFrame(true, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.WARP_30_PAIR,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG60_SR, false));
        assertTrue(ctrl.isCadenceLocked30());
        assertEquals(CaptureCadenceController.Action.HOLD_30_REAL,
                ctrl.onInputFrame(true, CaptureCadenceController.MODE_FG60_SR, false));

        // Static menu: D, D, D -> unlocks 30fps cadence and passes through
        assertEquals(CaptureCadenceController.Action.HOLD_30_REAL,
                ctrl.onInputFrame(true, CaptureCadenceController.MODE_FG60_SR, false));
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(true, CaptureCadenceController.MODE_FG60_SR, false));
        assertFalse(ctrl.isCadenceLocked30());
    }

    @Test
    public void cadenceController_fg120Generates60To120Midpoints() {
        CaptureCadenceController ctrl = new CaptureCadenceController();
        assertEquals(CaptureCadenceController.Action.PASSTHROUGH_REAL,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG120_SR, false));
        assertEquals(CaptureCadenceController.Action.WARP_60_TO_120,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG120_SR, false));
        assertEquals(CaptureCadenceController.Action.WARP_60_TO_120,
                ctrl.onInputFrame(false, CaptureCadenceController.MODE_FG120_SR, false));
    }

    private static byte[] encodeFlowGrid(int w, int h, float maxUv, FlowField field) {
        byte[] buf = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            float v = (y + 0.5f) / h;
            for (int x = 0; x < w; x++) {
                float u = (x + 0.5f) / w;
                float[] vc = field.sample(u, v, x, y);
                int r = Math.max(0, Math.min(255, Math.round(((vc[0] / maxUv) * 0.5f + (128.0f / 255.0f)) * 255.0f)));
                int g = Math.max(0, Math.min(255, Math.round(((vc[1] / maxUv) * 0.5f + (128.0f / 255.0f)) * 255.0f)));
                int a = Math.max(0, Math.min(255, Math.round(vc[2] * 255.0f)));
                int off = (y * w + x) * 4;
                buf[off] = (byte) r;
                buf[off + 1] = (byte) g;
                buf[off + 2] = 0;
                buf[off + 3] = (byte) a;
            }
        }
        return buf;
    }

    private interface FlowField {
        float[] sample(float u, float v, int x, int y);
    }

    @Test
    public void globalMotionEstimator_recoversPanWithOutliersAndHudNoise() {
        final int w = 32, h = 18;
        final float maxUv = 0.20f;
        byte[] grid = encodeFlowGrid(w, h, maxUv, (u, v, x, y) -> {
            // Corrupt HUD zones
            if ((u < 0.24f && v < 0.28f) || (u > 0.77f && v > 0.78f)) {
                return new float[]{-0.15f, 0.15f, 1.0f};
            }
            // ~20% outlier foreground cars moving opposite
            if ((x + y * 3) % 5 == 0) {
                return new float[]{-0.12f, 0.04f, 0.9f};
            }
            return new float[]{0.06f, -0.01f, 0.95f};
        });

        GlobalMotionEstimator est = new GlobalMotionEstimator();
        assertTrue(est.estimate(grid, w, h, maxUv));
        assertEquals(0.06f, est.getTx(), 0.004f);
        assertEquals(-0.01f, est.getTy(), 0.004f);
    }

    @Test
    public void globalMotionEstimator_recoversForwardDrivingRadialZoomAndResetsOnCut() {
        final int w = 32, h = 18;
        final float maxUv = 0.20f;
        byte[] zoomGrid = encodeFlowGrid(w, h, maxUv, (u, v, x, y) ->
                new float[]{0.08f * (u - 0.5f), 0.08f * (v - 0.5f), 0.9f});

        GlobalMotionEstimator est = new GlobalMotionEstimator();
        assertTrue(est.estimate(zoomGrid, w, h, maxUv));
        assertEquals(0.08f, est.getSx(), 0.008f);
        assertEquals(0.08f, est.getSy(), 0.008f);
        assertEquals(0.0f, est.getTx(), 0.004f);
        assertEquals(0.0f, est.getTy(), 0.004f);

        // Low-confidence scene cut resets cleanly
        byte[] cutGrid = encodeFlowGrid(w, h, maxUv, (u, v, x, y) -> new float[]{0.1f, 0.1f, 0.05f});
        assertFalse(est.estimate(cutGrid, w, h, maxUv));
        assertEquals(0.0f, est.getSx(), 1e-6f);
        assertEquals(0.0f, est.getTx(), 1e-6f);
    }

    @Test
    public void framePacer_snapsToVsyncGridWithLeadMargin() {
        long anchor = 1_000_000_000L;
        long p120 = FramePacer.PERIOD_120HZ_NS; // 8,333,333
        long snapped = FramePacer.snapToNextVsyncNs(anchor + 7_500_000L, anchor, p120, FramePacer.DEFAULT_LEAD_MARGIN_NS);
        // desired + 1.5ms = anchor + 9.0ms -> snaps to k=2 (anchor + 2 * p120)
        assertEquals(anchor + 2L * p120, snapped);

        long p90 = FramePacer.PERIOD_90HZ_NS;
        long snapped90 = FramePacer.snapToNextVsyncNs(anchor + 1_000_000L, anchor, p90, FramePacer.DEFAULT_LEAD_MARGIN_NS);
        assertEquals(anchor + p90, snapped90);

        // Consecutive sub-frames advance by exactly 1 VSync (never double-snapping to +2 VSyncs)
        FramePacer pacer = new FramePacer();
        pacer.onVsync(anchor, 120);
        // Even if Choreographer fires at 60 Hz (2 * p120), 120 Hz period stays locked
        pacer.onVsync(anchor + 2L * p120, 120);
        assertEquals(p120, pacer.getVsyncPeriodNs());

        long realSubmitted = anchor + 2L * p120 + 2_000_000L; // 2 ms after VSync
        long firstSub = pacer.firstSubFrameDueNs(realSubmitted);
        // Real latches on k=3; first sub-frame is k=4 (exactly 1 VSync later)
        assertEquals(anchor + 4L * p120, firstSub);

        long secondSub = pacer.advanceSubFrameDueNs(firstSub, firstSub - 3_000_000L);
        assertEquals(anchor + 5L * p120, secondSub);
    }

    @Test
    public void protocolHelper_clientConfigAndCodecCsdAndSurfaceSizing() {
        // Legacy 12-byte PC config
        byte[] pcCfg = CaptureProtocolHelper.buildClientConfigPayload(2560, 1440, 60, 80, null);
        assertEquals(12, pcCfg.length);

        // 14-byte Capture config: HEVC 10-bit
        byte[] capCfg = CaptureProtocolHelper.buildClientConfigPayload(
                2560, 1440, 60, 160, CaptureProtocolHelper.CAPTURE_CODEC_HEVC_10);
        assertEquals(14, capCfg.length);
        ByteBuffer bb = ByteBuffer.wrap(capCfg).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2560, bb.getInt());
        assertEquals(1440, bb.getInt());
        assertEquals(60, bb.getShort());
        assertEquals(160, bb.getShort());
        assertEquals(CaptureProtocolHelper.CODEC_HEVC, bb.get() & 0xFF);
        assertEquals(0x81, bb.get() & 0xFF);

        // AV1 raw OBU wrapping + no post-start CODEC_CONFIG queueing
        byte[] rawObu = new byte[]{0x0A, 0x0B, 0x0C};
        CaptureProtocolHelper.PreparedCsd av1Prep = CaptureProtocolHelper.prepareDecoderCsd(
                CaptureProtocolHelper.CODEC_AV1, rawObu);
        assertFalse(av1Prep.queueConfigBufferAfterStart);
        assertEquals(1, av1Prep.units.size());
        byte[] av1C = av1Prep.units.get(0);
        assertEquals(7, av1C.length);
        assertEquals((byte) 0x81, av1C[0]);
        assertEquals((byte) 0x0D, av1C[1]);
        assertEquals((byte) 0x0C, av1C[2]);
        assertEquals((byte) 0x00, av1C[3]);

        // HEVC Annex-B 3-unit split + post-start queue true
        byte[] hevcBlob = new byte[]{0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 1, 0, 0, 0, 1, 0x44, 1};
        CaptureProtocolHelper.PreparedCsd hevcPrep = CaptureProtocolHelper.prepareDecoderCsd(
                CaptureProtocolHelper.CODEC_HEVC, hevcBlob);
        assertTrue(hevcPrep.queueConfigBufferAfterStart);
        assertEquals(3, hevcPrep.units.size());

        // SuperRes & Surface sizing
        int[] dims1800 = CaptureProtocolHelper.computeSurfaceDimensions(2560, 1440,
                CaptureProtocolHelper.panelPxW(CaptureProtocolHelper.PANEL_SCALE_1800P),
                CaptureProtocolHelper.panelPxH(CaptureProtocolHelper.PANEL_SCALE_1800P));
        assertArrayEquals(new int[]{3200, 1800}, dims1800);
        assertEquals(320, CaptureProtocolHelper.panelDpi(CaptureProtocolHelper.PANEL_SCALE_1800P));
        assertEquals(384, CaptureProtocolHelper.panelDpi(CaptureProtocolHelper.PANEL_SCALE_2160P));

        assertFalse(CaptureProtocolHelper.shouldEnableSpatialSuperRes(2560, 1440, 2560, 1440, true));
        assertTrue(CaptureProtocolHelper.shouldEnableSpatialSuperRes(2560, 1440, 3200, 1800, true));
        assertTrue(CaptureProtocolHelper.shouldEnableSpatialSuperRes(2560, 1440, 3840, 2160, true));

        // Generated frames add only +0.10 stop to avoid >2x sharp/soft breathing oscillation
        assertEquals(0.20f, CaptureProtocolHelper.computeRcasSharpnessStops(0.10f, true), 1e-5f);
        assertEquals(0.10f, CaptureProtocolHelper.computeRcasSharpnessStops(0.10f, false), 1e-5f);
    }

    private static void assertValidShader(String name, String src, String expectedVersion, String... requiredTokens) {
        assertNotNull(name + " is null", src);
        assertTrue(name + " missing " + expectedVersion, src.startsWith(expectedVersion));
        int balance = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') balance++;
            else if (c == '}') balance--;
            assertTrue(name + " negative brace balance at idx " + i, balance >= 0);
        }
        assertEquals(name + " unbalanced braces", 0, balance);
        for (String tok : requiredTokens) {
            assertTrue(name + " missing token: " + tok, src.contains(tok));
        }
    }

    @Test
    public void glShaders_haveValidVersionsBalancedBracesAndRequiredUniforms() {
        assertValidShader("FRAG_LUMA", CaptureGlRenderer.FRAG_LUMA, "#version 300 es", "uSrcTexelSize");
        assertValidShader("FRAG_FLOW_COARSE", CaptureGlRenderer.FRAG_FLOW_COARSE, "#version 300 es",
                "uPrevFlowTex", "uGlobalMotion", "MAX_UV = 0.20");
        assertValidShader("COMP_FLOW_COARSE", CaptureGlRenderer.COMP_FLOW_COARSE, "#version 310 es",
                "shared highp float sCurr", "imageStore", "uGlobalMotion", "MAX_UV = 0.20");
        assertValidShader("COMP_FLOW_FINE", CaptureGlRenderer.COMP_FLOW_FINE, "#version 310 es",
                "shared highp float sCurr", "imageStore", "MAX_UV = 0.20");
        assertValidShader("FRAG_FLOW_SMOOTH", CaptureGlRenderer.FRAG_FLOW_SMOOTH, "#version 300 es",
                "uStepSize", "uTemporalWeight");
        assertValidShader("FRAG_WARP", CaptureGlRenderer.FRAG_WARP, "#version 300 es",
                "uConfScale", "uGlobalMotion", "cands[4]", "MAX_UV = 0.20");
        assertFalse("FRAG_WARP must not fall back to unaligned mix(c0, c1, ...)",
                CaptureGlRenderer.FRAG_WARP.contains("mix(c0, c1"));
        assertValidShader("FRAG_TAA_FOLIAGE", CaptureGlRenderer.FRAG_TAA_FOLIAGE, "#version 300 es",
                "rgbToYcocg", "ycocgToRgb", "uBlendMax");
        assertValidShader("FRAG_RCAS", CaptureGlRenderer.FRAG_RCAS, "#version 300 es",
                "fastLanczos2", "sgsr1Sample", "uSuperRes");
    }
}

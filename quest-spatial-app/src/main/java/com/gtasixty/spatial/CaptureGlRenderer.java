package com.gtasixty.spatial;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * On-Quest Adreno 740 OpenGL ES 3.0 / 3.1 post-processing pipeline for Capture mode.
 *
 * <p>Implements all 3 optimization phases:
 * <ul>
 *   <li>Phase 1: Foliage-safe 4x bilinear luma box downsample, {@code GL_RGBA16F} flow targets
 *       with {@code MAX_UV = 0.20}, 4-candidate trajectory warp, smooth confidence fallback,
 *       fixed cadence locking, and Qualcomm SGSR1 12-tap {@code fastLanczos2} + RCAS.
 *   <li>Phase 2: Choreographer VSync-locked sub-frame pacing ({@code eglPresentationTimeANDROID}),
 *       async PBO CPU affine {@link GlobalMotionEstimator}, ADPF {@code PerformanceHintManager}
 *       CPU boost, and YCoCg variance-clamped temporal foliage accumulation (SGSR2-lite).
 *   <li>Phase 3: GLES 3.1 shared-memory compute optical flow ({@code COMP_FLOW_COARSE} /
 *       {@code COMP_FLOW_FINE}) with fragment fallback + {@code GL_QCOM_motion_estimation} probe.
 * </ul>
 */
final class CaptureGlRenderer {

    private static final String TAG = "GTA60-GL";

    static final int MODE_OFF = CaptureCadenceController.MODE_OFF;
    static final int MODE_RCAS = CaptureCadenceController.MODE_RCAS;
    static final int MODE_SUPERRES = CaptureCadenceController.MODE_SUPERRES;
    static final int MODE_FG60 = CaptureCadenceController.MODE_FG60;
    static final int MODE_FG60_SR = CaptureCadenceController.MODE_FG60_SR;
    static final int MODE_FG90 = CaptureCadenceController.MODE_FG90;
    static final int MODE_FG90_SR = CaptureCadenceController.MODE_FG90_SR;
    static final int MODE_FG120 = CaptureCadenceController.MODE_FG120;
    static final int MODE_FG120_SR = CaptureCadenceController.MODE_FG120_SR;

    static final int LOOK_NORMAL = 0;
    static final int LOOK_VIVID = 1;
    static final int LOOK_NIGHT = 2;

    private static final int FLOW_LO_W = 160;
    private static final int FLOW_LO_H = 90;
    private static final int FLOW_HI_W = 640;
    private static final int FLOW_HI_H = 360;
    private static final int TINY_W = 32;
    private static final int TINY_H = 18;
    private static final float MAX_UV = 0.20f;
    private static final float RCAS_SHARPNESS = 0.10f;

    private static final int EGL_CONTEXT_MAJOR_VERSION_KHR = 0x3098;
    private static final int EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM = 0x8C90;
    private static final int MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM = 0x8C91;

    interface FrameListener {
        void onFrameRendered(boolean generated);
    }

    private final Context appContext;
    private final Surface outputSurface;
    private final int width;
    private final int height;
    private final boolean autoDetectDuplicates;
    private final boolean yuy2Input;
    private final FrameListener listener;

    private volatile int enhanceMode;
    private volatile int lookMode;
    private volatile boolean taaEnabled = true;
    private volatile boolean computeFlowEnabled = true;
    private volatile boolean running = true;

    private final Object frameLock = new Object();
    private final ArrayDeque<Boolean> dupQueue = new ArrayDeque<>();
    private int availableFrames = 0;
    private boolean resetHistoryRequested = false;
    private boolean loggedGeom = false;
    private float lastStSx = -99f, lastStSy = -99f, lastStTx = -99f, lastStTy = -99f;

    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean initSuccess = false;
    private volatile SurfaceTexture surfaceTexture;
    private volatile Surface decoderSurface;
    private Thread glThread;

    private final CaptureCadenceController cadence = new CaptureCadenceController();
    private final FramePacer framePacer = new FramePacer();
    private final GlobalMotionEstimator globalMotion = new GlobalMotionEstimator();

    // Choreographer VSync tracking on main looper
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Choreographer.FrameCallback vsyncCallback;

    // ADPF PerformanceHintManager session (API 31+)
    private Object perfHintSessionObj;

    // EGL state (owned by glThread)
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLConfig eglConfig = null;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private boolean es31Supported = false;

    // Opt-in secondary EGLSurface encoder for synchronized output video capture
    private final Object recordLock = new Object();
    private String pendingRecordPath = null;
    private int pendingRecordDurationSec = 0;
    private boolean stopRecordRequested = false;
    private String activeRecordPath = null;
    private MediaCodec recordCodec = null;
    private MediaMuxer recordMuxer = null;
    private int recordTrackIdx = -1;
    private boolean recordMuxerStarted = false;
    private Surface recordInputSurface = null;
    private EGLSurface eglRecordSurface = EGL14.EGL_NO_SURFACE;
    private int recordW = 0;
    private int recordH = 0;
    private long recordStartNs = 0L;
    private long recordDeadlineNs = 0L;
    private long lastRecordPtsNs = 0L;
    private long recordFrameIdx = 0L;
    private BufferedWriter recordCsv = null;
    private final MediaCodec.BufferInfo recordBufInfo = new MediaCodec.BufferInfo();
    private boolean float16FlowSupported = false;
    private boolean computeFlowSupported = false;
    private boolean qcomMotionSupported = false;

    // GL geometry & shaders (owned by glThread)
    private int vaoId = 0;
    private int vboId = 0;
    private int oesTexId = 0;
    private int texYuy2 = 0;

    private int progOesCopy = 0;
    private int uOesCopyTex = -1;
    private int uOesCopyStMatrix = -1;
    private int uOesCopyVScale = -1;
    // Qualcomm decoders hand us a surface taller than the stream, leaving a flat strip at the
    // top of the texture. Sample only the image region so the picture fills the panel.
    private static final float DECODER_IMAGE_V_SCALE = 0.9625f;
    private volatile float decoderImageVScale = DECODER_IMAGE_V_SCALE;

    private int progYuy2Copy = 0;
    private int uYuy2Tex = -1;
    private int uYuy2Width = -1;

    private int progLuma = 0;
    private int uLumaTex = -1;
    private int uLumaSrcTexel = -1;

    private int progDownsample = 0;
    private int uDownsampleHi = -1;

    private int progFlowCoarse = 0;
    private int uCoarsePrev = -1;
    private int uCoarseCurr = -1;
    private int uCoarsePrevFlow = -1;
    private int uCoarseGlobal = -1;

    private int progFlowFine = 0;
    private int uFinePrev = -1;
    private int uFineCurr = -1;
    private int uFineCoarse = -1;

    private int progCompCoarse = 0;
    private int uCompCoarsePrev = -1;
    private int uCompCoarseCurr = -1;
    private int uCompCoarsePrevFlow = -1;
    private int uCompCoarseGlobal = -1;

    private int progCompFine = 0;
    private int uCompFinePrev = -1;
    private int uCompFineCurr = -1;
    private int uCompFineCoarse = -1;

    private int progFlowSmooth = 0;
    private int uSmoothRaw = -1;
    private int uSmoothLuma = -1;
    private int uSmoothPrevFlow = -1;
    private int uSmoothStep = -1;
    private int uSmoothTemporalW = -1;

    private int progFlowTiny = 0;
    private int uFlowTinyTex = -1;

    private int progWarp = 0;
    private int uWarpPrev = -1;
    private int uWarpCurr = -1;
    private int uWarpFlow = -1;
    private int uWarpFlowBwd = -1;
    private int uWarpAlpha = -1;
    private int uWarpConfScale = -1;
    private int uWarpGlobal = -1;

    private int progTaa = 0;
    private int uTaaCurr = -1;
    private int uTaaHist = -1;
    private int uTaaFlow = -1;
    private int uTaaTexel = -1;
    private int uTaaBlendMax = -1;

    private int progRcas = 0;
    private int uRcasInput = -1;
    private int uRcasTexelSize = -1;
    private int uRcasSharpness = -1;
    private int uRcasSuperRes = -1;
    private int uRcasLookMode = -1;

    // Multi-frame-generation sub-frame scheduler (FG90 3x & FG120 2x/4x, owned by glThread)
    private int fgPendingPhase = 0;
    private int fgSubFrameCount = 2;
    private long fgNextDueNs = 0L;

    // Ping-pong textures & FBOs
    private int texPrev = 0, fboPrev = 0;
    private int texCurr = 0, fboCurr = 0;
    private int texMid = 0, fboMid = 0;
    private int texTaaPrev = 0, fboTaaPrev = 0;
    private int texTaaCurr = 0, fboTaaCurr = 0;
    private boolean hasTaaHistory = false;

    private int texLumaHiPrev = 0, fboLumaHiPrev = 0;
    private int texLumaHiCurr = 0, fboLumaHiCurr = 0;
    private int texLumaLoPrev = 0, fboLumaLoPrev = 0;
    private int texLumaLoCurr = 0, fboLumaLoCurr = 0;

    private int texFlowCoarse = 0, fboFlowCoarse = 0;
    private int texFlowBwdRaw = 0, fboFlowBwdRaw = 0;
    private int texFlowBwd = 0, fboFlowBwd = 0;
    private int texFlowFine = 0, fboFlowFine = 0;
    private int texFlowSmooth = 0, fboFlowSmooth = 0;
    private int texFlowPrev = 0, fboFlowPrev = 0;
    private int texFlowTiny = 0, fboFlowTiny = 0;
    private int texLumaTiny = 0, fboLumaTiny = 0;

    // Async 2-PBO readback for global camera motion (32x18 RGBA8)
    private final int[] flowPboIds = new int[2];
    private final long[] flowPboFences = new long[2];
    private final boolean[] flowPboPending = new boolean[2];
    private int flowPboWriteIdx = 0;
    private final byte[] flowTinyBytes = new byte[TINY_W * TINY_H * 4];

    private ByteBuffer tinyCurrBuf;
    private byte[] tinyCurrBytes;
    private byte[] tinyPrevBytes;
    private ByteBuffer yuy2UploadBuf;
    private byte[] yuy2FrontBuf;
    private byte[] yuy2BackBuf;
    private byte[] yuy2PrevSample;
    private boolean yuy2FrameReady = false;
    private boolean yuy2PendingDup = false;
    private boolean hasTinyPrev = false;
    private boolean lastAutoDup = false;
    private int autoDupInCount = 0;
    private int autoDupTagCount = 0;
    private double autoDupMadSum = 0.0;
    private long autoDupLastLogMs = 0L;

    private final float[] stMatrix = new float[16];
    private final int[] eglDim = new int[2];

    CaptureGlRenderer(Surface outputSurface, int width, int height,
                      int enhanceMode, int lookMode, FrameListener listener) {
        this(null, outputSurface, width, height, enhanceMode, lookMode, false, false, listener);
    }

    CaptureGlRenderer(Surface outputSurface, int width, int height,
                      int enhanceMode, int lookMode, boolean autoDetectDuplicates,
                      FrameListener listener) {
        this(null, outputSurface, width, height, enhanceMode, lookMode, autoDetectDuplicates, false, listener);
    }

    CaptureGlRenderer(Surface outputSurface, int width, int height,
                      int enhanceMode, int lookMode, boolean autoDetectDuplicates,
                      boolean yuy2Input, FrameListener listener) {
        this(null, outputSurface, width, height, enhanceMode, lookMode, autoDetectDuplicates, yuy2Input, listener);
    }

    CaptureGlRenderer(Context context, Surface outputSurface, int width, int height,
                      int enhanceMode, int lookMode, boolean autoDetectDuplicates,
                      boolean yuy2Input, FrameListener listener) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.outputSurface = outputSurface;
        this.width = width;
        this.height = height;
        this.enhanceMode = enhanceMode;
        this.lookMode = lookMode;
        this.autoDetectDuplicates = autoDetectDuplicates;
        this.yuy2Input = yuy2Input;
        this.listener = listener;
        final SpatialDisplayBridge.DisplaySettings ds = SpatialDisplayBridge.getSettings();
        this.taaEnabled = ds.taaEnabled;
        this.computeFlowEnabled = ds.computeFlowEnabled;
        start();
    }

    private void start() {
        registerChoreographer();
        glThread = new Thread(this::runGlLoop, "GTA60-CaptureGL");
        glThread.start();
    }

    private void registerChoreographer() {
        mainHandler.post(() -> {
            if (!running) return;
            vsyncCallback = new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!running) return;
                    final int m = enhanceMode;
                    final int hz = CaptureCadenceController.isFg90(m) ? 90 : 120;
                    framePacer.onVsync(frameTimeNanos, hz);
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };
            Choreographer.getInstance().postFrameCallback(vsyncCallback);
        });
    }

    private void unregisterChoreographer() {
        mainHandler.post(() -> {
            if (vsyncCallback != null) {
                try {
                    Choreographer.getInstance().removeFrameCallback(vsyncCallback);
                } catch (Exception ignored) {}
                vsyncCallback = null;
            }
        });
    }

    boolean awaitReady() {
        try {
            readyLatch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return initSuccess;
    }

    Surface getDecoderSurface() {
        return awaitReady() ? decoderSurface : null;
    }

    void submitYuy2Frame(byte[] frameData, int length) {
        if (!running || !yuy2Input || frameData == null) return;
        final int expected = width * height * 2;
        if (length < expected || yuy2BackBuf == null || yuy2PrevSample == null) return;

        boolean dup = false;
        if (autoDetectDuplicates && enhanceMode >= MODE_FG60) {
            float mad = 0f;
            long sumAbs = 0L;
            int idx = 0;
            for (int sy = 0; sy < TINY_H; sy++) {
                int y = (sy * height) / TINY_H;
                int rowOff = y * width * 2;
                for (int sx = 0; sx < TINY_W; sx++) {
                    int x = (sx * width) / TINY_W;
                    byte luma = frameData[rowOff + (x * 2)];
                    if (hasTinyPrev) {
                        sumAbs += Math.abs((luma & 0xFF) - (yuy2PrevSample[idx] & 0xFF));
                    }
                    yuy2PrevSample[idx++] = luma;
                }
            }
            if (hasTinyPrev) {
                mad = (float) sumAbs / (float) (TINY_W * TINY_H);
                dup = !lastAutoDup && (mad < 1.5f);
            }
            hasTinyPrev = true;
            lastAutoDup = dup;

            autoDupInCount++;
            if (dup) autoDupTagCount++;
            autoDupMadSum += mad;
            final long nowMs = System.currentTimeMillis();
            if (autoDupLastLogMs == 0L) autoDupLastLogMs = nowMs;
            if (nowMs - autoDupLastLogMs >= 2000L) {
                final double dupPct = autoDupInCount > 0 ? (autoDupTagCount * 100.0 / autoDupInCount) : 0.0;
                final double avgMad = autoDupInCount > 0 ? (autoDupMadSum / autoDupInCount) : 0.0;
                Log.i(TAG, String.format("USB-Dup: in=%d dup=%d (%.1f%%) avgMad=%.2f",
                        autoDupInCount, autoDupTagCount, dupPct, avgMad));
                autoDupInCount = 0;
                autoDupTagCount = 0;
                autoDupMadSum = 0.0;
                autoDupLastLogMs = nowMs;
            }
        }

        synchronized (frameLock) {
            if (!running) return;
            System.arraycopy(frameData, 0, yuy2BackBuf, 0, expected);
            yuy2PendingDup = dup;
            yuy2FrameReady = true;
            frameLock.notifyAll();
        }
    }

    void setEnhanceMode(int mode) {
        int prev = this.enhanceMode;
        this.enhanceMode = mode;
        if (prev < MODE_FG60 && mode >= MODE_FG60) {
            synchronized (frameLock) {
                resetHistoryRequested = true;
            }
        }
    }

    void setLookMode(int look) {
        this.lookMode = look;
    }

    void setTaaEnabled(boolean enabled) {
        if (this.taaEnabled != enabled) {
            this.taaEnabled = enabled;
            synchronized (frameLock) {
                resetHistoryRequested = true;
            }
        }
    }

    void setComputeFlowEnabled(boolean enabled) {
        this.computeFlowEnabled = enabled;
    }

    void setDecoderImageVScale(float vScale) {
        this.decoderImageVScale = Math.max(0.5f, Math.min(1.0f, vScale));
    }

    void startRecording(String mp4Path, int durationSec) {
        if (mp4Path == null || mp4Path.isEmpty()) return;
        synchronized (recordLock) {
            pendingRecordPath = mp4Path;
            pendingRecordDurationSec = Math.max(1, Math.min(120, durationSec));
            stopRecordRequested = false;
        }
        Log.i(TAG, "Scheduled GL output recording: " + mp4Path + " (" + durationSec + "s)");
    }

    private boolean isRecordingOrPending() {
        if (recordCodec != null) return true;
        synchronized (recordLock) {
            return pendingRecordPath != null;
        }
    }

    void onDecodedFrame(boolean isDuplicate) {
        synchronized (frameLock) {
            dupQueue.addLast(isDuplicate);
            frameLock.notifyAll();
        }
    }

    void release() {
        running = false;
        unregisterChoreographer();
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        Thread t = glThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void initPerformanceHintSession() {
        if (Build.VERSION.SDK_INT < 31 || appContext == null) return;
        try {
            PerformanceHintManager phm = appContext.getSystemService(PerformanceHintManager.class);
            if (phm != null) {
                int[] tids = new int[]{Process.myTid()};
                perfHintSessionObj = phm.createHintSession(tids, FramePacer.PERIOD_120HZ_NS);
                if (perfHintSessionObj != null) {
                    Log.i(TAG, "ADPF PerformanceHintManager session active for GL thread");
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "ADPF hint session unavailable: " + t.getMessage());
        }
    }

    private void reportAdpfWorkDuration(long workNs) {
        if (Build.VERSION.SDK_INT < 31 || perfHintSessionObj == null || workNs <= 0L) return;
        try {
            ((PerformanceHintManager.Session) perfHintSessionObj).reportActualWorkDuration(workNs);
        } catch (Throwable ignored) {}
    }

    private void closePerformanceHintSession() {
        if (Build.VERSION.SDK_INT >= 31 && perfHintSessionObj != null) {
            try {
                ((PerformanceHintManager.Session) perfHintSessionObj).close();
            } catch (Throwable ignored) {}
            perfHintSessionObj = null;
        }
    }

    private void runGlLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) {}

        initPerformanceHintSession();

        if (!initEgl() || !initGlResources()) {
            readyLatch.countDown();
            cleanupGl();
            cleanupEgl();
            closePerformanceHintSession();
            return;
        }

        initSuccess = true;
        readyLatch.countDown();
        Log.i(TAG, String.format(
                "CaptureGlRenderer initialized %dx%d mode=%d look=%d es31=%s fp16Flow=%s computeFlow=%s qcomMotion=%s",
                width, height, enhanceMode, lookMode, es31Supported, float16FlowSupported,
                computeFlowSupported, qcomMotionSupported));

        while (running) {
            boolean isDup = false;
            boolean runSubFrame = false;
            synchronized (frameLock) {
                while (running && (yuy2Input ? !yuy2FrameReady : (availableFrames <= 0 || (!autoDetectDuplicates && dupQueue.isEmpty())))) {
                    if (fgPendingPhase > 0) {
                        long targetRenderNs = fgNextDueNs - framePacer.subFrameRenderLeadNs();
                        long remNs = targetRenderNs - System.nanoTime();
                        if (remNs <= 0L) {
                            runSubFrame = true;
                            break;
                        }
                        if (remNs <= 2_000_000L) {
                            // Precision wait inside final 2 ms window without releasing lock too late
                            LockSupport.parkNanos(Math.min(remNs, 500_000L));
                            if (System.nanoTime() >= targetRenderNs) {
                                runSubFrame = true;
                                break;
                            }
                        } else {
                            try {
                                frameLock.wait(remNs / 1_000_000L, (int) (remNs % 1_000_000L));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                running = false;
                                break;
                            }
                        }
                    } else {
                        try {
                            frameLock.wait(250);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            running = false;
                            break;
                        }
                    }
                }
                if (!running) break;
                if (resetHistoryRequested) {
                    resetHistoryRequested = false;
                    cadence.reset();
                    globalMotion.reset();
                    fgPendingPhase = 0;
                    hasTinyPrev = false;
                    lastAutoDup = false;
                    hasTaaHistory = false;
                    clearFlowTextures();
                }
                if (!runSubFrame) {
                    if (yuy2Input) {
                        byte[] tmp = yuy2FrontBuf;
                        yuy2FrontBuf = yuy2BackBuf;
                        yuy2BackBuf = tmp;
                        isDup = yuy2PendingDup;
                        yuy2FrameReady = false;
                    } else {
                        availableFrames--;
                        if (!autoDetectDuplicates) {
                            isDup = dupQueue.pollFirst();
                        }
                    }
                }
            }

            final long tStartNs = System.nanoTime();
            if (runSubFrame) {
                try {
                    renderFgSubFrame();
                } catch (Exception e) {
                    Log.w(TAG, "renderFgSubFrame error", e);
                    fgPendingPhase = 0;
                }
                reportAdpfWorkDuration(System.nanoTime() - tStartNs);
                continue;
            }

            try {
                if (yuy2Input) {
                    yuy2UploadBuf.clear();
                    yuy2UploadBuf.put(yuy2FrontBuf, 0, width * height * 2);
                    yuy2UploadBuf.flip();
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texYuy2);
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width / 2, height,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, yuy2UploadBuf);
                } else {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(stMatrix);
                    synchronized (frameLock) {
                        while (availableFrames > 0 && (autoDetectDuplicates || !dupQueue.isEmpty())) {
                            availableFrames--;
                            if (!autoDetectDuplicates) {
                                isDup = dupQueue.pollFirst();
                            }
                            surfaceTexture.updateTexImage();
                            surfaceTexture.getTransformMatrix(stMatrix);
                        }
                    }
                    if (autoDetectDuplicates && enhanceMode >= MODE_FG60) {
                        isDup = detectAutoDuplicate();
                    }
                }
                renderFrame(isDup);
            } catch (Exception e) {
                Log.w(TAG, "renderFrame error", e);
            }
            reportAdpfWorkDuration(System.nanoTime() - tStartNs);
        }

        cleanupGl();
        cleanupEgl();
        closePerformanceHintSession();
        Log.i(TAG, "CaptureGlRenderer released");
    }

    private boolean initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed");
            return false;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed");
            return false;
        }

        final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
        int[] configAttribsRecordable = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribsRecordable, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                Log.e(TAG, "eglChooseConfig failed");
                return false;
            }
        }
        eglConfig = configs[0];

        // Try GLES 3.1 first (compute shaders + imageLoad/imageStore); fall back to GLES 3.0.
        int[] ctxAttribs31 = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                EGL_CONTEXT_MINOR_VERSION_KHR, 1,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs31, 0);
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            es31Supported = true;
        } else {
            int[] ctxAttribs30 = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs30, 0);
            es31Supported = false;
        }
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed");
            return false;
        }

        int[] surfAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, surfAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed");
            return false;
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed");
            return false;
        }
        EGL14.eglSwapInterval(eglDisplay, 0);
        return true;
    }

    private void probeExtensions() {
        String exts = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        if (exts == null) exts = "";
        boolean hasFloatExt = exts.contains("GL_EXT_color_buffer_float")
                || exts.contains("GL_EXT_color_buffer_half_float");
        float16FlowSupported = hasFloatExt && probeFloat16Fbo();

        qcomMotionSupported = exts.contains("GL_QCOM_motion_estimation");
        if (qcomMotionSupported) {
            int[] bx = new int[1];
            int[] by = new int[1];
            GLES30.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM, bx, 0);
            GLES30.glGetIntegerv(MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM, by, 0);
            Log.i(TAG, String.format("GL_QCOM_motion_estimation detected (block=%dx%d)", bx[0], by[0]));
        }
    }

    private boolean probeFloat16Fbo() {
        int[] tex = new int[1];
        int[] fbo = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 16, 16, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null);
        GLES30.glGenFramebuffers(1, fbo, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex[0], 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glDeleteFramebuffers(1, fbo, 0);
        GLES30.glDeleteTextures(1, tex, 0);
        return status == GLES30.GL_FRAMEBUFFER_COMPLETE;
    }

    private boolean initGlResources() {
        probeExtensions();

        int[] vaos = new int[1];
        int[] vbos = new int[1];
        GLES30.glGenVertexArrays(1, vaos, 0);
        GLES30.glGenBuffers(1, vbos, 0);
        vaoId = vaos[0];
        vboId = vbos[0];

        float[] quad = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };
        FloatBuffer fb = ByteBuffer.allocateDirect(quad.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(quad).position(0);

        GLES30.glBindVertexArray(vaoId);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.length * 4, fb, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glBindVertexArray(0);

        if (yuy2Input) {
            int[] yuyTex = new int[1];
            GLES30.glGenTextures(1, yuyTex, 0);
            texYuy2 = yuyTex[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texYuy2);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width / 2, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            int yuyBytes = width * height * 2;
            yuy2UploadBuf = ByteBuffer.allocateDirect(yuyBytes);
            yuy2FrontBuf = new byte[yuyBytes];
            yuy2BackBuf = new byte[yuyBytes];
            yuy2PrevSample = new byte[TINY_W * TINY_H];
        } else {
            int[] tex = new int[1];
            GLES30.glGenTextures(1, tex, 0);
            oesTexId = tex[0];
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

            surfaceTexture = new SurfaceTexture(oesTexId);
            surfaceTexture.setDefaultBufferSize(width, height);
            surfaceTexture.setOnFrameAvailableListener(st -> {
                synchronized (frameLock) {
                    availableFrames++;
                    frameLock.notifyAll();
                }
            });
            decoderSurface = new Surface(surfaceTexture);
        }

        progOesCopy = linkProgram(VERT_FBO, FRAG_OES_COPY);
        progYuy2Copy = yuy2Input ? linkProgram(VERT_FBO, FRAG_YUY2_COPY) : 0;
        progLuma = linkProgram(VERT_FBO, FRAG_LUMA);
        progDownsample = linkProgram(VERT_FBO, FRAG_DOWNSAMPLE);
        progFlowCoarse = linkProgram(VERT_FBO, FRAG_FLOW_COARSE);
        progFlowFine = linkProgram(VERT_FBO, FRAG_FLOW_FINE);
        progFlowSmooth = linkProgram(VERT_FBO, FRAG_FLOW_SMOOTH);
        progFlowTiny = linkProgram(VERT_FBO, FRAG_FLOW_TINY);
        progWarp = linkProgram(VERT_FBO, FRAG_WARP);
        progTaa = linkProgram(VERT_FBO, FRAG_TAA_FOLIAGE);
        progRcas = linkProgram(VERT_FBO, FRAG_RCAS);

        if (progOesCopy == 0 || (yuy2Input && progYuy2Copy == 0) || progLuma == 0 || progDownsample == 0
                || progFlowCoarse == 0 || progFlowFine == 0 || progFlowSmooth == 0
                || progFlowTiny == 0 || progWarp == 0 || progTaa == 0 || progRcas == 0) {
            return false;
        }

        if (es31Supported && float16FlowSupported) {
            progCompCoarse = linkComputeProgram(COMP_FLOW_COARSE);
            progCompFine = linkComputeProgram(COMP_FLOW_FINE);
            computeFlowSupported = (progCompCoarse != 0 && progCompFine != 0);
            if (computeFlowSupported) {
                uCompCoarsePrev = GLES30.glGetUniformLocation(progCompCoarse, "uPrevLumaLo");
                uCompCoarseCurr = GLES30.glGetUniformLocation(progCompCoarse, "uCurrLumaLo");
                uCompCoarsePrevFlow = GLES30.glGetUniformLocation(progCompCoarse, "uPrevFlowTex");
                uCompCoarseGlobal = GLES30.glGetUniformLocation(progCompCoarse, "uGlobalMotion");

                uCompFinePrev = GLES30.glGetUniformLocation(progCompFine, "uPrevLumaHi");
                uCompFineCurr = GLES30.glGetUniformLocation(progCompFine, "uCurrLumaHi");
                uCompFineCoarse = GLES30.glGetUniformLocation(progCompFine, "uCoarseFlow");
            }
        }

        uOesCopyTex = GLES30.glGetUniformLocation(progOesCopy, "uOesTex");
        uOesCopyStMatrix = GLES30.glGetUniformLocation(progOesCopy, "uStMatrix");
        uOesCopyVScale = GLES30.glGetUniformLocation(progOesCopy, "uVScale");
        if (yuy2Input) {
            uYuy2Tex = GLES30.glGetUniformLocation(progYuy2Copy, "uYuy2Tex");
            uYuy2Width = GLES30.glGetUniformLocation(progYuy2Copy, "uYuy2Width");
        }

        uLumaTex = GLES30.glGetUniformLocation(progLuma, "uVideoTex");
        uLumaSrcTexel = GLES30.glGetUniformLocation(progLuma, "uSrcTexelSize");
        uDownsampleHi = GLES30.glGetUniformLocation(progDownsample, "uHiLumaTex");

        uCoarsePrev = GLES30.glGetUniformLocation(progFlowCoarse, "uPrevLumaLo");
        uCoarseCurr = GLES30.glGetUniformLocation(progFlowCoarse, "uCurrLumaLo");
        uCoarsePrevFlow = GLES30.glGetUniformLocation(progFlowCoarse, "uPrevFlowTex");
        uCoarseGlobal = GLES30.glGetUniformLocation(progFlowCoarse, "uGlobalMotion");

        uFinePrev = GLES30.glGetUniformLocation(progFlowFine, "uPrevLumaHi");
        uFineCurr = GLES30.glGetUniformLocation(progFlowFine, "uCurrLumaHi");
        uFineCoarse = GLES30.glGetUniformLocation(progFlowFine, "uCoarseFlow");

        uSmoothRaw = GLES30.glGetUniformLocation(progFlowSmooth, "uRawFlowTex");
        uSmoothLuma = GLES30.glGetUniformLocation(progFlowSmooth, "uCurrLuma");
        uSmoothPrevFlow = GLES30.glGetUniformLocation(progFlowSmooth, "uPrevFlowTex");
        uSmoothStep = GLES30.glGetUniformLocation(progFlowSmooth, "uStepSize");
        uSmoothTemporalW = GLES30.glGetUniformLocation(progFlowSmooth, "uTemporalWeight");

        uFlowTinyTex = GLES30.glGetUniformLocation(progFlowTiny, "uCoarseFlowTex");

        uWarpPrev = GLES30.glGetUniformLocation(progWarp, "uPrevTex");
        uWarpCurr = GLES30.glGetUniformLocation(progWarp, "uCurrTex");
        uWarpFlow = GLES30.glGetUniformLocation(progWarp, "uFlowTex");
        uWarpFlowBwd = GLES30.glGetUniformLocation(progWarp, "uFlowBwdTex");
        uWarpAlpha = GLES30.glGetUniformLocation(progWarp, "uAlpha");
        uWarpConfScale = GLES30.glGetUniformLocation(progWarp, "uConfScale");
        uWarpGlobal = GLES30.glGetUniformLocation(progWarp, "uGlobalMotion");

        uTaaCurr = GLES30.glGetUniformLocation(progTaa, "uCurrTex");
        uTaaHist = GLES30.glGetUniformLocation(progTaa, "uHistoryTex");
        uTaaFlow = GLES30.glGetUniformLocation(progTaa, "uFlowTex");
        uTaaTexel = GLES30.glGetUniformLocation(progTaa, "uTexelSize");
        uTaaBlendMax = GLES30.glGetUniformLocation(progTaa, "uBlendMax");

        uRcasInput = GLES30.glGetUniformLocation(progRcas, "uInputTex");
        uRcasTexelSize = GLES30.glGetUniformLocation(progRcas, "uTexelSize");
        uRcasSharpness = GLES30.glGetUniformLocation(progRcas, "uSharpness");
        uRcasSuperRes = GLES30.glGetUniformLocation(progRcas, "uSuperRes");
        uRcasLookMode = GLES30.glGetUniformLocation(progRcas, "uLookMode");

        int[] pair = new int[2];
        createTexFbo(width, height, false, pair); texPrev = pair[0]; fboPrev = pair[1];
        createTexFbo(width, height, false, pair); texCurr = pair[0]; fboCurr = pair[1];
        createTexFbo(width, height, false, pair); texMid = pair[0]; fboMid = pair[1];
        createTexFbo(width, height, false, pair); texTaaPrev = pair[0]; fboTaaPrev = pair[1];
        createTexFbo(width, height, false, pair); texTaaCurr = pair[0]; fboTaaCurr = pair[1];

        createTexFbo(FLOW_HI_W, FLOW_HI_H, false, pair); texLumaHiPrev = pair[0]; fboLumaHiPrev = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, false, pair); texLumaHiCurr = pair[0]; fboLumaHiCurr = pair[1];
        createTexFbo(FLOW_LO_W, FLOW_LO_H, false, pair); texLumaLoPrev = pair[0]; fboLumaLoPrev = pair[1];
        createTexFbo(FLOW_LO_W, FLOW_LO_H, false, pair); texLumaLoCurr = pair[0]; fboLumaLoCurr = pair[1];

        createTexFbo(FLOW_LO_W, FLOW_LO_H, float16FlowSupported, pair); texFlowCoarse = pair[0]; fboFlowCoarse = pair[1];
        createTexFbo(FLOW_LO_W, FLOW_LO_H, float16FlowSupported, pair); texFlowBwdRaw = pair[0]; fboFlowBwdRaw = pair[1];
        createTexFbo(FLOW_LO_W, FLOW_LO_H, float16FlowSupported, pair); texFlowBwd = pair[0]; fboFlowBwd = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, float16FlowSupported, pair); texFlowFine = pair[0]; fboFlowFine = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, float16FlowSupported, pair); texFlowSmooth = pair[0]; fboFlowSmooth = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, float16FlowSupported, pair); texFlowPrev = pair[0]; fboFlowPrev = pair[1];

        createTexFbo(TINY_W, TINY_H, false, pair); texFlowTiny = pair[0]; fboFlowTiny = pair[1];
        GLES30.glGenBuffers(2, flowPboIds, 0);
        for (int i = 0; i < 2; i++) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, flowPboIds[i]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, TINY_W * TINY_H * 4, null, GLES30.GL_STREAM_READ);
            flowPboFences[i] = 0L;
            flowPboPending[i] = false;
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

        if (autoDetectDuplicates && !yuy2Input) {
            createTexFbo(TINY_W, TINY_H, false, pair); texLumaTiny = pair[0]; fboLumaTiny = pair[1];
            tinyCurrBuf = ByteBuffer.allocateDirect(TINY_W * TINY_H * 4);
            tinyCurrBytes = new byte[TINY_W * TINY_H * 4];
            tinyPrevBytes = new byte[TINY_W * TINY_H * 4];
        }

        clearFlowTextures();
        return true;
    }

    private void clearFlowTextures() {
        int[] flowFbos = {fboFlowCoarse, fboFlowBwdRaw, fboFlowBwd, fboFlowFine, fboFlowSmooth, fboFlowPrev};
        GLES30.glClearColor(128.0f / 255.0f, 128.0f / 255.0f, 0.0f, 0.0f);
        for (int fbo : flowFbos) {
            if (fbo == 0) continue;
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void renderFrame(boolean isDup) {
        final int mode = enhanceMode;
        final int look = lookMode;

        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, eglDim, 0);
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, eglDim, 1);
        final int surfW = eglDim[0] > 0 ? eglDim[0] : width;
        final int surfH = eglDim[1] > 0 ? eglDim[1] : height;

        if (!loggedGeom) {
            loggedGeom = true;
            int[] ts = new int[2];
            StringBuilder sb = new StringBuilder();
            for (int t : new int[]{texCurr, texPrev, texMid, texTaaCurr}) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t);
                GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, GLES31.GL_TEXTURE_WIDTH, ts, 0);
                GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, GLES31.GL_TEXTURE_HEIGHT, ts, 1);
                sb.append(ts[0]).append('x').append(ts[1]).append(' ');
            }
            Log.i(TAG, String.format(
                    "Geom: eglSurface=%dx%d stream=%dx%d st=[sx=%.4f sy=%.4f tx=%.4f ty=%.4f] mode=%d textures=%s",
                    surfW, surfH, width, height,
                    stMatrix[0], stMatrix[5], stMatrix[12], stMatrix[13], mode, sb));
        }
        if (stMatrix[0] != lastStSx || stMatrix[5] != lastStSy                || stMatrix[12] != lastStTx || stMatrix[13] != lastStTy) {
            lastStSx = stMatrix[0];
            lastStSy = stMatrix[5];
            lastStTx = stMatrix[12];
            lastStTy = stMatrix[13];
            Log.i(TAG, String.format(
                    "Geom: surface transform changed sx=%.4f sy=%.4f tx=%.4f ty=%.4f",
                    stMatrix[0], stMatrix[5], stMatrix[12], stMatrix[13]));
        }


        GLES30.glBindVertexArray(vaoId);

        if (mode == MODE_OFF) {
            cadence.reset();
            fgPendingPhase = 0;
            hasTaaHistory = false;
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, surfW, surfH);
            if (yuy2Input) {
                drawYuy2Input();
            } else {
                GLES30.glUseProgram(progOesCopy);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
                GLES30.glUniform1i(uOesCopyTex, 0);
                GLES30.glUniformMatrix4fv(uOesCopyStMatrix, 1, false, stMatrix, 0);
                GLES30.glUniform1f(uOesCopyVScale, decoderImageVScale);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            }
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            if (listener != null) listener.onFrameRendered(false);
            if (isRecordingOrPending()) {
                copyOesToFbo(fboCurr);
                maybeRecordOutputFrame(texCurr, surfW, surfH, false, look, false);
            }
            return;
        }

        if (mode == MODE_RCAS || mode == MODE_SUPERRES) {
            cadence.reset();
            fgPendingPhase = 0;
            hasTaaHistory = false;
            copyOesToFbo(fboCurr);
            final boolean enableSr = CaptureProtocolHelper.shouldEnableSpatialSuperRes(
                    width, height, surfW, surfH, mode == MODE_SUPERRES);
            renderRcasToTarget(texCurr, surfW, surfH, enableSr, look, false);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            if (listener != null) listener.onFrameRendered(false);
            maybeRecordOutputFrame(texCurr, surfW, surfH, enableSr, look, false);
            return;
        }

        final boolean fg120 = CaptureCadenceController.isFg120(mode);
        final boolean fg90 = CaptureCadenceController.isFg90(mode);
        final boolean superRes = CaptureCadenceController.isSuperResMode(mode);
        final boolean taa = taaEnabled;
        final CaptureCadenceController.Action action = cadence.onInputFrame(isDup, mode, fgPendingPhase > 0);

        if (action == CaptureCadenceController.Action.DEFER_TO_SUBFRAME_TIMER) {
            return;
        }

        boolean generated = false;
        boolean scheduleSubFrames = false;
        int displayTex = (taa && hasTaaHistory) ? texTaaCurr : texCurr;

        switch (action) {
            case PASSTHROUGH_REAL: {
                if (!isDup || cadence.getUniqueCount() <= 1) {
                    rotateAndIngestUniqueFrame();
                    if (taa && cadence.getUniqueCount() >= 2) {
                        runOpticalFlowPass(false);
                        applyTaaFoliage();
                    } else {
                        hasTaaHistory = false;
                    }
                }
                fgPendingPhase = 0;
                displayTex = (taa && hasTaaHistory) ? texTaaCurr : texCurr;
                break;
            }
            case HOLD_30_REAL: {
                // Real-frame-first FG60: WARP_30_PAIR already presented the real frame on U, so the
                // duplicate slot 16.67 ms later presents the extrapolated midpoint.
                if (!fg90 && !fg120 && cadence.getUniqueCount() >= 2) {
                    runWarpPass(1.5f, 0.85f);
                    displayTex = texMid;
                    generated = true;
                } else {
                    displayTex = (taa && hasTaaHistory) ? texTaaCurr : texCurr;
                }
                fgPendingPhase = 0;
                break;
            }
            case EXTRAP_GAP_FILL: {
                // Zero-latency gap-fill for an isolated 1-frame drop at 60 fps: extrapolate forward
                // from (prev -> curr) without waiting or modifying texCurr history.
                runWarpPass(1.35f, 0.65f);
                displayTex = texMid;
                generated = true;
                fgPendingPhase = 0;
                break;
            }
            case WARP_30_PAIR: {
                rotateAndIngestUniqueFrame();
                runOpticalFlowPass(true);
                if (taa) {
                    applyTaaFoliage();
                } else {
                    hasTaaHistory = false;
                }
                // Always present the real frame immediately so real frames are never pre-empted.
                displayTex = (taa && hasTaaHistory) ? texTaaCurr : texCurr;
                generated = false;
                if (fg90 || fg120) {
                    fgSubFrameCount = fg120 ? 3 : 2;
                    fgPendingPhase = 1;
                    scheduleSubFrames = true;
                } else {
                    fgPendingPhase = 0;
                }
                break;
            }
            case WARP_60_TO_120: {
                rotateAndIngestUniqueFrame();
                runOpticalFlowPass(true);
                if (taa) {
                    applyTaaFoliage();
                } else {
                    hasTaaHistory = false;
                }
                // Real-frame-first 60->120 fps: present real frame immediately on arrival, then
                // schedule 1 extrapolated sub-frame (+8.33 ms) afterward so real frames are never dropped.
                displayTex = (taa && hasTaaHistory) ? texTaaCurr : texCurr;
                generated = false;
                fgSubFrameCount = 1;
                fgPendingPhase = 1;
                scheduleSubFrames = true;
                break;
            }
            default:
                break;
        }

        final boolean enableSr = CaptureProtocolHelper.shouldEnableSpatialSuperRes(
                width, height, surfW, surfH, superRes);
        renderRcasToTarget(displayTex, surfW, surfH, enableSr, look, generated);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        if (scheduleSubFrames) {
            fgNextDueNs = framePacer.firstSubFrameDueNs(System.nanoTime());
        }
        if (listener != null) listener.onFrameRendered(generated);
        maybeRecordOutputFrame(displayTex, surfW, surfH, enableSr, look, generated);
    }

    private void rotateAndIngestUniqueFrame() {
        int tmpT = texPrev; texPrev = texCurr; texCurr = tmpT;
        int tmpF = fboPrev; fboPrev = fboCurr; fboCurr = tmpF;

        tmpT = texLumaHiPrev; texLumaHiPrev = texLumaHiCurr; texLumaHiCurr = tmpT;
        tmpF = fboLumaHiPrev; fboLumaHiPrev = fboLumaHiCurr; fboLumaHiCurr = tmpF;

        tmpT = texLumaLoPrev; texLumaLoPrev = texLumaLoCurr; texLumaLoCurr = tmpT;
        tmpF = fboLumaLoPrev; fboLumaLoPrev = fboLumaLoCurr; fboLumaLoCurr = tmpF;

        copyOesToFbo(fboCurr);

        // 4-tap bilinear box luma downsample (averages all 16 source pixels per 640x360 texel)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboLumaHiCurr);
        GLES30.glViewport(0, 0, FLOW_HI_W, FLOW_HI_H);
        GLES30.glUseProgram(progLuma);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurr);
        GLES30.glUniform1i(uLumaTex, 0);
        GLES30.glUniform2f(uLumaSrcTexel, 1.0f / width, 1.0f / height);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboLumaLoCurr);
        GLES30.glViewport(0, 0, FLOW_LO_W, FLOW_LO_H);
        GLES30.glUseProgram(progDownsample);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaHiCurr);
        GLES30.glUniform1i(uDownsampleHi, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void runOpticalFlowPass(boolean computeBackward) {
        pollGlobalMotionPbo();

        final float gTx = globalMotion.getTx();
        final float gTy = globalMotion.getTy();
        final float gSx = globalMotion.getSx();
        final float gSy = globalMotion.getSy();
        final boolean useCompute = computeFlowSupported && computeFlowEnabled;

        if (useCompute) {
            // Forward coarse flow (160x90) via GLES 3.1 compute shader
            GLES30.glUseProgram(progCompCoarse);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoPrev);
            GLES30.glUniform1i(uCompCoarsePrev, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoCurr);
            GLES30.glUniform1i(uCompCoarseCurr, 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowPrev);
            GLES30.glUniform1i(uCompCoarsePrevFlow, 2);
            GLES30.glUniform4f(uCompCoarseGlobal, gTx, gTy, gSx, gSy);
            GLES31.glBindImageTexture(0, texFlowCoarse, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
            GLES31.glDispatchCompute((FLOW_LO_W + 15) / 16, (FLOW_LO_H + 7) / 8, 1);

            if (computeBackward) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoCurr);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoPrev);
                GLES30.glUniform4f(uCompCoarseGlobal, -gTx, -gTy, -gSx, -gSy);
                GLES31.glBindImageTexture(0, texFlowBwdRaw, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
                GLES31.glDispatchCompute((FLOW_LO_W + 15) / 16, (FLOW_LO_H + 7) / 8, 1);
            }
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            // Fine optical flow (640x360) via GLES 3.1 compute shader
            GLES30.glUseProgram(progCompFine);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaHiPrev);
            GLES30.glUniform1i(uCompFinePrev, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaHiCurr);
            GLES30.glUniform1i(uCompFineCurr, 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowCoarse);
            GLES30.glUniform1i(uCompFineCoarse, 2);
            GLES31.glBindImageTexture(0, texFlowFine, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
            GLES31.glDispatchCompute((FLOW_HI_W + 15) / 16, (FLOW_HI_H + 7) / 8, 1);
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            GLES30.glBindVertexArray(vaoId);
        } else {
            // Fragment fallback path (GLES 3.0 compatible)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowCoarse);
            GLES30.glViewport(0, 0, FLOW_LO_W, FLOW_LO_H);
            GLES30.glUseProgram(progFlowCoarse);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoPrev);
            GLES30.glUniform1i(uCoarsePrev, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoCurr);
            GLES30.glUniform1i(uCoarseCurr, 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowPrev);
            GLES30.glUniform1i(uCoarsePrevFlow, 2);
            GLES30.glUniform4f(uCoarseGlobal, gTx, gTy, gSx, gSy);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

            if (computeBackward) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowBwdRaw);
                GLES30.glViewport(0, 0, FLOW_LO_W, FLOW_LO_H);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoCurr);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoPrev);
                GLES30.glUniform4f(uCoarseGlobal, -gTx, -gTy, -gSx, -gSy);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowFine);
            GLES30.glViewport(0, 0, FLOW_HI_W, FLOW_HI_H);
            GLES30.glUseProgram(progFlowFine);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaHiPrev);
            GLES30.glUniform1i(uFinePrev, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaHiCurr);
            GLES30.glUniform1i(uFineCurr, 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowCoarse);
            GLES30.glUniform1i(uFineCoarse, 2);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        // Smooth forward flow (640x360) with dilated bilateral + temporal filter
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowSmooth);
        GLES30.glViewport(0, 0, FLOW_HI_W, FLOW_HI_H);
        GLES30.glUseProgram(progFlowSmooth);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowFine);
        GLES30.glUniform1i(uSmoothRaw, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaHiCurr);
        GLES30.glUniform1i(uSmoothLuma, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowPrev);
        GLES30.glUniform1i(uSmoothPrevFlow, 2);
        GLES30.glUniform2f(uSmoothStep, 1.0f / FLOW_HI_W, 1.0f / FLOW_HI_H);
        GLES30.glUniform1f(uSmoothTemporalW, 0.35f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        if (computeBackward) {
            // Smooth backward flow (160x90) to eliminate 16x16 fbGate block seams
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowBwd);
            GLES30.glViewport(0, 0, FLOW_LO_W, FLOW_LO_H);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowBwdRaw);
            GLES30.glUniform1i(uSmoothRaw, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoPrev);
            GLES30.glUniform1i(uSmoothLuma, 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowBwdRaw);
            GLES30.glUniform1i(uSmoothPrevFlow, 2);
            GLES30.glUniform2f(uSmoothStep, 1.0f / FLOW_LO_W, 1.0f / FLOW_LO_H);
            GLES30.glUniform1f(uSmoothTemporalW, 0.0f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        // Rotate smoothed flow into texFlowPrev so next frame can temporally seed & smooth
        int tmpT = texFlowPrev; texFlowPrev = texFlowSmooth; texFlowSmooth = tmpT;
        int tmpF = fboFlowPrev; fboFlowPrev = fboFlowSmooth; fboFlowSmooth = tmpF;

        enqueueGlobalMotionPboReadback();
    }

    private void pollGlobalMotionPbo() {
        final int readIdx = 1 - flowPboWriteIdx;
        if (!flowPboPending[readIdx]) return;
        final long fence = flowPboFences[readIdx];
        if (fence != 0L) {
            int waitRes = GLES30.glClientWaitSync(fence, 0, 0L);
            if (waitRes == GLES30.GL_TIMEOUT_EXPIRED) {
                return; // Skip without stalling GPU pipeline
            }
            GLES30.glDeleteSync(fence);
            flowPboFences[readIdx] = 0L;
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, flowPboIds[readIdx]);
        ByteBuffer mapped = (ByteBuffer) GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER, 0, TINY_W * TINY_H * 4, GLES30.GL_MAP_READ_BIT);
        if (mapped != null) {
            mapped.get(flowTinyBytes);
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
            globalMotion.estimate(flowTinyBytes, TINY_W, TINY_H, MAX_UV);
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        flowPboPending[readIdx] = false;
    }

    private void enqueueGlobalMotionPboReadback() {
        if (flowPboPending[flowPboWriteIdx]) return;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowTiny);
        GLES30.glViewport(0, 0, TINY_W, TINY_H);
        GLES30.glUseProgram(progFlowTiny);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowCoarse);
        GLES30.glUniform1i(uFlowTinyTex, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, flowPboIds[flowPboWriteIdx]);
        GLES30.glReadPixels(0, 0, TINY_W, TINY_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        if (flowPboFences[flowPboWriteIdx] != 0L) {
            GLES30.glDeleteSync(flowPboFences[flowPboWriteIdx]);
        }
        flowPboFences[flowPboWriteIdx] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        flowPboPending[flowPboWriteIdx] = true;
        flowPboWriteIdx = 1 - flowPboWriteIdx;
    }

    private void applyTaaFoliage() {
        if (!hasTaaHistory) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboTaaCurr);
            GLES30.glViewport(0, 0, width, height);
            GLES30.glUseProgram(progTaa);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurr);
            GLES30.glUniform1i(uTaaCurr, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurr);
            GLES30.glUniform1i(uTaaHist, 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowPrev);
            GLES30.glUniform1i(uTaaFlow, 2);
            GLES30.glUniform2f(uTaaTexel, 1.0f / width, 1.0f / height);
            GLES30.glUniform1f(uTaaBlendMax, 0.0f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            hasTaaHistory = true;
            return;
        }
        int tmpT = texTaaPrev; texTaaPrev = texTaaCurr; texTaaCurr = tmpT;
        int tmpF = fboTaaPrev; fboTaaPrev = fboTaaCurr; fboTaaCurr = tmpF;

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboTaaCurr);
        GLES30.glViewport(0, 0, width, height);
        GLES30.glUseProgram(progTaa);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurr);
        GLES30.glUniform1i(uTaaCurr, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texTaaPrev);
        GLES30.glUniform1i(uTaaHist, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowPrev);
        GLES30.glUniform1i(uTaaFlow, 2);
        GLES30.glUniform2f(uTaaTexel, 1.0f / width, 1.0f / height);
        GLES30.glUniform1f(uTaaBlendMax, 0.16f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void runWarpPass(float alpha, float confScale) {
        final int currSource = (taaEnabled && hasTaaHistory) ? texTaaCurr : texCurr;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboMid);
        GLES30.glViewport(0, 0, width, height);
        GLES30.glUseProgram(progWarp);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texPrev);
        GLES30.glUniform1i(uWarpPrev, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currSource);
        GLES30.glUniform1i(uWarpCurr, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowPrev);
        GLES30.glUniform1i(uWarpFlow, 2);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowBwd);
        GLES30.glUniform1i(uWarpFlowBwd, 3);
        GLES30.glUniform1f(uWarpAlpha, alpha);
        GLES30.glUniform1f(uWarpConfScale, confScale);
        GLES30.glUniform4f(uWarpGlobal,
                globalMotion.getTx(), globalMotion.getTy(),
                globalMotion.getSx(), globalMotion.getSy());
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void renderFgSubFrame() {
        final int mode = enhanceMode;
        final boolean fg120 = CaptureCadenceController.isFg120(mode);
        final boolean fg90 = CaptureCadenceController.isFg90(mode);
        if ((!fg90 && !fg120) || fgPendingPhase <= 0 || fgPendingPhase > fgSubFrameCount) {
            fgPendingPhase = 0;
            return;
        }
        final boolean superRes = CaptureCadenceController.isSuperResMode(mode);
        final int look = lookMode;

        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, eglDim, 0);
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, eglDim, 1);
        final int surfW = eglDim[0] > 0 ? eglDim[0] : width;
        final int surfH = eglDim[1] > 0 ? eglDim[1] : height;

        GLES30.glBindVertexArray(vaoId);

        final boolean enableSr = CaptureProtocolHelper.shouldEnableSpatialSuperRes(
                width, height, surfW, surfH, superRes);
        final float alpha = 1.0f + (float) fgPendingPhase / (float) (fgSubFrameCount + 1);
        final float confScale = (fgSubFrameCount == 1) ? 0.90f : 0.82f;
        runWarpPass(alpha, confScale);
        renderRcasToTarget(texMid, surfW, surfH, enableSr, look, true);
        try {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, fgNextDueNs);
        } catch (Throwable ignored) {}
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        if (listener != null) listener.onFrameRendered(true);
        maybeRecordOutputFrame(texMid, surfW, surfH, enableSr, look, true);

        fgPendingPhase++;
        if (fgPendingPhase <= fgSubFrameCount) {
            fgNextDueNs = framePacer.advanceSubFrameDueNs(fgNextDueNs, System.nanoTime());
        } else {
            fgPendingPhase = 0;
        }
    }

    private boolean detectAutoDuplicate() {
        GLES30.glBindVertexArray(vaoId);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboLumaTiny);
        GLES30.glViewport(0, 0, TINY_W, TINY_H);
        GLES30.glUseProgram(progOesCopy);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES30.glUniform1i(uOesCopyTex, 0);
        GLES30.glUniformMatrix4fv(uOesCopyStMatrix, 1, false, stMatrix, 0);
        GLES30.glUniform1f(uOesCopyVScale, decoderImageVScale);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        tinyCurrBuf.clear();
        GLES30.glReadPixels(0, 0, TINY_W, TINY_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, tinyCurrBuf);
        tinyCurrBuf.rewind();
        tinyCurrBuf.get(tinyCurrBytes);

        float mad = 0f;
        boolean dup = false;
        if (hasTinyPrev) {
            long sumAbs = 0L;
            final int nPixels = TINY_W * TINY_H;
            for (int i = 0; i < nPixels * 4; i += 4) {
                sumAbs += Math.abs((tinyCurrBytes[i] & 0xFF) - (tinyPrevBytes[i] & 0xFF));
                sumAbs += Math.abs((tinyCurrBytes[i + 1] & 0xFF) - (tinyPrevBytes[i + 1] & 0xFF));
                sumAbs += Math.abs((tinyCurrBytes[i + 2] & 0xFF) - (tinyPrevBytes[i + 2] & 0xFF));
            }
            mad = (float) sumAbs / (float) (nPixels * 3);
            dup = !lastAutoDup && (mad < 1.2f);
        }

        System.arraycopy(tinyCurrBytes, 0, tinyPrevBytes, 0, tinyCurrBytes.length);
        hasTinyPrev = true;
        lastAutoDup = dup;

        autoDupInCount++;
        if (dup) autoDupTagCount++;
        autoDupMadSum += mad;
        final long nowMs = System.currentTimeMillis();
        if (autoDupLastLogMs == 0L) autoDupLastLogMs = nowMs;
        if (nowMs - autoDupLastLogMs >= 2000L) {
            final double dupPct = autoDupInCount > 0 ? (autoDupTagCount * 100.0 / autoDupInCount) : 0.0;
            final double avgMad = autoDupInCount > 0 ? (autoDupMadSum / autoDupInCount) : 0.0;
            Log.i(TAG, String.format("USB-Dup: in=%d dup=%d (%.1f%%) avgMad=%.2f",
                    autoDupInCount, autoDupTagCount, dupPct, avgMad));
            autoDupInCount = 0;
            autoDupTagCount = 0;
            autoDupMadSum = 0.0;
            autoDupLastLogMs = nowMs;
        }
        return dup;
    }

    private void copyOesToFbo(int targetFbo) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo);
        GLES30.glViewport(0, 0, width, height);
        if (yuy2Input) {
            drawYuy2Input();
            return;
        }
        GLES30.glUseProgram(progOesCopy);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES30.glUniform1i(uOesCopyTex, 0);
        GLES30.glUniformMatrix4fv(uOesCopyStMatrix, 1, false, stMatrix, 0);
        GLES30.glUniform1f(uOesCopyVScale, decoderImageVScale);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawYuy2Input() {
        GLES30.glUseProgram(progYuy2Copy);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texYuy2);
        GLES30.glUniform1i(uYuy2Tex, 0);
        GLES30.glUniform1f(uYuy2Width, (float) width);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void renderRcasToTarget(int inputTex, int targetW, int targetH,
                                    boolean enableSr, int look, boolean generated) {
        final float rcasStops = CaptureProtocolHelper.computeRcasSharpnessStops(RCAS_SHARPNESS, generated);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, targetW, targetH);
        GLES30.glUseProgram(progRcas);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTex);
        GLES30.glUniform1i(uRcasInput, 0);
        GLES30.glUniform2f(uRcasTexelSize, 1.0f / width, 1.0f / height);
        GLES30.glUniform1f(uRcasSharpness, rcasStops);
        GLES30.glUniform1f(uRcasSuperRes, enableSr ? 1.0f : 0.0f);
        GLES30.glUniform1f(uRcasLookMode, (float) look);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

    }



    private void maybeRecordOutputFrame(int inputTex, int surfW, int surfH,
                                        boolean enableSr, int look, boolean generated) {
        String startPath = null;
        int startDur = 0;
        boolean wantStop = false;
        synchronized (recordLock) {
            if (stopRecordRequested) {
                wantStop = true;
                stopRecordRequested = false;
            }
            if (pendingRecordPath != null) {
                startPath = pendingRecordPath;
                startDur = pendingRecordDurationSec;
                pendingRecordPath = null;
                pendingRecordDurationSec = 0;
            }
        }
        if (wantStop) {
            stopRecordingInternal();
        }
        if (startPath != null) {
            stopRecordingInternal();
            startRecordingInternal(startPath, startDur, surfW, surfH);
        }
        if (recordCodec == null || eglRecordSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }

        final long nowNs = System.nanoTime();
        final long ptsNs = Math.max(lastRecordPtsNs + 100_000L, Math.max(1_000_000L, nowNs - recordStartNs));
        lastRecordPtsNs = ptsNs;

        if (!EGL14.eglMakeCurrent(eglDisplay, eglRecordSurface, eglRecordSurface, eglContext)) {
            Log.w(TAG, "eglMakeCurrent(recordSurface) failed");
            stopRecordingInternal();
            return;
        }
        renderRcasToTarget(inputTex, recordW, recordH, enableSr, look, generated);
        try {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglRecordSurface, ptsNs);
        } catch (Throwable ignored) {}
        EGL14.eglSwapBuffers(eglDisplay, eglRecordSurface);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        drainRecordEncoder(false);
        if (recordCsv != null) {
            try {
                recordCsv.write(String.format(Locale.US,
                        "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
                        recordFrameIdx, ptsNs / 1000L, generated ? 1 : 0,
                        surfW, surfH, recordW, recordH,
                        enhanceMode, taaEnabled ? 1 : 0, computeFlowEnabled ? 1 : 0));
            } catch (IOException ignored) {}
        }
        recordFrameIdx++;

        if (nowNs >= recordDeadlineNs) {
            stopRecordingInternal();
        }
    }

    private void startRecordingInternal(String mp4Path, int durationSec, int surfW, int surfH) {
        if (eglConfig == null) return;
        int[][] candidates = (surfW > 2560 || surfH > 1440)
                ? new int[][]{{surfW, surfH}, {2560, 1440}, {1920, 1080}}
                : new int[][]{{surfW, surfH}, {1920, 1080}};
        boolean ok = false;
        for (int[] c : candidates) {
            if (tryConfigureRecorder(c[0], c[1])) {
                ok = true;
                break;
            }
        }
        if (!ok) return;

        try {
            recordMuxer = new MediaMuxer(mp4Path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            recordCsv = new BufferedWriter(new FileWriter(mp4Path + ".csv"));
            recordCsv.write("frameIdx,ptsUs,generated,surfW,surfH,recW,recH,mode,taa,computeFlow\n");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open MediaMuxer/CSV for " + mp4Path, t);
            stopRecordingInternal();
            return;
        }
        activeRecordPath = mp4Path;
        recordTrackIdx = -1;
        recordMuxerStarted = false;
        recordStartNs = System.nanoTime();
        recordDeadlineNs = recordStartNs + durationSec * 1_000_000_000L;
        lastRecordPtsNs = 0L;
        recordFrameIdx = 0L;
        Log.i(TAG, String.format("[record] started %dx%d -> %s (%ds)", recordW, recordH, mp4Path, durationSec));
    }

    private boolean tryConfigureRecorder(int recW, int recH) {
        MediaCodec mc = null;
        Surface surf = null;
        EGLSurface eglRec = EGL14.EGL_NO_SURFACE;
        try {
            mc = MediaCodec.createEncoderByType("video/hevc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/hevc", recW, recH);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 50_000_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 120);
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 120);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surf = mc.createInputSurface();
            int[] surfAttribs = {EGL14.EGL_NONE};
            eglRec = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surf, surfAttribs, 0);
            if (eglRec == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreateWindowSurface failed for recorder");
            }
            mc.start();
            recordCodec = mc;
            recordInputSurface = surf;
            eglRecordSurface = eglRec;
            recordW = recW;
            recordH = recH;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Recorder config " + recW + "x" + recH + " failed: " + t.getMessage());
            if (eglRec != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglRec);
            if (surf != null) {
                try { surf.release(); } catch (Exception ignored) {}
            }
            if (mc != null) {
                try { mc.stop(); } catch (Exception ignored) {}
                try { mc.release(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private void drainRecordEncoder(boolean eos) {
        if (recordCodec == null) return;
        final long timeoutUs = eos ? 10_000L : 0L;
        final int maxLoops = eos ? 200 : 16;
        for (int i = 0; i < maxLoops; i++) {
            int outIdx;
            try {
                outIdx = recordCodec.dequeueOutputBuffer(recordBufInfo, timeoutUs);
            } catch (IllegalStateException e) {
                break;
            }
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos) break;
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (recordMuxer != null && !recordMuxerStarted) {
                    recordTrackIdx = recordMuxer.addTrack(recordCodec.getOutputFormat());
                    recordMuxer.start();
                    recordMuxerStarted = true;
                }
            } else if (outIdx >= 0) {
                ByteBuffer buf = recordCodec.getOutputBuffer(outIdx);
                if (buf != null && (recordBufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && recordBufInfo.size > 0 && recordMuxerStarted && recordTrackIdx >= 0) {
                    buf.position(recordBufInfo.offset);
                    buf.limit(recordBufInfo.offset + recordBufInfo.size);
                    try {
                        recordMuxer.writeSampleData(recordTrackIdx, buf, recordBufInfo);
                    } catch (Throwable t) {
                        Log.w(TAG, "MediaMuxer writeSampleData failed: " + t.getMessage());
                    }
                }
                recordCodec.releaseOutputBuffer(outIdx, false);
                if ((recordBufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    private void stopRecordingInternal() {
        if (recordCodec != null) {
            try {
                recordCodec.signalEndOfInputStream();
                drainRecordEncoder(true);
            } catch (Throwable ignored) {}
            try { recordCodec.stop(); } catch (Exception ignored) {}
            try { recordCodec.release(); } catch (Exception ignored) {}
            recordCodec = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }
        if (eglRecordSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglRecordSurface);
            eglRecordSurface = EGL14.EGL_NO_SURFACE;
        }
        if (recordInputSurface != null) {
            try { recordInputSurface.release(); } catch (Exception ignored) {}
            recordInputSurface = null;
        }
        if (recordMuxer != null) {
            if (recordMuxerStarted) {
                try { recordMuxer.stop(); } catch (Exception ignored) {}
            }
            try { recordMuxer.release(); } catch (Exception ignored) {}
            recordMuxer = null;
        }
        recordMuxerStarted = false;
        recordTrackIdx = -1;
        if (recordCsv != null) {
            try { recordCsv.flush(); recordCsv.close(); } catch (Exception ignored) {}
            recordCsv = null;
        }
        if (activeRecordPath != null) {
            Log.i(TAG, String.format(Locale.US, "[record] saved %d frames (%dx%d) to %s",
                    recordFrameIdx, recordW, recordH, activeRecordPath));
            activeRecordPath = null;
        }
    }

    private static void createTexFbo(int w, int h, boolean float16, int[] outTexFbo) {
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        int tex = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        // Immutable storage (glTexStorage2D) is required by GLES 3.1 glBindImageTexture()
        // for compute shader imageStore(); mutable glTexImage2D() triggers GL_INVALID_OPERATION (0x502).
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1,
                float16 ? GLES30.GL_RGBA16F : GLES30.GL_RGBA8, w, h);

        GLES30.glGenFramebuffers(1, ids, 0);
        int fbo = ids[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        outTexFbo[0] = tex;
        outTexFbo[1] = fbo;
    }

    private static int compileShader(int type, String src) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, src);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static int linkProgram(String vsSrc, String fsSrc) {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc);
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES30.glDeleteShader(vs);
            if (fs != 0) GLES30.glDeleteShader(fs);
            return 0;
        }
        int prog = GLES30.glCreateProgram();
        GLES30.glAttachShader(prog, vs);
        GLES30.glAttachShader(prog, fs);
        GLES30.glLinkProgram(prog);
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        int[] status = new int[1];
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: " + GLES30.glGetProgramInfoLog(prog));
            GLES30.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int linkComputeProgram(String csSrc) {
        int cs = compileShader(GLES31.GL_COMPUTE_SHADER, csSrc);
        if (cs == 0) return 0;
        int prog = GLES30.glCreateProgram();
        GLES30.glAttachShader(prog, cs);
        GLES30.glLinkProgram(prog);
        GLES30.glDeleteShader(cs);
        int[] status = new int[1];
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Compute program link error: " + GLES30.glGetProgramInfoLog(prog));
            GLES30.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private void cleanupGl() {
        stopRecordingInternal();
        if (decoderSurface != null) {
            try { decoderSurface.release(); } catch (Exception ignored) {}
            decoderSurface = null;
        }
        if (surfaceTexture != null) {
            try { surfaceTexture.release(); } catch (Exception ignored) {}
            surfaceTexture = null;
        }
        if (eglContext == EGL14.EGL_NO_CONTEXT) return;

        for (int i = 0; i < 2; i++) {
            if (flowPboFences[i] != 0L) {
                GLES30.glDeleteSync(flowPboFences[i]);
                flowPboFences[i] = 0L;
            }
        }
        GLES30.glDeleteBuffers(2, flowPboIds, 0);

        int[] progs = {progOesCopy, progYuy2Copy, progLuma, progDownsample, progFlowCoarse,
                progFlowFine, progCompCoarse, progCompFine, progFlowSmooth, progFlowTiny,
                progWarp, progTaa, progRcas};
        for (int p : progs) {
            if (p != 0) GLES30.glDeleteProgram(p);
        }

        int[] fbos = {fboPrev, fboCurr, fboMid, fboTaaPrev, fboTaaCurr,
                fboLumaHiPrev, fboLumaHiCurr, fboLumaLoPrev, fboLumaLoCurr,
                fboFlowCoarse, fboFlowBwdRaw, fboFlowBwd, fboFlowFine, fboFlowSmooth,
                fboFlowPrev, fboFlowTiny, fboLumaTiny};
        GLES30.glDeleteFramebuffers(fbos.length, fbos, 0);

        int[] texs = {oesTexId, texYuy2, texPrev, texCurr, texMid, texTaaPrev, texTaaCurr,
                texLumaHiPrev, texLumaHiCurr, texLumaLoPrev, texLumaLoCurr,
                texFlowCoarse, texFlowBwdRaw, texFlowBwd, texFlowFine, texFlowSmooth,
                texFlowPrev, texFlowTiny, texLumaTiny};
        GLES30.glDeleteTextures(texs.length, texs, 0);

        if (vboId != 0) GLES30.glDeleteBuffers(1, new int[]{vboId}, 0);
        if (vaoId != 0) GLES30.glDeleteVertexArrays(1, new int[]{vaoId}, 0);
    }

    private void cleanupEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
    }

    static final String VERT_FBO = "#version 300 es\n"
            + "layout(location=0) in vec2 aPos;\n"
            + "out vec2 vUv;\n"
            + "void main() {\n"
            + "  vUv = aPos * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";

    static final String FRAG_OES_COPY = "#version 300 es\n"
            + "#extension GL_OES_EGL_image_external_essl3 : require\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform samplerExternalOES uOesTex;\n"
            + "uniform mat4 uStMatrix;\n"
            + "uniform float uVScale;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  vec2 uv = (uStMatrix * vec4(vUv, 0.0, 1.0)).xy;\n"
            + "  uv.y = (1.0 - uVScale) + uv.y * uVScale;\n"
            + "  fragColor = vec4(texture(uOesTex, uv).rgb, 1.0);\n"
            + "}\n";

    static final String FRAG_YUY2_COPY = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uYuy2Tex;\n"
            + "uniform float uYuy2Width;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  ivec2 ts = textureSize(uYuy2Tex, 0);\n"
            + "  int w = int(uYuy2Width + 0.5);\n"
            + "  int px = clamp(int(floor(vUv.x * uYuy2Width)), 0, max(w - 1, 0));\n"
            + "  int py = clamp(int(floor((1.0 - vUv.y) * float(ts.y))), 0, max(ts.y - 1, 0));\n"
            + "  vec4 yuyv = texelFetch(uYuy2Tex, ivec2(px >> 1, py), 0);\n"
            + "  float Y = ((px & 1) == 0) ? yuyv.r : yuyv.b;\n"
            + "  float U = yuyv.g - 0.5;\n"
            + "  float V = yuyv.a - 0.5;\n"
            + "  float y = (Y - (16.0 / 255.0)) * 1.164383;\n"
            + "  float r = y + 1.792741 * V;\n"
            + "  float g = y - 0.213249 * U - 0.532909 * V;\n"
            + "  float b = y + 2.112402 * U;\n"
            + "  fragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);\n"
            + "}\n";

    // Foliage-safe 4x bilinear box prefilter (samples all 16 source pixels per 640x360 texel)
    static final String FRAG_LUMA = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uVideoTex;\n"
            + "uniform vec2 uSrcTexelSize;\n"
            + "out vec4 fragColor;\n"
            + "float luma709(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n"
            + "void main() {\n"
            + "  vec2 d = uSrcTexelSize;\n"
            + "  float y = 0.25 * (\n"
            + "    luma709(texture(uVideoTex, vUv + vec2(-d.x, -d.y)).rgb) +\n"
            + "    luma709(texture(uVideoTex, vUv + vec2( d.x, -d.y)).rgb) +\n"
            + "    luma709(texture(uVideoTex, vUv + vec2(-d.x,  d.y)).rgb) +\n"
            + "    luma709(texture(uVideoTex, vUv + vec2( d.x,  d.y)).rgb));\n"
            + "  fragColor = vec4(y, 0.0, 0.0, 1.0);\n"
            + "}\n";

    static final String FRAG_DOWNSAMPLE = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uHiLumaTex;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  vec2 d = vec2(1.0 / 640.0, 1.0 / 360.0);\n"
            + "  float y = 0.25 * (\n"
            + "    texture(uHiLumaTex, vUv + vec2(-d.x, -d.y)).r +\n"
            + "    texture(uHiLumaTex, vUv + vec2( d.x, -d.y)).r +\n"
            + "    texture(uHiLumaTex, vUv + vec2(-d.x,  d.y)).r +\n"
            + "    texture(uHiLumaTex, vUv + vec2( d.x,  d.y)).r);\n"
            + "  fragColor = vec4(y, 0.0, 0.0, 1.0);\n"
            + "}\n";

    static final String FRAG_FLOW_COARSE = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uPrevLumaLo;\n"
            + "uniform sampler2D uCurrLumaLo;\n"
            + "uniform sampler2D uPrevFlowTex;\n"
            + "uniform vec4 uGlobalMotion;\n"
            + "out vec4 fragColor;\n"
            + "const vec2 STEP = vec2(1.0 / 160.0, 1.0 / 90.0);\n"
            + "const float MAX_UV = 0.20;\n"
            + "vec2 decodeFlow(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n"
            + "float patchSad(vec2 uvCurr, vec2 uvPrev) {\n"
            + "  float s = abs(texture(uCurrLumaLo, uvCurr).r - texture(uPrevLumaLo, uvPrev).r) * 2.0;\n"
            + "  s += abs(texture(uCurrLumaLo, uvCurr + vec2(STEP.x, 0.0)).r - texture(uPrevLumaLo, uvPrev + vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uCurrLumaLo, uvCurr - vec2(STEP.x, 0.0)).r - texture(uPrevLumaLo, uvPrev - vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uCurrLumaLo, uvCurr + vec2(0.0, STEP.y)).r - texture(uPrevLumaLo, uvPrev + vec2(0.0, STEP.y)).r);\n"
            + "  s += abs(texture(uCurrLumaLo, uvCurr - vec2(0.0, STEP.y)).r - texture(uPrevLumaLo, uvPrev - vec2(0.0, STEP.y)).r);\n"
            + "  return s * (1.0 / 6.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  float bestSad = patchSad(vUv, vUv);\n"
            + "  vec2 bestOff = vec2(0.0);\n"
            + "  vec2 prevSeed = decodeFlow(texture(uPrevFlowTex, vUv).rg);\n"
            + "  vec2 globalSeed = uGlobalMotion.xy + uGlobalMotion.zw * (vUv - 0.5);\n"
            + "  float sadPrev = patchSad(vUv, clamp(vUv + prevSeed, vec2(0.002), vec2(0.998)));\n"
            + "  if (sadPrev + 0.0005 < bestSad) { bestSad = sadPrev; bestOff = prevSeed; }\n"
            + "  float sadGlob = patchSad(vUv, clamp(vUv + globalSeed, vec2(0.002), vec2(0.998)));\n"
            + "  if (sadGlob + 0.0005 < bestSad) { bestSad = sadGlob; bestOff = globalSeed; }\n"
            + "  if (bestSad > 0.007) {\n"
            + "    vec2 baseOff = bestOff;\n"
            + "    for (int dy = -4; dy <= 4; dy++) {\n"
            + "      for (int dx = -6; dx <= 6; dx++) {\n"
            + "        vec2 off = vec2(float(dx) * 2.0, float(dy) * 2.0) * STEP;\n"
            + "        float penalty = 0.0008 * length(vec2(float(dx) * 2.0, float(dy) * 2.0));\n"
            + "        float s = patchSad(vUv, clamp(vUv + off, vec2(0.002), vec2(0.998))) + penalty;\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 sSeed = bestOff;\n"
            + "    for (int dy = -2; dy <= 2; dy++) {\n"
            + "      for (int dx = -2; dx <= 2; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = baseOff + vec2(float(dx), float(dy)) * STEP;\n"
            + "        float s = patchSad(vUv, clamp(vUv + off, vec2(0.002), vec2(0.998)));\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = sSeed + vec2(float(dx), float(dy)) * STEP;\n"
            + "        float s = patchSad(vUv, clamp(vUv + off, vec2(0.002), vec2(0.998)));\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  fragColor = vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), 1.0);\n"
            + "}\n";

    static final String FRAG_FLOW_FINE = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uPrevLumaHi;\n"
            + "uniform sampler2D uCurrLumaHi;\n"
            + "uniform sampler2D uCoarseFlow;\n"
            + "out vec4 fragColor;\n"
            + "const vec2 STEP = vec2(1.0 / 640.0, 1.0 / 360.0);\n"
            + "const float MAX_UV = 0.20;\n"
            + "vec2 decodeFlow(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n"
            + "float patchSad(vec2 uvCurr, vec2 uvPrev) {\n"
            + "  float s = abs(texture(uCurrLumaHi, uvCurr).r - texture(uPrevLumaHi, uvPrev).r) * 2.0;\n"
            + "  s += abs(texture(uCurrLumaHi, uvCurr + vec2(STEP.x, 0.0)).r - texture(uPrevLumaHi, uvPrev + vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uCurrLumaHi, uvCurr - vec2(STEP.x, 0.0)).r - texture(uPrevLumaHi, uvPrev - vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uCurrLumaHi, uvCurr + vec2(0.0, STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2(0.0, STEP.y)).r);\n"
            + "  s += abs(texture(uCurrLumaHi, uvCurr - vec2(0.0, STEP.y)).r - texture(uPrevLumaHi, uvPrev - vec2(0.0, STEP.y)).r);\n"
            + "  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2( STEP.x,  STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2( STEP.x,  STEP.y)).r);\n"
            + "  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2(-STEP.x,  STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2(-STEP.x,  STEP.y)).r);\n"
            + "  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2( STEP.x, -STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2( STEP.x, -STEP.y)).r);\n"
            + "  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2(-STEP.x, -STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2(-STEP.x, -STEP.y)).r);\n"
            + "  return s * (1.0 / 8.6);\n"
            + "}\n"
            + "float patchSadRev(vec2 uvPrev, vec2 uvCurr) {\n"
            + "  float s = abs(texture(uPrevLumaHi, uvPrev).r - texture(uCurrLumaHi, uvCurr).r) * 2.0;\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev + vec2(STEP.x, 0.0)).r - texture(uCurrLumaHi, uvCurr + vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev - vec2(STEP.x, 0.0)).r - texture(uCurrLumaHi, uvCurr - vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev + vec2(0.0, STEP.y)).r - texture(uCurrLumaHi, uvCurr + vec2(0.0, STEP.y)).r);\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev - vec2(0.0, STEP.y)).r - texture(uCurrLumaHi, uvCurr - vec2(0.0, STEP.y)).r);\n"
            + "  return s * (1.0 / 6.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 initOff = decodeFlow(texture(uCoarseFlow, vUv).rg);\n"
            + "  float sad0 = patchSad(vUv, vUv);\n"
            + "  float bestSad = patchSad(vUv, vUv + initOff);\n"
            + "  vec2 bestOff = initOff;\n"
            + "  if (sad0 <= bestSad + 0.001) { bestSad = sad0; bestOff = vec2(0.0); }\n"
            + "  if (sad0 > 0.007 || length(initOff) > 0.0004) {\n"
            + "    vec2 s2 = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = s2 + vec2(float(dx) * 2.0, float(dy) * 2.0) * STEP;\n"
            + "        float s = patchSad(vUv, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 s1 = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = s1 + vec2(float(dx), float(dy)) * STEP;\n"
            + "        float s = patchSad(vUv, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 s0 = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = s0 + vec2(float(dx), float(dy)) * STEP * 0.5;\n"
            + "        float s = patchSad(vUv, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 sq = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = sq + vec2(float(dx), float(dy)) * STEP * 0.25;\n"
            + "        float s = patchSad(vUv, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 prevUv = clamp(vUv + bestOff, vec2(0.001), vec2(0.999));\n"
            + "  float revSad = patchSadRev(prevUv, vUv);\n"
            + "  vec2 revCoarse = decodeFlow(texture(uCoarseFlow, prevUv).rg);\n"
            + "  float vecAgree = clamp(1.0 - (length(revCoarse - bestOff) / MAX_UV) * 2.2, 0.0, 1.0);\n"
            + "  float gain = clamp((sad0 - bestSad) / max(sad0, 0.005), 0.0, 1.0);\n"
            + "  float matchConf = clamp(1.0 - max(bestSad, revSad) * 7.0, 0.0, 1.0);\n"
            + "  float conf = matchConf * (sad0 <= 0.007 ? 1.0 : gain) * mix(0.45, 1.0, vecAgree);\n"
            + "  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  fragColor = vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), conf);\n"
            + "}\n";

    // GLES 3.1 Compute Coarse Optical Flow (160x90) using shared-memory workgroup tile cache
    static final String COMP_FLOW_COARSE = "#version 310 es\n"
            + "layout(local_size_x = 16, local_size_y = 8) in;\n"
            + "uniform highp sampler2D uPrevLumaLo;\n"
            + "uniform highp sampler2D uCurrLumaLo;\n"
            + "uniform highp sampler2D uPrevFlowTex;\n"
            + "uniform highp vec4 uGlobalMotion;\n"
            + "layout(rgba16f, binding = 0) writeonly uniform highp image2D uOutFlow;\n"
            + "shared highp float sCurr[10][18];\n"
            + "const vec2 STEP = vec2(1.0 / 160.0, 1.0 / 90.0);\n"
            + "const float MAX_UV = 0.20;\n"
            + "vec2 decodeFlow(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n"
            + "float patchSadShared(uint lx, uint ly, vec2 uvPrev) {\n"
            + "  float c0  = sCurr[ly + 1u][lx + 1u];\n"
            + "  float cxP = sCurr[ly + 1u][lx + 2u];\n"
            + "  float cxM = sCurr[ly + 1u][lx];\n"
            + "  float cyP = sCurr[ly + 2u][lx + 1u];\n"
            + "  float cyM = sCurr[ly][lx + 1u];\n"
            + "  float s = abs(c0 - texture(uPrevLumaLo, uvPrev).r) * 2.0;\n"
            + "  s += abs(cxP - texture(uPrevLumaLo, uvPrev + vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(cxM - texture(uPrevLumaLo, uvPrev - vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(cyP - texture(uPrevLumaLo, uvPrev + vec2(0.0, STEP.y)).r);\n"
            + "  s += abs(cyM - texture(uPrevLumaLo, uvPrev - vec2(0.0, STEP.y)).r);\n"
            + "  return s * (1.0 / 6.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  ivec2 base = ivec2(gl_WorkGroupID.xy) * ivec2(16, 8) - ivec2(1, 1);\n"
            + "  uint tid = gl_LocalInvocationID.y * 16u + gl_LocalInvocationID.x;\n"
            + "  for (uint k = 0u; k < 2u; k++) {\n"
            + "    uint flatIdx = tid + k * 128u;\n"
            + "    if (flatIdx < 180u) {\n"
            + "      int sy = int(flatIdx / 18u);\n"
            + "      int sx = int(flatIdx % 18u);\n"
            + "      ivec2 p = clamp(base + ivec2(sx, sy), ivec2(0), ivec2(159, 89));\n"
            + "      sCurr[sy][sx] = texelFetch(uCurrLumaLo, p, 0).r;\n"
            + "    }\n"
            + "  }\n"
            + "  barrier();\n"
            + "  ivec2 gid = ivec2(gl_GlobalInvocationID.xy);\n"
            + "  if (gid.x >= 160 || gid.y >= 90) return;\n"
            + "  vec2 vUv = (vec2(gid) + 0.5) * STEP;\n"
            + "  uint lx = gl_LocalInvocationID.x;\n"
            + "  uint ly = gl_LocalInvocationID.y;\n"
            + "  float bestSad = patchSadShared(lx, ly, vUv);\n"
            + "  vec2 bestOff = vec2(0.0);\n"
            + "  vec2 prevSeed = decodeFlow(texture(uPrevFlowTex, vUv).rg);\n"
            + "  vec2 globalSeed = uGlobalMotion.xy + uGlobalMotion.zw * (vUv - 0.5);\n"
            + "  float sadPrev = patchSadShared(lx, ly, clamp(vUv + prevSeed, vec2(0.002), vec2(0.998)));\n"
            + "  if (sadPrev + 0.0005 < bestSad) { bestSad = sadPrev; bestOff = prevSeed; }\n"
            + "  float sadGlob = patchSadShared(lx, ly, clamp(vUv + globalSeed, vec2(0.002), vec2(0.998)));\n"
            + "  if (sadGlob + 0.0005 < bestSad) { bestSad = sadGlob; bestOff = globalSeed; }\n"
            + "  if (bestSad > 0.007) {\n"
            + "    vec2 baseOff = bestOff;\n"
            + "    for (int dy = -4; dy <= 4; dy++) {\n"
            + "      for (int dx = -6; dx <= 6; dx++) {\n"
            + "        vec2 off = vec2(float(dx) * 2.0, float(dy) * 2.0) * STEP;\n"
            + "        float penalty = 0.0008 * length(vec2(float(dx) * 2.0, float(dy) * 2.0));\n"
            + "        float s = patchSadShared(lx, ly, clamp(vUv + off, vec2(0.002), vec2(0.998))) + penalty;\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 sSeed = bestOff;\n"
            + "    for (int dy = -2; dy <= 2; dy++) {\n"
            + "      for (int dx = -2; dx <= 2; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = baseOff + vec2(float(dx), float(dy)) * STEP;\n"
            + "        float s = patchSadShared(lx, ly, clamp(vUv + off, vec2(0.002), vec2(0.998)));\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = sSeed + vec2(float(dx), float(dy)) * STEP;\n"
            + "        float s = patchSadShared(lx, ly, clamp(vUv + off, vec2(0.002), vec2(0.998)));\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  imageStore(uOutFlow, gid, vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), 1.0));\n"
            + "}\n";

    // GLES 3.1 Compute Fine Optical Flow (640x360) using shared-memory workgroup tile cache
    static final String COMP_FLOW_FINE = "#version 310 es\n"
            + "layout(local_size_x = 16, local_size_y = 8) in;\n"
            + "uniform highp sampler2D uPrevLumaHi;\n"
            + "uniform highp sampler2D uCurrLumaHi;\n"
            + "uniform highp sampler2D uCoarseFlow;\n"
            + "layout(rgba16f, binding = 0) writeonly uniform highp image2D uOutFlow;\n"
            + "shared highp float sCurr[10][18];\n"
            + "const vec2 STEP = vec2(1.0 / 640.0, 1.0 / 360.0);\n"
            + "const float MAX_UV = 0.20;\n"
            + "vec2 decodeFlow(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n"
            + "float patchSadShared(uint lx, uint ly, vec2 uvPrev) {\n"
            + "  float s = abs(sCurr[ly + 1u][lx + 1u] - texture(uPrevLumaHi, uvPrev).r) * 2.0;\n"
            + "  s += abs(sCurr[ly + 1u][lx + 2u] - texture(uPrevLumaHi, uvPrev + vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(sCurr[ly + 1u][lx]      - texture(uPrevLumaHi, uvPrev - vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(sCurr[ly + 2u][lx + 1u] - texture(uPrevLumaHi, uvPrev + vec2(0.0, STEP.y)).r);\n"
            + "  s += abs(sCurr[ly][lx + 1u]      - texture(uPrevLumaHi, uvPrev - vec2(0.0, STEP.y)).r);\n"
            + "  s += 0.65 * abs(sCurr[ly + 2u][lx + 2u] - texture(uPrevLumaHi, uvPrev + vec2( STEP.x,  STEP.y)).r);\n"
            + "  s += 0.65 * abs(sCurr[ly + 2u][lx]      - texture(uPrevLumaHi, uvPrev + vec2(-STEP.x,  STEP.y)).r);\n"
            + "  s += 0.65 * abs(sCurr[ly][lx + 2u]      - texture(uPrevLumaHi, uvPrev + vec2( STEP.x, -STEP.y)).r);\n"
            + "  s += 0.65 * abs(sCurr[ly][lx]           - texture(uPrevLumaHi, uvPrev + vec2(-STEP.x, -STEP.y)).r);\n"
            + "  return s * (1.0 / 8.6);\n"
            + "}\n"
            + "float patchSadRev(vec2 uvPrev, vec2 uvCurr) {\n"
            + "  float s = abs(texture(uPrevLumaHi, uvPrev).r - texture(uCurrLumaHi, uvCurr).r) * 2.0;\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev + vec2(STEP.x, 0.0)).r - texture(uCurrLumaHi, uvCurr + vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev - vec2(STEP.x, 0.0)).r - texture(uCurrLumaHi, uvCurr - vec2(STEP.x, 0.0)).r);\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev + vec2(0.0, STEP.y)).r - texture(uCurrLumaHi, uvCurr + vec2(0.0, STEP.y)).r);\n"
            + "  s += abs(texture(uPrevLumaHi, uvPrev - vec2(0.0, STEP.y)).r - texture(uCurrLumaHi, uvCurr - vec2(0.0, STEP.y)).r);\n"
            + "  return s * (1.0 / 6.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  ivec2 base = ivec2(gl_WorkGroupID.xy) * ivec2(16, 8) - ivec2(1, 1);\n"
            + "  uint tid = gl_LocalInvocationID.y * 16u + gl_LocalInvocationID.x;\n"
            + "  for (uint k = 0u; k < 2u; k++) {\n"
            + "    uint flatIdx = tid + k * 128u;\n"
            + "    if (flatIdx < 180u) {\n"
            + "      int sy = int(flatIdx / 18u);\n"
            + "      int sx = int(flatIdx % 18u);\n"
            + "      ivec2 p = clamp(base + ivec2(sx, sy), ivec2(0), ivec2(639, 359));\n"
            + "      sCurr[sy][sx] = texelFetch(uCurrLumaHi, p, 0).r;\n"
            + "    }\n"
            + "  }\n"
            + "  barrier();\n"
            + "  ivec2 gid = ivec2(gl_GlobalInvocationID.xy);\n"
            + "  if (gid.x >= 640 || gid.y >= 360) return;\n"
            + "  vec2 vUv = (vec2(gid) + 0.5) * STEP;\n"
            + "  uint lx = gl_LocalInvocationID.x;\n"
            + "  uint ly = gl_LocalInvocationID.y;\n"
            + "  vec2 initOff = decodeFlow(texture(uCoarseFlow, vUv).rg);\n"
            + "  float sad0 = patchSadShared(lx, ly, vUv);\n"
            + "  float bestSad = patchSadShared(lx, ly, vUv + initOff);\n"
            + "  vec2 bestOff = initOff;\n"
            + "  if (sad0 <= bestSad + 0.001) { bestSad = sad0; bestOff = vec2(0.0); }\n"
            + "  if (sad0 > 0.007 || length(initOff) > 0.0004) {\n"
            + "    vec2 s2 = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = s2 + vec2(float(dx) * 2.0, float(dy) * 2.0) * STEP;\n"
            + "        float s = patchSadShared(lx, ly, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 s1 = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = s1 + vec2(float(dx), float(dy)) * STEP;\n"
            + "        float s = patchSadShared(lx, ly, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 s0 = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = s0 + vec2(float(dx), float(dy)) * STEP * 0.5;\n"
            + "        float s = patchSadShared(lx, ly, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "    vec2 sq = bestOff;\n"
            + "    for (int dy = -1; dy <= 1; dy++) {\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = sq + vec2(float(dx), float(dy)) * STEP * 0.25;\n"
            + "        float s = patchSadShared(lx, ly, vUv + off);\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 prevUv = clamp(vUv + bestOff, vec2(0.001), vec2(0.999));\n"
            + "  float revSad = patchSadRev(prevUv, vUv);\n"
            + "  vec2 revCoarse = decodeFlow(texture(uCoarseFlow, prevUv).rg);\n"
            + "  float vecAgree = clamp(1.0 - (length(revCoarse - bestOff) / MAX_UV) * 2.2, 0.0, 1.0);\n"
            + "  float gain = clamp((sad0 - bestSad) / max(sad0, 0.005), 0.0, 1.0);\n"
            + "  float matchConf = clamp(1.0 - max(bestSad, revSad) * 7.0, 0.0, 1.0);\n"
            + "  float conf = matchConf * (sad0 <= 0.007 ? 1.0 : gain) * mix(0.45, 1.0, vecAgree);\n"
            + "  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  imageStore(uOutFlow, gid, vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), conf));\n"
            + "}\n";

    // Dilated 9-tap bilateral + temporal flow smoother (eliminates 4x4 and 16x16 confidence tiles)
    static final String FRAG_FLOW_SMOOTH = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uRawFlowTex;\n"
            + "uniform sampler2D uCurrLuma;\n"
            + "uniform sampler2D uPrevFlowTex;\n"
            + "uniform vec2 uStepSize;\n"
            + "uniform float uTemporalWeight;\n"
            + "out vec4 fragColor;\n"
            + "const float MAX_UV = 0.20;\n"
            + "vec2 dec(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n"
            + "void main() {\n"
            + "  float cLuma = texture(uCurrLuma, vUv).r;\n"
            + "  vec4 cFlow = texture(uRawFlowTex, vUv);\n"
            + "  vec2 cVec = dec(cFlow.rg);\n"
            + "  vec2 sumVec = vec2(0.0);\n"
            + "  float sumConf = 0.0;\n"
            + "  float sumSad = 0.0;\n"
            + "  float sumW = 0.0;\n"
            + "  float maxDiff = 0.0;\n"
            + "  for (int dy = -2; dy <= 2; dy += 1) {\n"
            + "    for (int dx = -2; dx <= 2; dx += 1) {\n"
            + "      if (abs(dx) == 2 && abs(dy) == 2) continue;\n"
            + "      vec2 uv = vUv + vec2(float(dx), float(dy)) * uStepSize;\n"
            + "      vec4 nf = texture(uRawFlowTex, uv);\n"
            + "      float nl = texture(uCurrLuma, uv).r;\n"
            + "      vec2 nv = dec(nf.rg);\n"
            + "      float dist2 = float(dx * dx + dy * dy);\n"
            + "      float wSpatial = exp(-dist2 * 0.35);\n"
            + "      float wLuma = exp(-abs(nl - cLuma) * 9.0);\n"
            + "      float w = wSpatial * wLuma * (0.20 + nf.a);\n"
            + "      sumVec += nv * w;\n"
            + "      sumConf += nf.a * w;\n"
            + "      sumSad += nf.b * w;\n"
            + "      sumW += w;\n"
            + "      maxDiff = max(maxDiff, length(nv - cVec) / MAX_UV);\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 avgVec = sumVec / max(sumW, 1e-4);\n"
            + "  vec2 prevVec = dec(texture(uPrevFlowTex, vUv).rg);\n"
            + "  float disagree = clamp(length(avgVec - prevVec) / MAX_UV, 0.0, 1.0);\n"
            + "  float wPrev = (1.0 - disagree) * uTemporalWeight;\n"
            + "  vec2 blended = mix(avgVec, prevVec, wPrev);\n"
            + "  float avgConf = (sumConf / max(sumW, 1e-4)) * clamp(1.0 - maxDiff * 1.1, 0.0, 1.0);\n"
            + "  float avgSad = sumSad / max(sumW, 1e-4);\n"
            + "  vec2 enc = clamp((blended / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  fragColor = vec4(enc.x, enc.y, avgSad, avgConf);\n"
            + "}\n";

    // Downsamples 160x90 coarse flow into 32x18 RGBA8 for async PBO CPU global motion estimation
    static final String FRAG_FLOW_TINY = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uCoarseFlowTex;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  vec2 d = vec2(1.0 / 64.0, 1.0 / 36.0);\n"
            + "  vec4 f0 = texture(uCoarseFlowTex, vUv + vec2(-d.x, -d.y));\n"
            + "  vec4 f1 = texture(uCoarseFlowTex, vUv + vec2( d.x, -d.y));\n"
            + "  vec4 f2 = texture(uCoarseFlowTex, vUv + vec2(-d.x,  d.y));\n"
            + "  vec4 f3 = texture(uCoarseFlowTex, vUv + vec2( d.x,  d.y));\n"
            + "  vec2 rg = 0.25 * (f0.rg + f1.rg + f2.rg + f3.rg);\n"
            + "  float sad = 0.25 * (f0.b + f1.b + f2.b + f3.b);\n"
            + "  float conf = clamp(1.0 - sad * 2.5, 0.0, 1.0);\n"
            + "  fragColor = vec4(rg.x, rg.y, sad, conf);\n"
            + "}\n";

    // 4-candidate trajectory warp (ported from FrameGenWarp.hlsl) + feathered HUD + extrapolation support
    static final String FRAG_WARP = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uPrevTex;\n"
            + "uniform sampler2D uCurrTex;\n"
            + "uniform sampler2D uFlowTex;\n"
            + "uniform sampler2D uFlowBwdTex;\n"
            + "uniform float uAlpha;\n"
            + "uniform float uConfScale;\n"
            + "uniform vec4 uGlobalMotion;\n"
            + "out vec4 fragColor;\n"
            + "const float MAX_UV = 0.20;\n"
            + "vec2 decodeFlow(vec2 raw) {\n"
            + "  return (raw - (128.0 / 255.0)) * 2.0 * MAX_UV;\n"
            + "}\n"
            + "float hudMask(vec2 uv) {\n"
            + "  float m1 = smoothstep(0.255, 0.235, uv.x) * smoothstep(0.295, 0.275, uv.y);\n"
            + "  float m2 = smoothstep(0.755, 0.775, uv.x) * smoothstep(0.765, 0.785, uv.y);\n"
            + "  float m3 = smoothstep(0.024, 0.015, abs(uv.x - 0.5)) * smoothstep(0.036, 0.025, abs(uv.y - 0.5));\n"
            + "  return clamp(max(m1, max(m2, m3)), 0.0, 1.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec3 c0 = texture(uPrevTex, vUv).rgb;\n"
            + "  vec3 c1 = texture(uCurrTex, vUv).rgb;\n"
            + "  float hMask = hudMask(vUv);\n"
            + "  if (hMask >= 0.999) {\n"
            + "    fragColor = vec4(c1, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  float es = dot(abs(c0 - c1), vec3(0.333333));\n"
            + "  if (es < 0.008) {\n"
            + "    fragColor = vec4(c1, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec4 f = texture(uFlowTex, vUv);\n"
            + "  vec2 v0 = decodeFlow(f.rg);\n"
            + "  vec2 v1 = -decodeFlow(texture(uFlowBwdTex, vUv).rg);\n"
            + "  vec2 v2 = decodeFlow(texture(uFlowTex, clamp(vUv - (1.0 - uAlpha) * v0, vec2(0.001), vec2(0.999))).rg);\n"
            + "  vec2 v3 = -decodeFlow(texture(uFlowBwdTex, clamp(vUv + uAlpha * v1, vec2(0.001), vec2(0.999))).rg);\n"
            + "  vec2 cands[4];\n"
            + "  cands[0] = v0; cands[1] = v1; cands[2] = v2; cands[3] = v3;\n"
            + "  float bestEm = 1e6;\n"
            + "  vec2 bestV = v0;\n"
            + "  vec3 bestWarped = c1;\n"
            + "  vec2 gVec = uGlobalMotion.xy + uGlobalMotion.zw * (vUv - 0.5);\n"
            + "  vec3 fallback = c1;\n"
            + "  if (uAlpha > 1.001) {\n"
            + "    float ex = uAlpha - 1.0;\n"
            + "    for (int i = 0; i < 4; i++) {\n"
            + "      vec2 v = cands[i];\n"
            + "      vec2 p1 = clamp(vUv + ex * v, vec2(0.001), vec2(0.999));\n"
            + "      vec2 p0 = clamp(p1 + v, vec2(0.001), vec2(0.999));\n"
            + "      vec3 w0 = texture(uPrevTex, p0).rgb;\n"
            + "      vec3 w1 = texture(uCurrTex, p1).rgb;\n"
            + "      float em = dot(abs(w0 - w1), vec3(0.333333));\n"
            + "      if (em < bestEm) { bestEm = em; bestV = v; bestWarped = w1; }\n"
            + "    }\n"
            + "    vec2 gP1 = clamp(vUv + ex * gVec, vec2(0.001), vec2(0.999));\n"
            + "    vec2 gP0 = clamp(gP1 + gVec, vec2(0.001), vec2(0.999));\n"
            + "    vec3 gW1 = texture(uCurrTex, gP1).rgb;\n"
            + "    float gErr = dot(abs(texture(uPrevTex, gP0).rgb - gW1), vec3(0.333333));\n"
            + "    float gFit = smoothstep(0.10, 0.015, gErr) * smoothstep(0.0, 0.015, es - gErr) * (1.0 - hMask);\n"
            + "    fallback = mix(c1, gW1, gFit);\n"
            + "  } else {\n"
            + "    for (int i = 0; i < 4; i++) {\n"
            + "      vec2 v = cands[i];\n"
            + "      vec2 p0 = clamp(vUv + uAlpha * v, vec2(0.001), vec2(0.999));\n"
            + "      vec2 p1 = clamp(vUv - (1.0 - uAlpha) * v, vec2(0.001), vec2(0.999));\n"
            + "      vec3 w0 = texture(uPrevTex, p0).rgb;\n"
            + "      vec3 w1 = texture(uCurrTex, p1).rgb;\n"
            + "      float em = dot(abs(w0 - w1), vec3(0.333333));\n"
            + "      if (em < bestEm) { bestEm = em; bestV = v; bestWarped = mix(w0, w1, uAlpha); }\n"
            + "    }\n"
            + "    vec2 gP0 = clamp(vUv + uAlpha * gVec, vec2(0.001), vec2(0.999));\n"
            + "    vec2 gP1 = clamp(vUv - (1.0 - uAlpha) * gVec, vec2(0.001), vec2(0.999));\n"
            + "    vec3 gW0 = texture(uPrevTex, gP0).rgb;\n"
            + "    vec3 gW1 = texture(uCurrTex, gP1).rgb;\n"
            + "    float gErr = dot(abs(gW0 - gW1), vec3(0.333333));\n"
            + "    float gFit = smoothstep(0.10, 0.015, gErr) * smoothstep(0.0, 0.015, es - gErr) * (1.0 - hMask);\n"
            + "    vec3 nearest = (uAlpha >= 0.5) ? c1 : c0;\n"
            + "    vec3 gNearest = (uAlpha >= 0.5) ? gW1 : gW0;\n"
            + "    fallback = mix(nearest, gNearest, gFit);\n"
            + "  }\n"
            + "  vec2 pCheck = clamp(vUv + uAlpha * bestV, vec2(0.001), vec2(0.999));\n"
            + "  vec2 vBwd = decodeFlow(texture(uFlowBwdTex, pCheck).rg);\n"
            + "  float fbErr = length(bestV + vBwd) / MAX_UV;\n"
            + "  float fbGate = smoothstep(0.42, 0.08, fbErr);\n"
            + "  float fit = smoothstep(0.11, 0.014, bestEm) * smoothstep(0.0, 0.018, es - bestEm);\n"
            + "  float sadGate = smoothstep(0.45, 0.10, f.b);\n"
            + "  float w = clamp(f.a * fit * sadGate * fbGate * uConfScale * (1.0 - hMask), 0.0, 1.0);\n"
            + "  fragColor = vec4(mix(fallback, bestWarped, w), 1.0);\n"
            + "}\n";

    // SGSR2-lite YCoCg neighborhood-clamped temporal accumulation to dissolve foliage dither
    static final String FRAG_TAA_FOLIAGE = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uCurrTex;\n"
            + "uniform sampler2D uHistoryTex;\n"
            + "uniform sampler2D uFlowTex;\n"
            + "uniform vec2 uTexelSize;\n"
            + "uniform float uBlendMax;\n"
            + "out vec4 fragColor;\n"
            + "const float MAX_UV = 0.20;\n"
            + "vec2 decodeFlow(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n"
            + "vec3 rgbToYcocg(vec3 c) {\n"
            + "  float y  =  0.25 * c.r + 0.50 * c.g + 0.25 * c.b;\n"
            + "  float co =  0.50 * c.r - 0.50 * c.b;\n"
            + "  float cg = -0.25 * c.r + 0.50 * c.g - 0.25 * c.b;\n"
            + "  return vec3(y, co, cg);\n"
            + "}\n"
            + "vec3 ycocgToRgb(vec3 ycc) {\n"
            + "  float tmp = ycc.x - ycc.z;\n"
            + "  return vec3(tmp + ycc.y, ycc.x + ycc.z, tmp - ycc.y);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec3 c = texture(uCurrTex, vUv).rgb;\n"
            + "  if (uBlendMax <= 0.001) { fragColor = vec4(c, 1.0); return; }\n"
            + "  vec4 f = texture(uFlowTex, vUv);\n"
            + "  vec2 v = decodeFlow(f.rg);\n"
            + "  vec2 prevUv = vUv + v;\n"
            + "  if (any(lessThan(prevUv, vec2(0.001))) || any(greaterThan(prevUv, vec2(0.999)))) {\n"
            + "    fragColor = vec4(c, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec3 m1 = vec3(0.0);\n"
            + "  vec3 m2 = vec3(0.0);\n"
            + "  vec3 mn = vec3(1e6);\n"
            + "  vec3 mx = vec3(-1e6);\n"
            + "  for (int dy = -1; dy <= 1; dy++) {\n"
            + "    for (int dx = -1; dx <= 1; dx++) {\n"
            + "      vec3 s = rgbToYcocg(texture(uCurrTex, vUv + vec2(float(dx), float(dy)) * uTexelSize).rgb);\n"
            + "      m1 += s;\n"
            + "      m2 += s * s;\n"
            + "      mn = min(mn, s);\n"
            + "      mx = max(mx, s);\n"
            + "    }\n"
            + "  }\n"
            + "  vec3 mu = m1 * (1.0 / 9.0);\n"
            + "  vec3 sigma = sqrt(max(m2 * (1.0 / 9.0) - mu * mu, vec3(0.0)));\n"
            + "  vec3 boxMin = max(mn, mu - 1.25 * sigma);\n"
            + "  vec3 boxMax = min(mx, mu + 1.25 * sigma);\n"
            + "  vec3 histYcc = clamp(rgbToYcocg(texture(uHistoryTex, prevUv).rgb), boxMin, boxMax);\n"
            + "  vec3 histRgb = clamp(ycocgToRgb(histYcc), 0.0, 1.0);\n"
            + "  float w = clamp(uBlendMax * f.a, 0.0, 0.22);\n"
            + "  fragColor = vec4(mix(c, histRgb, w), 1.0);\n"
            + "}\n";

    // Qualcomm SGSR1 12-tap fastLanczos2 spatial upscaler + dither-aware AMD FidelityFX RCAS + looks
    static final String FRAG_RCAS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uInputTex;\n"
            + "uniform vec2 uTexelSize;\n"
            + "uniform float uSharpness;\n"
            + "uniform float uSuperRes;\n"
            + "uniform float uLookMode;\n"
            + "out vec4 fragColor;\n"
            + "float lum(vec3 c) { return c.g + 0.5 * (c.r + c.b); }\n"
            + "float fastLanczos2(float x) {\n"
            + "  float wA = x - 4.0;\n"
            + "  float wB = x * wA - wA;\n"
            + "  wA *= wA;\n"
            + "  return wB * wA;\n"
            + "}\n"
            + "vec3 sgsr1Sample(vec2 uv, vec2 texel, vec3 b, vec3 d, vec3 e, vec3 f, vec3 h) {\n"
            + "  vec2 pos = uv / texel - 0.5;\n"
            + "  vec2 fp = floor(pos);\n"
            + "  vec2 sub = pos - fp;\n"
            + "  vec2 baseUv = (fp + 0.5) * texel;\n"
            + "  vec3 c00 = texture(uInputTex, baseUv).rgb;\n"
            + "  vec3 c10 = texture(uInputTex, baseUv + vec2(texel.x, 0.0)).rgb;\n"
            + "  vec3 c01 = texture(uInputTex, baseUv + vec2(0.0, texel.y)).rgb;\n"
            + "  vec3 c11 = texture(uInputTex, baseUv + texel).rgb;\n"
            + "  vec3 c0M = texture(uInputTex, baseUv + vec2(0.0, -texel.y)).rgb;\n"
            + "  vec3 c1M = texture(uInputTex, baseUv + vec2(texel.x, -texel.y)).rgb;\n"
            + "  vec3 cM0 = texture(uInputTex, baseUv + vec2(-texel.x, 0.0)).rgb;\n"
            + "  vec3 c20 = texture(uInputTex, baseUv + vec2(2.0 * texel.x, 0.0)).rgb;\n"
            + "  vec3 cM1 = texture(uInputTex, baseUv + vec2(-texel.x, texel.y)).rgb;\n"
            + "  vec3 c21 = texture(uInputTex, baseUv + vec2(2.0 * texel.x, texel.y)).rgb;\n"
            + "  vec3 c02 = texture(uInputTex, baseUv + vec2(0.0, 2.0 * texel.y)).rgb;\n"
            + "  vec3 c12 = texture(uInputTex, baseUv + vec2(texel.x, 2.0 * texel.y)).rgb;\n"
            + "  float l00 = lum(c00), l10 = lum(c10), l01 = lum(c01), l11 = lum(c11);\n"
            + "  float gx = (l10 - l00) * (1.0 - sub.y) + (l11 - l01) * sub.y;\n"
            + "  float gy = (l01 - l00) * (1.0 - sub.x) + (l11 - l10) * sub.x;\n"
            + "  float edge = sqrt(gx * gx + gy * gy);\n"
            + "  float edgeWeight = clamp((edge - (4.0 / 255.0)) * 2.0, 0.0, 1.0);\n"
            + "  vec2 dir = edge > 1e-4 ? vec2(gx, gy) / edge : vec2(1.0, 0.0);\n"
            + "  vec2 scale = vec2(1.0 + 0.65 * edgeWeight, 1.0 - 0.35 * edgeWeight);\n"
            + "  vec3 acc = vec3(0.0);\n"
            + "  float wSum = 0.0;\n"
            + "  vec2 offs[12]; vec3 cols[12];\n"
            + "  offs[0] = vec2(0.0, -1.0); cols[0] = c0M;\n"
            + "  offs[1] = vec2(1.0, -1.0); cols[1] = c1M;\n"
            + "  offs[2] = vec2(-1.0, 0.0); cols[2] = cM0;\n"
            + "  offs[3] = vec2(0.0, 0.0);  cols[3] = c00;\n"
            + "  offs[4] = vec2(1.0, 0.0);  cols[4] = c10;\n"
            + "  offs[5] = vec2(2.0, 0.0);  cols[5] = c20;\n"
            + "  offs[6] = vec2(-1.0, 1.0); cols[6] = cM1;\n"
            + "  offs[7] = vec2(0.0, 1.0);  cols[7] = c01;\n"
            + "  offs[8] = vec2(1.0, 1.0);  cols[8] = c11;\n"
            + "  offs[9] = vec2(2.0, 1.0);  cols[9] = c21;\n"
            + "  offs[10] = vec2(0.0, 2.0); cols[10] = c02;\n"
            + "  offs[11] = vec2(1.0, 2.0); cols[11] = c12;\n"
            + "  for (int i = 0; i < 12; i++) {\n"
            + "    vec2 dUv = offs[i] - sub;\n"
            + "    float pX = dot(dUv, dir) * scale.x;\n"
            + "    float pY = dot(dUv, vec2(-dir.y, dir.x)) * scale.y;\n"
            + "    float d2 = min(pX * pX + pY * pY, 4.0);\n"
            + "    float w = fastLanczos2(d2);\n"
            + "    acc += cols[i] * w;\n"
            + "    wSum += w;\n"
            + "  }\n"
            + "  vec3 min4 = min(min(c00, c10), min(c01, c11));\n"
            + "  vec3 max4 = max(max(c00, c10), max(c01, c11));\n"
            + "  return clamp(acc / max(wSum, 1e-5), min(e, min4), max(e, max4));\n"
            + "}\n"
            + "void main() {\n"
            + "  vec3 b = texture(uInputTex, vUv - vec2(0.0, uTexelSize.y)).rgb;\n"
            + "  vec3 d = texture(uInputTex, vUv - vec2(uTexelSize.x, 0.0)).rgb;\n"
            + "  vec3 e = texture(uInputTex, vUv).rgb;\n"
            + "  vec3 f = texture(uInputTex, vUv + vec2(uTexelSize.x, 0.0)).rgb;\n"
            + "  vec3 h = texture(uInputTex, vUv + vec2(0.0, uTexelSize.y)).rgb;\n"
            + "  if (uSuperRes > 0.5) {\n"
            + "    e = sgsr1Sample(vUv, uTexelSize, b, d, e, f, h);\n"
            + "  }\n"
            + "  float bL = lum(b);\n"
            + "  float dL = lum(d);\n"
            + "  float eL = lum(e);\n"
            + "  float fL = lum(f);\n"
            + "  float hL = lum(h);\n"
            + "  float nz = 0.25 * (bL + dL + fL + hL) - eL;\n"
            + "  float range = max(max(bL, dL), max(fL, max(hL, eL))) - min(min(bL, dL), min(fL, min(hL, eL)));\n"
            + "  float debandW = smoothstep(0.024, 0.006, range) * 0.45;\n"
            + "  if (debandW > 0.001) {\n"
            + "    vec3 avg4 = 0.25 * (b + d + f + h);\n"
            + "    float ig = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n"
            + "    e = mix(e, avg4, debandW) + (ig - 0.5) / 255.0;\n"
            + "    eL = lum(e);\n"
            + "  }\n"
            + "  float nzClamp = range > 1e-4 ? clamp(abs(nz) / range, 0.0, 1.0) : 0.0;\n"
            + "  float stipple = smoothstep(0.45, 0.85, nzClamp);\n"
            + "  vec3 mn = min(min(min(b, d), min(f, h)), e);\n"
            + "  vec3 mx = max(max(max(b, d), max(f, h)), e);\n"
            + "  vec3 hitMin = mn / max(4.0 * mx, vec3(1e-5));\n"
            + "  vec3 hitMax = (1.0 - mx) / min(4.0 * mn - 4.0, vec3(-1e-5));\n"
            + "  vec3 lobeRGB = max(-hitMin, hitMax);\n"
            + "  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0));\n"
            + "  lobe *= exp2(-uSharpness) * (1.0 - 0.5 * nzClamp) * (1.0 - stipple);\n"
            + "  vec3 pix = clamp((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 0.0, 1.0);\n"
            + "  float deStipple = stipple * smoothstep(0.03, 0.14, range) * 0.32;\n"
            + "  pix = mix(pix, 0.25 * (b + d + f + h), deStipple);\n"
            + "  if (uLookMode > 1.5) {\n"
            + "    float y0 = dot(pix, vec3(0.2126, 0.7152, 0.0722));\n"
            + "    pix = pow(max(pix, vec3(0.0)), vec3(0.88)) + vec3(0.015 * (1.0 - y0));\n"
            + "    float y1 = dot(pix, vec3(0.2126, 0.7152, 0.0722));\n"
            + "    pix = mix(vec3(y1), pix, 1.08);\n"
            + "  } else if (uLookMode > 0.5) {\n"
            + "    pix = mix(pix, pix * pix * (3.0 - 2.0 * pix), 0.14);\n"
            + "    float y1 = dot(pix, vec3(0.2126, 0.7152, 0.0722));\n"
            + "    pix = mix(vec3(y1), pix, 1.12);\n"
            + "  }\n"
            + "  fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);\n"
            + "}\n";
}

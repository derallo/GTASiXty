package com.gtasixty.spatial;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * On-Quest Adreno 740 OpenGL ES 3.0 post-processing pipeline for Capture mode.
 *
 * Ports Direct mode's WebGL2 pipeline (2-level 80x45 -> 320x180 coarse-to-fine optical flow,
 * disocclusion-safe midpoint warp triggered by experimental kFlagDuplicate, EASU Super-Resolution,
 * 1-pixel AMD FidelityFX RCAS sharpening, and Normal/Vivid/Night color grading) to native EGL/GLES3.
 */
final class CaptureGlRenderer {

    private static final String TAG = "GTA60-GL";

    static final int MODE_OFF = 0;
    static final int MODE_RCAS = 1;
    static final int MODE_SUPERRES = 2;
    static final int MODE_FG60 = 3;
    static final int MODE_FG60_SR = 4;

    static final int LOOK_NORMAL = 0;
    static final int LOOK_VIVID = 1;
    static final int LOOK_NIGHT = 2;

    private static final int FLOW_LO_W = 80;
    private static final int FLOW_LO_H = 45;
    private static final int FLOW_HI_W = 320;
    private static final int FLOW_HI_H = 180;
    private static final float RCAS_SHARPNESS = 0.10f;

    interface FrameListener {
        void onFrameRendered(boolean generated);
    }

    private final Surface outputSurface;
    private final int width;
    private final int height;
    private final FrameListener listener;

    private volatile int enhanceMode;
    private volatile int lookMode;
    private volatile boolean running = true;

    private final Object frameLock = new Object();
    private final ArrayDeque<Boolean> dupQueue = new ArrayDeque<>();
    private int availableFrames = 0;
    private boolean resetHistoryRequested = false;

    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean initSuccess = false;
    private volatile SurfaceTexture surfaceTexture;
    private volatile Surface decoderSurface;
    private Thread glThread;

    // EGL state (owned by glThread)
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    // GL geometry & shaders (owned by glThread)
    private int vaoId = 0;
    private int vboId = 0;
    private int oesTexId = 0;

    private int progOesCopy = 0;
    private int uOesCopyTex = -1;
    private int uOesCopyStMatrix = -1;

    private int progLuma = 0;
    private int uLumaTex = -1;

    private int progDownsample = 0;
    private int uDownsampleHi = -1;

    private int progFlowCoarse = 0;
    private int uCoarsePrev = -1;
    private int uCoarseCurr = -1;

    private int progFlowFine = 0;
    private int uFinePrev = -1;
    private int uFineCurr = -1;
    private int uFineCoarse = -1;

    private int progFlowSmooth = 0;
    private int uSmoothRaw = -1;
    private int uSmoothLuma = -1;
    private int uSmoothPrevFlow = -1;

    private int progWarp = 0;
    private int uWarpPrev = -1;
    private int uWarpCurr = -1;
    private int uWarpFlow = -1;

    private int progRcas = 0;
    private int uRcasInput = -1;
    private int uRcasTexelSize = -1;
    private int uRcasSharpness = -1;
    private int uRcasSuperRes = -1;
    private int uRcasLookMode = -1;

    // Ping-pong textures & FBOs
    private int texPrev = 0, fboPrev = 0;
    private int texCurr = 0, fboCurr = 0;
    private int texMid = 0, fboMid = 0;
    private int texLumaHiPrev = 0, fboLumaHiPrev = 0;
    private int texLumaHiCurr = 0, fboLumaHiCurr = 0;
    private int texLumaLoPrev = 0, fboLumaLoPrev = 0;
    private int texLumaLoCurr = 0, fboLumaLoCurr = 0;
    private int texFlowCoarse = 0, fboFlowCoarse = 0;
    private int texFlowFine = 0, fboFlowFine = 0;
    private int texFlowSmooth = 0, fboFlowSmooth = 0;
    private int texFlowPrev = 0, fboFlowPrev = 0;

    private int uniqueCount = 0;
    private int consecDups = 0;
    private int dupGateFrames = 0;
    private boolean realFramePending = false;
    private final float[] stMatrix = new float[16];
    private final int[] eglDim = new int[2];

    CaptureGlRenderer(Surface outputSurface, int width, int height,
                      int enhanceMode, int lookMode, FrameListener listener) {
        this.outputSurface = outputSurface;
        this.width = width;
        this.height = height;
        this.enhanceMode = enhanceMode;
        this.lookMode = lookMode;
        this.listener = listener;
        start();
    }

    private void start() {
        glThread = new Thread(this::runGlLoop, "GTA60-CaptureGL");
        glThread.start();
    }

    Surface getDecoderSurface() {
        try {
            readyLatch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return initSuccess ? decoderSurface : null;
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

    void onDecodedFrame(boolean isDuplicate) {
        synchronized (frameLock) {
            dupQueue.addLast(isDuplicate);
            frameLock.notifyAll();
        }
    }

    void release() {
        running = false;
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

    private void runGlLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) {}

        if (!initEgl() || !initGlResources()) {
            readyLatch.countDown();
            cleanupGl();
            cleanupEgl();
            return;
        }

        initSuccess = true;
        readyLatch.countDown();
        Log.i(TAG, String.format("CaptureGlRenderer initialized %dx%d mode=%d look=%d",
                width, height, enhanceMode, lookMode));

        while (running) {
            boolean isDup;
            synchronized (frameLock) {
                while (running && (availableFrames <= 0 || dupQueue.isEmpty())) {
                    try {
                        frameLock.wait(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                        break;
                    }
                }
                if (!running) break;
                if (resetHistoryRequested) {
                    resetHistoryRequested = false;
                    uniqueCount = 0;
                    consecDups = 0;
                    dupGateFrames = 0;
                    realFramePending = false;
                }
                availableFrames--;
                isDup = dupQueue.pollFirst();
            }

            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
                // Drain any backed-up SurfaceTexture frames so we always render the freshest image.
                synchronized (frameLock) {
                    while (availableFrames > 0 && !dupQueue.isEmpty()) {
                        availableFrames--;
                        isDup = dupQueue.pollFirst();
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(stMatrix);
                    }
                }
                renderFrame(isDup);
            } catch (Exception e) {
                Log.w(TAG, "renderFrame error", e);
            }
        }

        cleanupGl();
        cleanupEgl();
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
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            Log.e(TAG, "eglChooseConfig failed");
            return false;
        }

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
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

    private boolean initGlResources() {
        // Fullscreen quad VAO + VBO
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

        // External OES texture + SurfaceTexture
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

        // Compile programs
        progOesCopy = linkProgram(VERT_FBO, FRAG_OES_COPY);
        progLuma = linkProgram(VERT_FBO, FRAG_LUMA);
        progDownsample = linkProgram(VERT_FBO, FRAG_DOWNSAMPLE);
        progFlowCoarse = linkProgram(VERT_FBO, FRAG_FLOW_COARSE);
        progFlowFine = linkProgram(VERT_FBO, FRAG_FLOW_FINE);
        progFlowSmooth = linkProgram(VERT_FBO, FRAG_FLOW_SMOOTH);
        progWarp = linkProgram(VERT_FBO, FRAG_WARP);
        progRcas = linkProgram(VERT_FBO, FRAG_RCAS);

        if (progOesCopy == 0 || progLuma == 0 || progDownsample == 0 || progFlowCoarse == 0
                || progFlowFine == 0 || progFlowSmooth == 0 || progWarp == 0 || progRcas == 0) {
            return false;
        }

        uOesCopyTex = GLES30.glGetUniformLocation(progOesCopy, "uOesTex");
        uOesCopyStMatrix = GLES30.glGetUniformLocation(progOesCopy, "uStMatrix");

        uLumaTex = GLES30.glGetUniformLocation(progLuma, "uVideoTex");
        uDownsampleHi = GLES30.glGetUniformLocation(progDownsample, "uHiLumaTex");

        uCoarsePrev = GLES30.glGetUniformLocation(progFlowCoarse, "uPrevLumaLo");
        uCoarseCurr = GLES30.glGetUniformLocation(progFlowCoarse, "uCurrLumaLo");

        uFinePrev = GLES30.glGetUniformLocation(progFlowFine, "uPrevLumaHi");
        uFineCurr = GLES30.glGetUniformLocation(progFlowFine, "uCurrLumaHi");
        uFineCoarse = GLES30.glGetUniformLocation(progFlowFine, "uCoarseFlow");

        uSmoothRaw = GLES30.glGetUniformLocation(progFlowSmooth, "uRawFlowTex");
        uSmoothLuma = GLES30.glGetUniformLocation(progFlowSmooth, "uCurrLuma");
        uSmoothPrevFlow = GLES30.glGetUniformLocation(progFlowSmooth, "uPrevFlowTex");

        uWarpPrev = GLES30.glGetUniformLocation(progWarp, "uPrevTex");
        uWarpCurr = GLES30.glGetUniformLocation(progWarp, "uCurrTex");
        uWarpFlow = GLES30.glGetUniformLocation(progWarp, "uFlowTex");

        uRcasInput = GLES30.glGetUniformLocation(progRcas, "uInputTex");
        uRcasTexelSize = GLES30.glGetUniformLocation(progRcas, "uTexelSize");
        uRcasSharpness = GLES30.glGetUniformLocation(progRcas, "uSharpness");
        uRcasSuperRes = GLES30.glGetUniformLocation(progRcas, "uSuperRes");
        uRcasLookMode = GLES30.glGetUniformLocation(progRcas, "uLookMode");

        // Allocate textures + FBOs
        int[] pair = new int[2];
        createTexFbo(width, height, pair); texPrev = pair[0]; fboPrev = pair[1];
        createTexFbo(width, height, pair); texCurr = pair[0]; fboCurr = pair[1];
        createTexFbo(width, height, pair); texMid = pair[0]; fboMid = pair[1];

        createTexFbo(FLOW_HI_W, FLOW_HI_H, pair); texLumaHiPrev = pair[0]; fboLumaHiPrev = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, pair); texLumaHiCurr = pair[0]; fboLumaHiCurr = pair[1];
        createTexFbo(FLOW_LO_W, FLOW_LO_H, pair); texLumaLoPrev = pair[0]; fboLumaLoPrev = pair[1];
        createTexFbo(FLOW_LO_W, FLOW_LO_H, pair); texLumaLoCurr = pair[0]; fboLumaLoCurr = pair[1];

        createTexFbo(FLOW_LO_W, FLOW_LO_H, pair); texFlowCoarse = pair[0]; fboFlowCoarse = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, pair); texFlowFine = pair[0]; fboFlowFine = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, pair); texFlowSmooth = pair[0]; fboFlowSmooth = pair[1];
        createTexFbo(FLOW_HI_W, FLOW_HI_H, pair); texFlowPrev = pair[0]; fboFlowPrev = pair[1];

        return true;
    }

    private void renderFrame(boolean isDup) {
        final int mode = enhanceMode;
        final int look = lookMode;

        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, eglDim, 0);
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, eglDim, 1);
        final int surfW = eglDim[0] > 0 ? eglDim[0] : width;
        final int surfH = eglDim[1] > 0 ? eglDim[1] : height;

        GLES30.glBindVertexArray(vaoId);

        if (mode == MODE_OFF) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, surfW, surfH);
            GLES30.glUseProgram(progOesCopy);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            GLES30.glUniform1i(uOesCopyTex, 0);
            GLES30.glUniformMatrix4fv(uOesCopyStMatrix, 1, false, stMatrix, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            if (listener != null) listener.onFrameRendered(false);
            return;
        }

        if (mode == MODE_RCAS || mode == MODE_SUPERRES) {
            copyOesToFbo(fboCurr);
            renderRcasToScreen(texCurr, surfW, surfH, mode == MODE_SUPERRES, look);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            if (listener != null) listener.onFrameRendered(false);
            return;
        }

        // FG60 or FG60+SR (strict monotonic presentation: Mid(U0, U1) on unique arrival, U1 on duplicate slot)
        final boolean superRes = (mode == MODE_FG60_SR);
        boolean generated = false;
        int displayTex = texCurr;

        if (!isDup || uniqueCount == 0) {
            // Unique frame: rotate prev/curr color + luma FBOs
            int tmpT = texPrev; texPrev = texCurr; texCurr = tmpT;
            int tmpF = fboPrev; fboPrev = fboCurr; fboCurr = tmpF;

            tmpT = texLumaHiPrev; texLumaHiPrev = texLumaHiCurr; texLumaHiCurr = tmpT;
            tmpF = fboLumaHiPrev; fboLumaHiPrev = fboLumaHiCurr; fboLumaHiCurr = tmpF;

            tmpT = texLumaLoPrev; texLumaLoPrev = texLumaLoCurr; texLumaLoCurr = tmpT;
            tmpF = fboLumaLoPrev; fboLumaLoPrev = fboLumaLoCurr; fboLumaLoCurr = tmpF;

            copyOesToFbo(fboCurr);

            // Luma hi (320x180)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboLumaHiCurr);
            GLES30.glViewport(0, 0, FLOW_HI_W, FLOW_HI_H);
            GLES30.glUseProgram(progLuma);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurr);
            GLES30.glUniform1i(uLumaTex, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

            // Luma lo (80x45)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboLumaLoCurr);
            GLES30.glViewport(0, 0, FLOW_LO_W, FLOW_LO_H);
            GLES30.glUseProgram(progDownsample);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaHiCurr);
            GLES30.glUniform1i(uDownsampleHi, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

            uniqueCount++;
            consecDups = 0;

            if (dupGateFrames > 0 && uniqueCount >= 2) {
                dupGateFrames--;

                // Coarse optical flow (80x45)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFlowCoarse);
                GLES30.glViewport(0, 0, FLOW_LO_W, FLOW_LO_H);
                GLES30.glUseProgram(progFlowCoarse);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoPrev);
                GLES30.glUniform1i(uCoarsePrev, 0);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLumaLoCurr);
                GLES30.glUniform1i(uCoarseCurr, 1);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

                // Fine optical flow (320x180)
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

                // Bilateral + temporal smooth (320x180)
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
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

                // Midpoint warp Mid(U0, U1) -> fboMid (t = 0.5)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboMid);
                GLES30.glViewport(0, 0, width, height);
                GLES30.glUseProgram(progWarp);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texPrev);
                GLES30.glUniform1i(uWarpPrev, 0);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurr);
                GLES30.glUniform1i(uWarpCurr, 1);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFlowSmooth);
                GLES30.glUniform1i(uWarpFlow, 2);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

                tmpT = texFlowPrev; texFlowPrev = texFlowSmooth; texFlowSmooth = tmpT;
                tmpF = fboFlowPrev; fboFlowPrev = fboFlowSmooth; fboFlowSmooth = tmpF;

                displayTex = texMid;
                realFramePending = true;
                generated = true;
            } else {
                if (dupGateFrames > 0) dupGateFrames--;
                displayTex = texCurr;
                realFramePending = false;
            }
        } else {
            // Duplicate HDMI frame: present the pending real frame U1 (t = 1.0) or hold U1 cleanly
            dupGateFrames = 12;
            consecDups++;
            displayTex = texCurr;
            realFramePending = false;
        }

        renderRcasToScreen(displayTex, surfW, surfH, superRes, look);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        if (listener != null) listener.onFrameRendered(generated);
    }

    private void copyOesToFbo(int targetFbo) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo);
        GLES30.glViewport(0, 0, width, height);
        GLES30.glUseProgram(progOesCopy);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES30.glUniform1i(uOesCopyTex, 0);
        GLES30.glUniformMatrix4fv(uOesCopyStMatrix, 1, false, stMatrix, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void renderRcasToScreen(int inputTex, int surfW, int surfH, boolean superRes, int look) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, surfW, surfH);
        GLES30.glUseProgram(progRcas);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTex);
        GLES30.glUniform1i(uRcasInput, 0);
        GLES30.glUniform2f(uRcasTexelSize, 1.0f / width, 1.0f / height);
        GLES30.glUniform1f(uRcasSharpness, RCAS_SHARPNESS);
        GLES30.glUniform1f(uRcasSuperRes, superRes ? 1.0f : 0.0f);
        GLES30.glUniform1f(uRcasLookMode, (float) look);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void createTexFbo(int w, int h, int[] outTexFbo) {
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        int tex = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);

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

    private void cleanupGl() {
        if (decoderSurface != null) {
            try { decoderSurface.release(); } catch (Exception ignored) {}
            decoderSurface = null;
        }
        if (surfaceTexture != null) {
            try { surfaceTexture.release(); } catch (Exception ignored) {}
            surfaceTexture = null;
        }
        if (eglContext == EGL14.EGL_NO_CONTEXT) return;

        int[] progs = {progOesCopy, progLuma, progDownsample, progFlowCoarse,
                progFlowFine, progFlowSmooth, progWarp, progRcas};
        for (int p : progs) {
            if (p != 0) GLES30.glDeleteProgram(p);
        }

        int[] fbos = {fboPrev, fboCurr, fboMid, fboLumaHiPrev, fboLumaHiCurr,
                fboLumaLoPrev, fboLumaLoCurr, fboFlowCoarse, fboFlowFine,
                fboFlowSmooth, fboFlowPrev};
        GLES30.glDeleteFramebuffers(fbos.length, fbos, 0);

        int[] texs = {oesTexId, texPrev, texCurr, texMid, texLumaHiPrev, texLumaHiCurr,
                texLumaLoPrev, texLumaLoCurr, texFlowCoarse, texFlowFine,
                texFlowSmooth, texFlowPrev};
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

    private static final String VERT_FBO = "#version 300 es\n"
            + "layout(location=0) in vec2 aPos;\n"
            + "out vec2 vUv;\n"
            + "void main() {\n"
            + "  vUv = aPos * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAG_OES_COPY = "#version 300 es\n"
            + "#extension GL_OES_EGL_image_external_essl3 : require\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform samplerExternalOES uOesTex;\n"
            + "uniform mat4 uStMatrix;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  vec2 uv = (uStMatrix * vec4(vUv, 0.0, 1.0)).xy;\n"
            + "  fragColor = vec4(texture(uOesTex, uv).rgb, 1.0);\n"
            + "}\n";

    private static final String FRAG_LUMA = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uVideoTex;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  vec3 c = texture(uVideoTex, vUv).rgb;\n"
            + "  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));\n"
            + "  fragColor = vec4(y, 0.0, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAG_DOWNSAMPLE = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uHiLumaTex;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  vec2 d = vec2(1.0 / 320.0, 1.0 / 180.0);\n"
            + "  float y = 0.25 * (\n"
            + "    texture(uHiLumaTex, vUv + vec2(-d.x, -d.y)).r +\n"
            + "    texture(uHiLumaTex, vUv + vec2( d.x, -d.y)).r +\n"
            + "    texture(uHiLumaTex, vUv + vec2(-d.x,  d.y)).r +\n"
            + "    texture(uHiLumaTex, vUv + vec2( d.x,  d.y)).r);\n"
            + "  fragColor = vec4(y, 0.0, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAG_FLOW_COARSE = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uPrevLumaLo;\n"
            + "uniform sampler2D uCurrLumaLo;\n"
            + "out vec4 fragColor;\n"
            + "const vec2 STEP = vec2(1.0 / 80.0, 1.0 / 45.0);\n"
            + "const float MAX_UV = 0.08;\n"
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
            + "  if (bestSad > 0.008) {\n"
            + "    for (int dy = -3; dy <= 3; dy++) {\n"
            + "      for (int dx = -5; dx <= 5; dx++) {\n"
            + "        if (dx == 0 && dy == 0) continue;\n"
            + "        vec2 off = vec2(float(dx), float(dy)) * STEP;\n"
            + "        float penalty = 0.0012 * length(vec2(float(dx), float(dy)));\n"
            + "        float s = patchSad(vUv, vUv + off) + penalty;\n"
            + "        if (s < bestSad) { bestSad = s; bestOff = off; }\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  fragColor = vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private static final String FRAG_FLOW_FINE = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uPrevLumaHi;\n"
            + "uniform sampler2D uCurrLumaHi;\n"
            + "uniform sampler2D uCoarseFlow;\n"
            + "out vec4 fragColor;\n"
            + "const vec2 STEP = vec2(1.0 / 320.0, 1.0 / 180.0);\n"
            + "const float MAX_UV = 0.08;\n"
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
            + "  if (sad0 > 0.008 || length(initOff) > 0.0005) {\n"
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
            + "  float matchConf = clamp(1.0 - max(bestSad, revSad) * 7.5, 0.0, 1.0);\n"
            + "  float conf = matchConf * (sad0 <= 0.008 ? 1.0 : gain) * mix(0.45, 1.0, vecAgree);\n"
            + "  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  fragColor = vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), conf);\n"
            + "}\n";

    private static final String FRAG_FLOW_SMOOTH = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uRawFlowTex;\n"
            + "uniform sampler2D uCurrLuma;\n"
            + "uniform sampler2D uPrevFlowTex;\n"
            + "out vec4 fragColor;\n"
            + "const vec2 STEP = vec2(1.0 / 320.0, 1.0 / 180.0);\n"
            + "const float MAX_UV = 0.08;\n"
            + "vec2 dec(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n"
            + "void main() {\n"
            + "  float cLuma = texture(uCurrLuma, vUv).r;\n"
            + "  vec4 cFlow = texture(uRawFlowTex, vUv);\n"
            + "  vec2 cVec = dec(cFlow.rg);\n"
            + "  vec2 sumVec = vec2(0.0);\n"
            + "  float sumConf = 0.0;\n"
            + "  float sumW = 0.0;\n"
            + "  float maxDiff = 0.0;\n"
            + "  for (int dy = -1; dy <= 1; dy++) {\n"
            + "    for (int dx = -1; dx <= 1; dx++) {\n"
            + "      vec2 uv = vUv + vec2(float(dx), float(dy)) * STEP;\n"
            + "      vec4 nf = texture(uRawFlowTex, uv);\n"
            + "      float nl = texture(uCurrLuma, uv).r;\n"
            + "      vec2 nv = dec(nf.rg);\n"
            + "      float wSpatial = (dx == 0 && dy == 0) ? 2.0 : ((dx == 0 || dy == 0) ? 1.0 : 0.6);\n"
            + "      float wLuma = exp(-abs(nl - cLuma) * 10.0);\n"
            + "      float w = wSpatial * wLuma * (0.15 + nf.a);\n"
            + "      sumVec += nv * w;\n"
            + "      sumConf += nf.a * w;\n"
            + "      sumW += w;\n"
            + "      maxDiff = max(maxDiff, length(nv - cVec) / MAX_UV);\n"
            + "    }\n"
            + "  }\n"
            + "  vec2 avgVec = sumVec / max(sumW, 1e-4);\n"
            + "  vec2 prevVec = dec(texture(uPrevFlowTex, vUv).rg);\n"
            + "  float disagree = clamp(length(avgVec - prevVec) / MAX_UV, 0.0, 1.0);\n"
            + "  float wPrev = (1.0 - disagree) * 0.35;\n"
            + "  vec2 blended = mix(avgVec, prevVec, wPrev);\n"
            + "  float avgConf = (sumConf / max(sumW, 1e-4)) * clamp(1.0 - maxDiff * 1.4, 0.0, 1.0);\n"
            + "  vec2 enc = clamp((blended / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n"
            + "  fragColor = vec4(enc.x, enc.y, cFlow.b, avgConf);\n"
            + "}\n";

    // OpenGL FBO UVs have bottom-left y=0: minimap is y < 0.28, top-right HUD is y > 0.78.
    private static final String FRAG_WARP = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uPrevTex;\n"
            + "uniform sampler2D uCurrTex;\n"
            + "uniform sampler2D uFlowTex;\n"
            + "out vec4 fragColor;\n"
            + "const float MAX_UV = 0.08;\n"
            + "vec2 decodeFlow(vec2 raw) {\n"
            + "  return (raw - (128.0 / 255.0)) * 2.0 * MAX_UV;\n"
            + "}\n"
            + "void main() {\n"
            + "  vec3 c0 = texture(uPrevTex, vUv).rgb;\n"
            + "  vec3 c1 = texture(uCurrTex, vUv).rgb;\n"
            + "  bool inMinimap = (vUv.x < 0.24 && vUv.y < 0.28);\n"
            + "  bool inTopRightHud = (vUv.x > 0.77 && vUv.y > 0.78);\n"
            + "  bool inReticle = (abs(vUv.x - 0.5) < 0.018 && abs(vUv.y - 0.5) < 0.028);\n"
            + "  if (inMinimap || inTopRightHud || inReticle) {\n"
            + "    fragColor = vec4(c1, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  float es = dot(abs(c0 - c1), vec3(0.333333));\n"
            + "  if (es < 0.008) {\n"
            + "    fragColor = vec4(c1, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec4 f = texture(uFlowTex, vUv);\n"
            + "  vec2 v = decodeFlow(f.rg);\n"
            + "  if (length(v) < 0.002 && es < 0.03) {\n"
            + "    fragColor = vec4(c1, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec2 p0 = clamp(vUv + 0.5 * v, vec2(0.001), vec2(0.999));\n"
            + "  vec2 p1 = clamp(vUv - 0.5 * v, vec2(0.001), vec2(0.999));\n"
            + "  vec3 w0 = texture(uPrevTex, p0).rgb;\n"
            + "  vec3 w1 = texture(uCurrTex, p1).rgb;\n"
            + "  float em = dot(abs(w0 - w1), vec3(0.333333));\n"
            + "  vec3 warpedMid = mix(w0, w1, 0.5);\n"
            + "  float fit = smoothstep(0.09, 0.012, em) * smoothstep(0.0, 0.022, es - em);\n"
            + "  float w = clamp(f.a * fit, 0.0, 1.0);\n"
            + "  fragColor = vec4(mix(c1, warpedMid, w), 1.0);\n"
            + "}\n";

    private static final String FRAG_RCAS = "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uInputTex;\n"
            + "uniform vec2 uTexelSize;\n"
            + "uniform float uSharpness;\n"
            + "uniform float uSuperRes;\n"
            + "uniform float uLookMode;\n"
            + "out vec4 fragColor;\n"
            + "float lum(vec3 c) { return c.g + 0.5 * (c.r + c.b); }\n"
            + "void main() {\n"
            + "  vec3 b = texture(uInputTex, vUv - vec2(0.0, uTexelSize.y)).rgb;\n"
            + "  vec3 d = texture(uInputTex, vUv - vec2(uTexelSize.x, 0.0)).rgb;\n"
            + "  vec3 e = texture(uInputTex, vUv).rgb;\n"
            + "  vec3 f = texture(uInputTex, vUv + vec2(uTexelSize.x, 0.0)).rgb;\n"
            + "  vec3 h = texture(uInputTex, vUv + vec2(0.0, uTexelSize.y)).rgb;\n"
            + "  float bL = lum(b);\n"
            + "  float dL = lum(d);\n"
            + "  float eL = lum(e);\n"
            + "  float fL = lum(f);\n"
            + "  float hL = lum(h);\n"
            + "  if (uSuperRes > 0.5) {\n"
            + "    vec3 nw = texture(uInputTex, vUv + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;\n"
            + "    vec3 ne = texture(uInputTex, vUv + vec2( uTexelSize.x, -uTexelSize.y)).rgb;\n"
            + "    vec3 sw = texture(uInputTex, vUv + vec2(-uTexelSize.x,  uTexelSize.y)).rgb;\n"
            + "    vec3 se = texture(uInputTex, vUv + vec2( uTexelSize.x,  uTexelSize.y)).rgb;\n"
            + "    float gx = (lum(ne) + 2.0 * fL + lum(se)) - (lum(nw) + 2.0 * dL + lum(sw));\n"
            + "    float gy = (lum(sw) + 2.0 * hL + lum(se)) - (lum(nw) + 2.0 * bL + lum(ne));\n"
            + "    vec2 dir = vec2(-gy, gx);\n"
            + "    float dirLen = length(dir);\n"
            + "    if (dirLen > 1e-4) {\n"
            + "      vec2 tDir = (dir / dirLen) * uTexelSize;\n"
            + "      vec3 e0 = texture(uInputTex, vUv - tDir * 0.5).rgb;\n"
            + "      vec3 e1 = texture(uInputTex, vUv + tDir * 0.5).rgb;\n"
            + "      vec3 e2 = texture(uInputTex, vUv - tDir * 1.15).rgb;\n"
            + "      vec3 e3 = texture(uInputTex, vUv + tDir * 1.15).rgb;\n"
            + "      vec3 easu = (e * 0.50 + 0.32 * (e0 + e1) - 0.07 * (e2 + e3));\n"
            + "      vec3 min4 = min(min(b, d), min(f, h));\n"
            + "      vec3 max4 = max(max(b, d), max(f, h));\n"
            + "      easu = clamp(easu, min(e, min4), max(e, max4));\n"
            + "      e = mix(e, easu, clamp(dirLen * 1.8, 0.0, 0.65));\n"
            + "      eL = lum(e);\n"
            + "    }\n"
            + "  }\n"
            + "  float nz = 0.25 * (bL + dL + fL + hL) - eL;\n"
            + "  float range = max(max(bL, dL), max(fL, max(hL, eL))) - min(min(bL, dL), min(fL, min(hL, eL)));\n"
            + "  float nzClamp = range > 1e-4 ? clamp(abs(nz) / range, 0.0, 1.0) : 0.0;\n"
            + "  vec3 mn = min(min(min(b, d), min(f, h)), e);\n"
            + "  vec3 mx = max(max(max(b, d), max(f, h)), e);\n"
            + "  vec3 hitMin = mn / max(4.0 * mx, vec3(1e-5));\n"
            + "  vec3 hitMax = (1.0 - mx) / min(4.0 * mn - 4.0, vec3(-1e-5));\n"
            + "  vec3 lobeRGB = max(-hitMin, hitMax);\n"
            + "  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0));\n"
            + "  lobe *= exp2(-uSharpness) * (1.0 - 0.5 * nzClamp);\n"
            + "  vec3 pix = clamp((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 0.0, 1.0);\n"
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

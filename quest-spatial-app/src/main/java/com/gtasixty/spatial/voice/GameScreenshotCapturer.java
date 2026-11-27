package com.gtasixty.spatial.voice;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-invasive asynchronous screenshot capturer for the active game panel.
 * Supports Capture (LAN/USB), PC Relay, PlayStation Remote Play (SurfaceView via PixelCopy),
 * and Xbox Wi-Fi Direct (Activity Window / WebView fallback), downscaled on-GPU to 768x432 JPEG.
 */
final class GameScreenshotCapturer {

    private static final String TAG = "GTA60-JimmyShot";
    private static final int OUT_W = 512;
    private static final int OUT_H = 288;
    private static final int JPEG_QUALITY = 60;
    private static final long PIXEL_COPY_TIMEOUT_MS = 500L;

    private final Activity activity;
    private final ViewGroup root;
    private final SurfaceView surfaceView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private HandlerThread copyThread;
    private Handler copyHandler;

    GameScreenshotCapturer(Activity activity, ViewGroup root, SurfaceView surfaceView) {
        this.activity = activity;
        this.root = root;
        this.surfaceView = surfaceView;
        this.copyThread = new HandlerThread("Jimmy-PixelCopy");
        this.copyThread.start();
        this.copyHandler = new Handler(this.copyThread.getLooper());
    }

    byte[] captureJpegBytes() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;

        // Path 1: Hardware-scaled PixelCopy directly from the game SurfaceView
        if (isSurfaceViewActive()) {
            final byte[] svBytes = copyFromSurfaceView();
            if (svBytes != null && svBytes.length > 0) return svBytes;
        }

        // Path 2: Hardware-scaled PixelCopy from the Activity window (covers Xbox Wi-Fi WebView)
        final byte[] winBytes = copyFromWindow();
        if (winBytes != null && winBytes.length > 0) return winBytes;

        // Path 3: Software WebView draw fallback
        return drawWebViewFallback();
    }

    void destroy() {
        if (copyThread != null) {
            copyThread.quitSafely();
            copyThread = null;
            copyHandler = null;
        }
    }

    private boolean isSurfaceViewActive() {
        if (surfaceView == null) return false;
        if (surfaceView.getVisibility() != View.VISIBLE) return false;
        if (surfaceView.getWidth() <= 0 || surfaceView.getHeight() <= 0) return false;
        final Surface s = surfaceView.getHolder() != null ? surfaceView.getHolder().getSurface() : null;
        return s != null && s.isValid();
    }

    private byte[] copyFromSurfaceView() {
        final Handler h = copyHandler;
        if (h == null) return null;
        final Bitmap bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger resultCode = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        try {
            PixelCopy.request(surfaceView, bmp, copyResult -> {
                resultCode.set(copyResult);
                latch.countDown();
            }, h);
            if (!latch.await(PIXEL_COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "SurfaceView PixelCopy timed out");
                return null;
            }
            if (resultCode.get() == PixelCopy.SUCCESS) {
                return compressAndRecycle(bmp);
            }
            Log.d(TAG, "SurfaceView PixelCopy status=" + resultCode.get());
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "SurfaceView PixelCopy failed: " + t.getMessage());
            return null;
        } finally {
            if (!bmp.isRecycled()) bmp.recycle();
        }
    }

    private byte[] copyFromWindow() {
        final Handler h = copyHandler;
        final Window window = activity.getWindow();
        if (h == null || window == null) return null;
        final Bitmap bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger resultCode = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        try {
            PixelCopy.request(window, bmp, copyResult -> {
                resultCode.set(copyResult);
                latch.countDown();
            }, h);
            if (!latch.await(PIXEL_COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Window PixelCopy timed out");
                return null;
            }
            if (resultCode.get() == PixelCopy.SUCCESS) {
                return compressAndRecycle(bmp);
            }
            Log.d(TAG, "Window PixelCopy status=" + resultCode.get());
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "Window PixelCopy failed: " + t.getMessage());
            return null;
        } finally {
            if (!bmp.isRecycled()) bmp.recycle();
        }
    }

    private byte[] drawWebViewFallback() {
        if (root == null) return null;
        final WebView webView = findVisibleWebView(root);
        if (webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0) return null;

        final Bitmap bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        uiHandler.post(() -> {
            try {
                final Canvas canvas = new Canvas(bmp);
                canvas.scale((float) OUT_W / webView.getWidth(), (float) OUT_H / webView.getHeight());
                webView.draw(canvas);
                ok[0] = true;
            } catch (Throwable t) {
                Log.w(TAG, "WebView software draw failed: " + t.getMessage());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(PIXEL_COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return ok[0] ? compressAndRecycle(bmp) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (!bmp.isRecycled()) bmp.recycle();
        }
    }

    private static WebView findVisibleWebView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            if (child instanceof WebView && child.getVisibility() == View.VISIBLE) {
                return (WebView) child;
            }
            if (child instanceof ViewGroup) {
                final WebView nested = findVisibleWebView((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static byte[] compressAndRecycle(Bitmap bmp) {
        try {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)) {
                return null;
            }
            return baos.toByteArray();
        } finally {
            if (!bmp.isRecycled()) bmp.recycle();
        }
    }
}

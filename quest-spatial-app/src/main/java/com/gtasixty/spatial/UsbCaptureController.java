package com.gtasixty.spatial;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct USB-C UVC HDMI capture controller on Meta Quest 3 using Android {@link UsbManager}.
 *
 * Horizon OS does not expose UVC capture cards through Camera2, so this controller
 * parses the USB descriptors, performs UVC Probe/Commit control transfers over USB,
 * reads bulk video payloads (YUY2 uncompressed or MJPEG fallback) into {@link CaptureGlRenderer},
 * and streams UAC USB audio via AudioRecord -> low-latency AudioTrack.
 */
final class UsbCaptureController {

    private static final String TAG = "GTA60-USB";
    private static final String ACTION_USB_PERMISSION = "com.gtasixty.spatial.USB_PERMISSION";

    private static final int USB_DT_INTERFACE = 0x04;
    private static final int USB_DT_CS_INTERFACE = 0x24;
    private static final int UVC_VS_FORMAT_UNCOMPRESSED = 0x04;
    private static final int UVC_VS_FRAME_UNCOMPRESSED = 0x05;
    private static final int UVC_VS_FORMAT_MJPEG = 0x06;
    private static final int UVC_VS_FRAME_MJPEG = 0x07;

    private static final int UVC_SET_CUR = 0x01;
    private static final int UVC_GET_CUR = 0x81;
    private static final int UVC_GET_LEN = 0x85;
    private static final int UVC_VS_PROBE_CONTROL = 0x0100;
    private static final int UVC_VS_COMMIT_CONTROL = 0x0200;

    interface Listener {
        void onStarted(int width, int height, int fps);
        void onFrameRendered(boolean generated);
        void onStatus(String message);
        void onError(String message);
        void onStopped();
    }

    private static final class UvcMode {
        final int formatIndex;
        final int frameIndex;
        final int width;
        final int height;
        final int frameInterval; // 100 ns units
        final int fps;
        final boolean isYuy2;

        UvcMode(int formatIndex, int frameIndex, int width, int height, int frameInterval, boolean isYuy2) {
            this.formatIndex = formatIndex;
            this.frameIndex = frameIndex;
            this.width = width;
            this.height = height;
            this.frameInterval = frameInterval > 0 ? frameInterval : 166666;
            this.fps = Math.max(1, (int) Math.round(10_000_000.0 / this.frameInterval));
            this.isYuy2 = isYuy2;
        }
    }

    private static final class UvcEndpointTarget {
        final UsbDevice device;
        final UsbInterface vcIface;
        final UsbInterface vsIface;
        final UsbEndpoint bulkInEp;

        UvcEndpointTarget(UsbDevice device, UsbInterface vcIface, UsbInterface vsIface, UsbEndpoint bulkInEp) {
            this.device = device;
            this.vcIface = vcIface;
            this.vsIface = vsIface;
            this.bulkInEp = bulkInEp;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean terminalDelivered = new AtomicBoolean(false);

    private BroadcastReceiver usbPermReceiver;
    private boolean receiverRegistered = false;

    private Thread videoThread;
    private Thread audioThread;
    private volatile boolean audioRunning = false;

    private UsbDeviceConnection usbConnection;
    private UsbInterface claimedVc;
    private UsbInterface claimedVs;
    private volatile CaptureGlRenderer captureGl;

    UsbCaptureController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isRunning() {
        return running.get();
    }

    void setEnhanceMode(int mode) {
        CaptureGlRenderer gl = captureGl;
        if (gl != null) gl.setEnhanceMode(mode);
    }

    void setLookMode(int look) {
        CaptureGlRenderer gl = captureGl;
        if (gl != null) gl.setLookMode(look);
    }

    void setTaaEnabled(boolean enabled) {
        CaptureGlRenderer gl = captureGl;
        if (gl != null) gl.setTaaEnabled(enabled);
    }

    void setComputeFlowEnabled(boolean enabled) {
        CaptureGlRenderer gl = captureGl;
        if (gl != null) gl.setComputeFlowEnabled(enabled);
    }

    void start(Surface outputSurface, int enhanceMode, int lookMode) {
        if (outputSurface == null || !outputSurface.isValid()) {
            deliverError("Cannot start USB capture: display surface unavailable.");
            return;
        }
        if (running.getAndSet(true)) {
            Log.w(TAG, "UsbCaptureController.start ignored: already running");
            return;
        }
        terminalDelivered.set(false);

        UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (um == null) {
            deliverError("UsbManager unavailable on this device.");
            stop();
            return;
        }

        logConnectedDevices(um);
        UvcEndpointTarget target = findUvcBulkDevice(um);
        if (target == null) {
            deliverError("No USB-C UVC capture card found. Plug in the capture card and close Meta Quest HDMI Link if open.");
            stop();
            return;
        }

        Log.i(TAG, String.format("Found UVC Bulk device %04x:%04x '%s' vcIf=%d vsIf=%d alt=%d ep=0x%02x maxPkt=%d",
                target.device.getVendorId(), target.device.getProductId(), target.device.getProductName(),
                target.vcIface != null ? target.vcIface.getId() : -1,
                target.vsIface.getId(), target.vsIface.getAlternateSetting(),
                target.bulkInEp.getAddress(), target.bulkInEp.getMaxPacketSize()));

        if (um.hasPermission(target.device)) {
            launchStreamingThread(um, target, outputSurface, enhanceMode, lookMode);
            return;
        }

        if (listener != null) {
            listener.onStatus("Allow USB permission for " + target.device.getProductName() + "...");
        }
        registerPermissionReceiver(um, target, outputSurface, enhanceMode, lookMode);

        Intent permIntent = new Intent(ACTION_USB_PERMISSION);
        permIntent.setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, permIntent, flags);
        try {
            um.requestPermission(target.device, pi);
        } catch (Exception e) {
            deliverError("USB permission request failed: " + e.getMessage());
            stop();
        }
    }

    private void registerPermissionReceiver(
            UsbManager um,
            UvcEndpointTarget target,
            Surface outputSurface,
            int enhanceMode,
            int lookMode) {
        synchronized (lock) {
            unregisterReceiverLocked();
            usbPermReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null || !ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                    synchronized (lock) {
                        unregisterReceiverLocked();
                    }
                    if (!running.get()) return;

                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            || um.hasPermission(target.device);
                    Log.i(TAG, "USB permission result granted=" + granted + " for " + target.device.getProductName());
                    if (granted) {
                        launchStreamingThread(um, target, outputSurface, enhanceMode, lookMode);
                    } else {
                        deliverError("USB permission denied for " + target.device.getProductName()
                                + ". Tap Start USB and select Allow.");
                        stop();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(usbPermReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(usbPermReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    private void unregisterReceiverLocked() {
        if (receiverRegistered && usbPermReceiver != null) {
            try {
                context.unregisterReceiver(usbPermReceiver);
            } catch (Exception ignored) {}
        }
        receiverRegistered = false;
        usbPermReceiver = null;
    }

    private void launchStreamingThread(
            UsbManager um,
            UvcEndpointTarget target,
            Surface outputSurface,
            int enhanceMode,
            int lookMode) {
        Thread t = new Thread(
                () -> runBulkLoop(um, target, outputSurface, enhanceMode, lookMode),
                "GTA60-UsbUvc");
        synchronized (lock) {
            if (!running.get()) return;
            videoThread = t;
        }
        t.start();
    }

    private void runBulkLoop(
            UsbManager um,
            UvcEndpointTarget target,
            Surface outputSurface,
            int enhanceMode,
            int lookMode) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) {}

        UsbDeviceConnection conn = null;
        try {
            conn = um.openDevice(target.device);
            if (conn == null) {
                deliverError("Could not open USB capture device (close Meta Quest HDMI Link if open).");
                stop();
                return;
            }
            synchronized (lock) {
                if (!running.get()) {
                    conn.close();
                    return;
                }
                usbConnection = conn;
            }

            if (target.vcIface != null) {
                boolean vcOk = conn.claimInterface(target.vcIface, true);
                Log.i(TAG, "claimInterface VC(" + target.vcIface.getId() + ")=" + vcOk);
                if (vcOk) {
                    synchronized (lock) { claimedVc = target.vcIface; }
                }
            }
            boolean vsOk = conn.claimInterface(target.vsIface, true);
            Log.i(TAG, "claimInterface VS(" + target.vsIface.getId() + ")=" + vsOk);
            if (!vsOk) {
                deliverError("USB capture interface busy — close Meta Quest HDMI Link (Virtual Display) and retry.");
                stop();
                return;
            }
            synchronized (lock) { claimedVs = target.vsIface; }
            if (target.vsIface.getAlternateSetting() != 0) {
                conn.setInterface(target.vsIface);
            }

            byte[] rawDesc = conn.getRawDescriptors();
            if (rawDesc == null || rawDesc.length == 0) {
                deliverError("USB capture card returned empty descriptors.");
                stop();
                return;
            }
            List<UvcMode> modes = parseUvcModes(rawDesc, target.vsIface.getId());
            UvcMode mode = selectBestMode(modes);
            if (mode == null) {
                deliverError("No supported YUY2 or MJPEG streaming modes found on capture card.");
                stop();
                return;
            }

            Log.i(TAG, String.format("Selected UVC mode: %s %dx%d @ %dfps (fmt=%d frm=%d iv=%d)",
                    mode.isYuy2 ? "YUY2" : "MJPEG", mode.width, mode.height, mode.fps,
                    mode.formatIndex, mode.frameIndex, mode.frameInterval));
            if (listener != null) {
                listener.onStatus(String.format("Negotiating USB %s %dx%d @ %dfps...",
                        mode.isYuy2 ? "YUY2" : "MJPEG", mode.width, mode.height, mode.fps));
            }

            int[] neg = negotiateUvc(conn, target.vsIface.getId(), mode);
            if (neg == null) {
                deliverError("UVC probe/commit failed for " + target.device.getProductName() + ".");
                stop();
                return;
            }
            int maxFrameSize = neg[0];
            int maxPayload = neg[1];

            CaptureGlRenderer gl = new CaptureGlRenderer(
                    context,
                    outputSurface,
                    mode.width,
                    mode.height,
                    enhanceMode,
                    lookMode,
                    true,
                    mode.isYuy2,
                    generated -> {
                        if (listener != null) listener.onFrameRendered(generated);
                    });
            if (!gl.awaitReady()) {
                gl.release();
                deliverError("Failed to initialize GPU renderer for USB capture.");
                stop();
                return;
            }
            Surface decSurf = mode.isYuy2 ? null : gl.getDecoderSurface();
            if (!mode.isYuy2 && (decSurf == null || !decSurf.isValid())) {
                gl.release();
                deliverError("Failed to initialize MJPEG surface for USB capture.");
                stop();
                return;
            }
            synchronized (lock) {
                if (!running.get()) {
                    gl.release();
                    return;
                }
                captureGl = gl;
            }

            startUsbAudioIfAvailable();
            if (listener != null) {
                listener.onStarted(mode.width, mode.height, mode.fps);
            }

            final int expectedYuy2Bytes = mode.width * mode.height * 2;
            final int frameBufSize = mode.isYuy2
                    ? Math.max(expectedYuy2Bytes, Math.min(maxFrameSize, expectedYuy2Bytes * 2))
                    : Math.max(2 * 1024 * 1024, Math.min(maxFrameSize, 8 * 1024 * 1024));
            final byte[] frameBuf = new byte[frameBufSize];
            final int chunkSize = (maxPayload > 0 && maxPayload <= 65536) ? maxPayload : 16384;
            final int payloadCap = (maxPayload > 0) ? maxPayload : frameBufSize;
            final byte[] xferBuf = new byte[chunkSize];

            int framePos = 0;
            int payloadRemaining = 0;
            int lastFid = -1;
            boolean pendingEof = false;
            boolean frameError = false;

            long statFrames = 0;
            long statBytes = 0;
            long statErrs = 0;
            long lastLogMs = System.currentTimeMillis();
            boolean loggedFirstHdr = false;

            while (running.get()) {
                int n = conn.bulkTransfer(target.bulkInEp, xferBuf, chunkSize, 500);
                if (n < 0) {
                    if (!running.get()) break;
                    statErrs++;
                    payloadRemaining = 0;
                    continue;
                }
                if (n == 0) {
                    // ZLP terminates the current UVC bulk payload.
                    payloadRemaining = 0;
                    if (pendingEof || (mode.isYuy2 && framePos >= expectedYuy2Bytes)) {
                        if (!frameError) {
                            emitFrame(gl, decSurf, mode, frameBuf, framePos, expectedYuy2Bytes);
                            statFrames++;
                        }
                        framePos = 0;
                        pendingEof = false;
                        frameError = false;
                    }
                    continue;
                }
                statBytes += n;

                int off = 0;
                while (off < n) {
                    if (payloadRemaining == 0) {
                        int hdrLen = xferBuf[off] & 0xFF;
                        int hdrInfo = (off + 1 < n) ? (xferBuf[off + 1] & 0xFF) : 0;
                        boolean validHdr = hdrLen >= 2 && hdrLen <= 12 && (off + hdrLen <= n) && (hdrInfo & 0x80) != 0;
                        if (!loggedFirstHdr) {
                            loggedFirstHdr = true;
                            Log.i(TAG, String.format("First UVC bulk packet n=%d hdrLen=%d hdrInfo=0x%02x valid=%s",
                                    n, hdrLen, hdrInfo, validHdr));
                        }
                        if (!validHdr) {
                            // Lost framing; reset and wait for next valid payload start.
                            framePos = 0;
                            payloadRemaining = 0;
                            pendingEof = false;
                            frameError = false;
                            break;
                        }
                        int fid = hdrInfo & 0x01;
                        boolean eof = (hdrInfo & 0x02) != 0;
                        boolean err = (hdrInfo & 0x40) != 0;
                        if (lastFid != -1 && fid != lastFid && framePos > 0) {
                            if (!frameError) {
                                emitFrame(gl, decSurf, mode, frameBuf, framePos, expectedYuy2Bytes);
                                statFrames++;
                            }
                            framePos = 0;
                            pendingEof = false;
                            frameError = false;
                        }
                        lastFid = fid;
                        if (eof) pendingEof = true;
                        if (err) frameError = true;
                        payloadRemaining = Math.max(0, payloadCap - hdrLen);
                        off += hdrLen;
                    }

                    int avail = n - off;
                    if (avail > 0) {
                        int copyLen = payloadRemaining > 0 ? Math.min(avail, payloadRemaining) : avail;
                        if (!frameError && framePos + copyLen <= frameBuf.length) {
                            System.arraycopy(xferBuf, off, frameBuf, framePos, copyLen);
                            framePos += copyLen;
                        } else if (!frameError) {
                            frameError = true;
                        }
                        off += copyLen;
                        if (payloadRemaining > 0) {
                            payloadRemaining -= copyLen;
                        }
                    }

                    if (n < chunkSize) {
                        payloadRemaining = 0;
                    }
                    if (payloadRemaining == 0 && (pendingEof || (mode.isYuy2 && framePos >= expectedYuy2Bytes))) {
                        if (!frameError) {
                            emitFrame(gl, decSurf, mode, frameBuf, framePos, expectedYuy2Bytes);
                            statFrames++;
                        }
                        framePos = 0;
                        pendingEof = false;
                        frameError = false;
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastLogMs >= 2000L) {
                    double dt = (now - lastLogMs) / 1000.0;
                    double fps = statFrames / dt;
                    double mbps = statBytes / (dt * 1024.0 * 1024.0);
                    Log.i(TAG, String.format("USB-Bulk: frames=%d (%.1f fps) %.1f MB/s errs=%d",
                            statFrames, fps, mbps, statErrs));
                    statFrames = 0;
                    statBytes = 0;
                    statErrs = 0;
                    lastLogMs = now;
                }
            }
        } catch (SecurityException se) {
            deliverError("USB permission error: " + se.getMessage());
        } catch (Throwable t) {
            if (running.get()) {
                Log.e(TAG, "USB bulk loop failed", t);
                deliverError("USB capture error: " + t.getMessage());
            }
        } finally {
            stop();
        }
    }

    private void emitFrame(
            CaptureGlRenderer gl,
            Surface mjpegSurf,
            UvcMode mode,
            byte[] frameBuf,
            int frameLen,
            int expectedYuy2Bytes) {
        if (frameLen <= 0) return;
        if (mode.isYuy2) {
            int minAccept = (expectedYuy2Bytes * 9) / 10;
            if (frameLen < minAccept) return;
            if (frameLen < expectedYuy2Bytes) {
                Arrays.fill(frameBuf, frameLen, expectedYuy2Bytes, (byte) 0x80);
            }
            gl.submitYuy2Frame(frameBuf, expectedYuy2Bytes);
        } else if (mjpegSurf != null && mjpegSurf.isValid()) {
            Bitmap bmp = BitmapFactory.decodeByteArray(frameBuf, 0, frameLen);
            if (bmp == null) return;
            try {
                Canvas c = Build.VERSION.SDK_INT >= 23
                        ? mjpegSurf.lockHardwareCanvas()
                        : mjpegSurf.lockCanvas(null);
                if (c != null) {
                    try {
                        c.drawBitmap(bmp, null, new Rect(0, 0, mode.width, mode.height), null);
                    } finally {
                        mjpegSurf.unlockCanvasAndPost(c);
                    }
                    gl.onDecodedFrame(false);
                }
            } catch (Exception e) {
                Log.w(TAG, "MJPEG surface draw error: " + e.getMessage());
            } finally {
                bmp.recycle();
            }
        }
    }

    private int[] negotiateUvc(UsbDeviceConnection conn, int vsIfaceNum, UvcMode mode) {
        byte[] lenBuf = new byte[2];
        int lenRes = conn.controlTransfer(0xA1, UVC_GET_LEN, UVC_VS_PROBE_CONTROL, vsIfaceNum, lenBuf, 2, 1000);
        int probeLen = (lenRes == 2) ? ((lenBuf[0] & 0xFF) | ((lenBuf[1] & 0xFF) << 8)) : 26;
        if (probeLen < 26 || probeLen > 48) probeLen = 26;

        byte[] probe = new byte[probeLen];
        // bmHint = 1 (keep dwFrameInterval fixed)
        probe[0] = 1;
        probe[1] = 0;
        probe[2] = (byte) mode.formatIndex;
        probe[3] = (byte) mode.frameIndex;
        putLe32(probe, 4, mode.frameInterval);

        int setProbe = conn.controlTransfer(0x21, UVC_SET_CUR, UVC_VS_PROBE_CONTROL, vsIfaceNum, probe, probeLen, 1000);
        int getProbe = conn.controlTransfer(0xA1, UVC_GET_CUR, UVC_VS_PROBE_CONTROL, vsIfaceNum, probe, probeLen, 1000);
        if (setProbe < 0 || getProbe < 26) {
            Log.e(TAG, String.format("UVC PROBE failed len=%d (res=%d) set=%d get=%d",
                    probeLen, lenRes, setProbe, getProbe));
            return null;
        }
        int maxFrameSize = le32(probe, 18);
        int maxPayload = le32(probe, 22);
        int negInterval = le32(probe, 4);
        int setCommit = conn.controlTransfer(0x21, UVC_SET_CUR, UVC_VS_COMMIT_CONTROL, vsIfaceNum, probe, probeLen, 1000);
        Log.i(TAG, String.format("UVC negotiated len=%d setProbe=%d getProbe=%d setCommit=%d fmt=%d frm=%d iv=%d maxFrame=%d maxPayload=%d",
                probeLen, setProbe, getProbe, setCommit,
                probe[2] & 0xFF, probe[3] & 0xFF, negInterval, maxFrameSize, maxPayload));
        if (setCommit < 0) return null;
        return new int[]{maxFrameSize, maxPayload};
    }

    private List<UvcMode> parseUvcModes(byte[] rawDesc, int vsIfaceNum) {
        List<UvcMode> modes = new ArrayList<>();
        int pos = 0;
        int currentIfaceNum = -1;
        int currentFmtIdx = -1;
        boolean currentIsYuy2 = false;
        boolean currentIsMjpeg = false;

        while (pos + 2 <= rawDesc.length) {
            int len = rawDesc[pos] & 0xFF;
            int type = rawDesc[pos + 1] & 0xFF;
            if (len < 2 || pos + len > rawDesc.length) break;

            if (type == USB_DT_INTERFACE && len >= 4) {
                currentIfaceNum = rawDesc[pos + 2] & 0xFF;
                currentFmtIdx = -1;
                currentIsYuy2 = false;
                currentIsMjpeg = false;
            } else if (type == USB_DT_CS_INTERFACE && currentIfaceNum == vsIfaceNum && len >= 3) {
                int subtype = rawDesc[pos + 2] & 0xFF;
                if (subtype == UVC_VS_FORMAT_UNCOMPRESSED && len >= 22) {
                    currentFmtIdx = rawDesc[pos + 3] & 0xFF;
                    currentIsYuy2 = (rawDesc[pos + 5] == 0x59 && rawDesc[pos + 6] == 0x55
                            && rawDesc[pos + 7] == 0x59 && rawDesc[pos + 8] == 0x32);
                    currentIsMjpeg = false;
                    Log.i(TAG, String.format("  UVC Uncompressed fmt=%d fourcc=%c%c%c%c bpp=%d",
                            currentFmtIdx,
                            printable(rawDesc[pos + 5]), printable(rawDesc[pos + 6]),
                            printable(rawDesc[pos + 7]), printable(rawDesc[pos + 8]),
                            rawDesc[pos + 21] & 0xFF));
                } else if (subtype == UVC_VS_FRAME_UNCOMPRESSED && len >= 26 && currentFmtIdx > 0 && currentIsYuy2) {
                    addFrameMode(rawDesc, pos, len, currentFmtIdx, true, modes);
                } else if (subtype == UVC_VS_FORMAT_MJPEG && len >= 4) {
                    currentFmtIdx = rawDesc[pos + 3] & 0xFF;
                    currentIsMjpeg = true;
                    currentIsYuy2 = false;
                    Log.i(TAG, String.format("  UVC MJPEG fmt=%d", currentFmtIdx));
                } else if (subtype == UVC_VS_FRAME_MJPEG && len >= 26 && currentFmtIdx > 0 && currentIsMjpeg) {
                    addFrameMode(rawDesc, pos, len, currentFmtIdx, false, modes);
                }
            }
            pos += len;
        }
        return modes;
    }

    private void addFrameMode(byte[] rawDesc, int pos, int len, int fmtIdx, boolean isYuy2, List<UvcMode> out) {
        int frmIdx = rawDesc[pos + 3] & 0xFF;
        int w = le16(rawDesc, pos + 5);
        int h = le16(rawDesc, pos + 7);
        int defInterval = le32(rawDesc, pos + 21);
        int ivType = rawDesc[pos + 25] & 0xFF;

        int chosenIv = defInterval > 0 ? defInterval : 166666;
        if (ivType == 0 && len >= 38) {
            int minIv = le32(rawDesc, pos + 26);
            int maxIv = le32(rawDesc, pos + 30);
            if (166666 >= minIv && 166666 <= maxIv) {
                chosenIv = 166666;
            } else if (minIv >= 160000) {
                chosenIv = minIv;
            }
        } else if (ivType > 0) {
            int bestLe60 = 0;
            int minAny = Integer.MAX_VALUE;
            for (int i = 0; i < ivType && pos + 26 + (i + 1) * 4 <= pos + len; i++) {
                int iv = le32(rawDesc, pos + 26 + i * 4);
                if (iv <= 0) continue;
                if (iv < minAny) minAny = iv;
                if (iv >= 160000 && (bestLe60 == 0 || iv < bestLe60)) {
                    bestLe60 = iv;
                }
            }
            if (bestLe60 > 0) chosenIv = bestLe60;
            else if (minAny < Integer.MAX_VALUE) chosenIv = minAny;
        }

        UvcMode m = new UvcMode(fmtIdx, frmIdx, w, h, chosenIv, isYuy2);
        out.add(m);
        Log.i(TAG, String.format("    Mode %s fmt=%d frm=%d %dx%d @ %dfps (iv=%d)",
                isYuy2 ? "YUY2" : "MJPEG", fmtIdx, frmIdx, w, h, m.fps, m.frameInterval));
    }

    private UvcMode selectBestMode(List<UvcMode> modes) {
        if (modes.isEmpty()) return null;
        UvcMode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (UvcMode m : modes) {
            int score = 0;
            if (m.isYuy2) score += 10_000_000;
            if (m.width == 1920 && m.height == 1080) {
                score += 5_000_000;
            } else if (m.width <= 1920 && m.height <= 1080) {
                score += 2_000_000 + m.width * m.height;
                if (Math.abs(m.width * 9 - m.height * 16) <= 16) score += 250_000;
            } else {
                // Keep 1080p preferred over >1080p modes on USB/Quest GPU pipeline.
                score += Math.max(0, 1_000_000 - (m.width * m.height) / 10);
            }
            score += Math.min(m.fps, 60) * 1000;
            if (best == null || score > bestScore) {
                best = m;
                bestScore = score;
            }
        }
        return best;
    }

    private UvcEndpointTarget findUvcBulkDevice(UsbManager um) {
        Map<String, UsbDevice> devs = um.getDeviceList();
        for (UsbDevice dev : devs.values()) {
            UsbInterface vc = null;
            UsbInterface vs = null;
            UsbEndpoint ep = null;
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface ui = dev.getInterface(i);
                if (ui.getInterfaceClass() != UsbConstants.USB_CLASS_VIDEO) continue;
                if (ui.getInterfaceSubclass() == 1 && vc == null) {
                    vc = ui;
                } else if (ui.getInterfaceSubclass() == 2) {
                    for (int e = 0; e < ui.getEndpointCount(); e++) {
                        UsbEndpoint candidate = ui.getEndpoint(e);
                        if (candidate.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                                && candidate.getDirection() == UsbConstants.USB_DIR_IN) {
                            vs = ui;
                            ep = candidate;
                            break;
                        }
                    }
                }
            }
            if (vs != null && ep != null) {
                return new UvcEndpointTarget(dev, vc, vs, ep);
            }
        }
        return null;
    }

    void stop() {
        running.set(false);
        audioRunning = false;

        Thread vThread;
        Thread aThread;
        UsbDeviceConnection conn;
        UsbInterface vc;
        UsbInterface vs;
        CaptureGlRenderer gl;

        synchronized (lock) {
            unregisterReceiverLocked();
            vThread = videoThread;
            videoThread = null;
            aThread = audioThread;
            audioThread = null;
            conn = usbConnection;
            usbConnection = null;
            vc = claimedVc;
            claimedVc = null;
            vs = claimedVs;
            claimedVs = null;
            gl = captureGl;
            captureGl = null;
        }

        if (aThread != null && aThread != Thread.currentThread()) {
            aThread.interrupt();
            try { aThread.join(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (conn != null) {
            if (vs != null) {
                try { conn.releaseInterface(vs); } catch (Exception ignored) {}
            }
            if (vc != null) {
                try { conn.releaseInterface(vc); } catch (Exception ignored) {}
            }
            try { conn.close(); } catch (Exception ignored) {}
        }
        if (vThread != null && vThread != Thread.currentThread()) {
            vThread.interrupt();
            try { vThread.join(600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (gl != null) {
            gl.release();
        }

        if (!terminalDelivered.getAndSet(true) && listener != null) {
            listener.onStopped();
        }
    }

    private void deliverError(String message) {
        Log.e(TAG, message);
        if (!terminalDelivered.getAndSet(true) && listener != null) {
            listener.onError(message);
        }
    }

    private void startUsbAudioIfAvailable() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "RECORD_AUDIO not granted; continuing USB video without audio");
            return;
        }
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        AudioDeviceInfo usbInput = null;
        for (AudioDeviceInfo dev : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = dev.getType();
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_LINE_ANALOG) {
                usbInput = dev;
                break;
            }
        }
        if (usbInput == null) {
            Log.i(TAG, "No USB audio input device found; continuing USB video without UAC audio");
            return;
        }

        final AudioDeviceInfo selectedInput = usbInput;
        audioRunning = true;
        Thread t = new Thread(() -> runUsbAudioLoop(selectedInput), "GTA60-UsbAudio");
        t.setDaemon(true);
        synchronized (lock) {
            if (!running.get()) {
                audioRunning = false;
                return;
            }
            audioThread = t;
        }
        t.start();
    }

    private void runUsbAudioLoop(AudioDeviceInfo usbDev) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception ignored) {}

        AudioRecord record = null;
        AudioTrack track = null;
        try {
            final int sampleRate = 48000;
            final int minRec = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            final int recBufBytes = Math.max(minRec, sampleRate * 4 / 50);

            AudioFormat inFmt = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();

            int[] sources = {MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.DEFAULT};
            for (int src : sources) {
                try {
                    AudioRecord candidate = new AudioRecord.Builder()
                            .setAudioSource(src)
                            .setAudioFormat(inFmt)
                            .setBufferSizeInBytes(recBufBytes)
                            .build();
                    if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                        record = candidate;
                        break;
                    }
                    candidate.release();
                } catch (Exception e) {
                    Log.w(TAG, "AudioRecord source=" + src + " failed: " + e.getMessage());
                }
            }
            if (record == null) {
                Log.w(TAG, "Could not initialize USB AudioRecord; continuing video-only");
                return;
            }
            record.setPreferredDevice(usbDev);

            int minPlay = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack.Builder tb = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minPlay, sampleRate * 4 / 50))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = tb.build();

            record.startRecording();
            track.play();
            Log.i(TAG, "USB UAC audio started from " + usbDev.getProductName() + " (type=" + usbDev.getType() + ")");

            byte[] pcm = new byte[1920];
            while (audioRunning && running.get()) {
                int read = record.read(pcm, 0, pcm.length);
                if (read > 0) {
                    if (Build.VERSION.SDK_INT >= 23) {
                        track.write(pcm, 0, read, AudioTrack.WRITE_NON_BLOCKING);
                    } else {
                        track.write(pcm, 0, read);
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error: " + read);
                    break;
                }
            }
        } catch (SecurityException se) {
            Log.w(TAG, "USB audio permission denied: " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "USB audio loop error: " + t.getMessage());
        } finally {
            if (record != null) {
                try { record.stop(); } catch (Exception ignored) {}
                try { record.release(); } catch (Exception ignored) {}
            }
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void logConnectedDevices(UsbManager um) {
        try {
            Map<String, UsbDevice> devs = um.getDeviceList();
            Log.i(TAG, "Diag UsbManager devices=" + devs.size());
            for (UsbDevice d : devs.values()) {
                StringBuilder ifaces = new StringBuilder();
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    UsbInterface ui = d.getInterface(i);
                    if (i > 0) ifaces.append(',');
                    ifaces.append(ui.getInterfaceClass()).append(':').append(ui.getInterfaceSubclass());
                }
                Log.i(TAG, String.format("  USB %04x:%04x name='%s' mfg='%s' perm=%s ifaces=[%s]",
                        d.getVendorId(), d.getProductId(), d.getProductName(),
                        d.getManufacturerName(), um.hasPermission(d), ifaces.toString()));
            }
        } catch (Exception e) {
            Log.w(TAG, "Diag UsbManager failed: " + e.getMessage());
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
                Log.i(TAG, "Diag Audio inputs=" + inputs.length);
                for (AudioDeviceInfo info : inputs) {
                    Log.i(TAG, String.format("  AudioIn type=%d name='%s' rates=%s ch=%s",
                            info.getType(), info.getProductName(),
                            Arrays.toString(info.getSampleRates()),
                            Arrays.toString(info.getChannelCounts())));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Diag AudioManager failed: " + e.getMessage());
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static void putLe32(byte[] b, int off, int val) {
        b[off] = (byte) (val & 0xFF);
        b[off + 1] = (byte) ((val >>> 8) & 0xFF);
        b[off + 2] = (byte) ((val >>> 16) & 0xFF);
        b[off + 3] = (byte) ((val >>> 24) & 0xFF);
    }

    private static char printable(byte b) {
        int c = b & 0xFF;
        return (c >= 32 && c < 127) ? (char) c : '?';
    }
}

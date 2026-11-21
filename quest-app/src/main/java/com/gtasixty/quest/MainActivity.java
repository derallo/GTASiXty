package com.gtasixty.quest;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "GTA60";
    private static final String TAG_PERF = "GTA60-Perf";
    // Empty means "nothing configured yet" - the app then scans the LAN instead of dialling a
    // hardcoded address.
    private static final String DEFAULT_PC_HOST = "";

    private static final int GT60_MAGIC = 0x30365447; // "GT60" LE
    private static final int TYPE_VIDEO_CONFIG = 0x01;
    private static final int TYPE_VIDEO_AU = 0x02;
    private static final int TYPE_AUDIO_PCM = 0x03;
    private static final int TYPE_CLIENT_IDR = 0x81;
    private static final int TYPE_CLIENT_GAMEPAD = 0x82;
    private static final int TYPE_CLIENT_CONFIG = 0x83;
    // Echo of the AU timestamp we just presented, so the PC can measure the PC->Quest leg
    // (transport + decode queue + present) instead of guessing at it.
    private static final int TYPE_CLIENT_RENDERED = 0x84;

    // VIDEO_CONFIG payload: u32 width, u32 height, u8 codec, then the Annex-B parameter sets.
    // The codec byte must match gta60::media::VideoCodec on the PC side.
    private static final int VIDEO_CONFIG_HEADER_BYTES = 9;
    private static final int CODEC_H264 = 0;
    private static final int CODEC_HEVC = 1;
    private static final String MIME_AVC = "video/avc";
    private static final String MIME_HEVC = "video/hevc";

    private static final int FLAG_IDR = 0x01;
    private static final int FLAG_GENERATED = 0x02;

    private static final int DEFAULT_TCP_PORT = 7660;
    private static final int DISCOVERY_UDP_PORT = 7661;
    // PC-side agent listens here to launch the streaming app on request.
    private static final int START_UDP_PORT = 7662;

    private static final int[] RES_WIDTHS = {1920, 2560, 3840};
    private static final int[] RES_HEIGHTS = {1080, 1440, 2160};
    private static final String[] RES_LABELS = {"1080p", "1440p", "4K"};
    private static final int[] BITRATE_OPTIONS = {30, 50, 80, 120};

    private SurfaceView surfaceView;
    private LinearLayout hudBar;
    private EditText hostInput;
    private Button startBtn;
    private Button resBtn;
    private Button fpsBtn;
    private Button bitrateBtn;
    // "Hold session": re-send the current pad state every ~20 s so the console never decides the
    // player went idle. That is what stops the stream from dying while you are away, and - more
    // importantly - what stops a reconnect from attaching a new client mid-game (which drops the
    // console to the dashboard) or leaving a stale virtual pad holding player 1 (notes 10.5, 12).
    // Costs power: the console stays awake while it is on, so it is a visible toggle.
    private boolean keepAlive = true;
    private volatile long lastGamepadSendMs = 0;
    private Button holdBtn;
    private TextView statusText;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

    private volatile Surface activeSurface;
    private final Object surfaceLock = new Object();
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private boolean autoStartTriggered = false;
    private Thread netThread;
    private Thread decodeOutThread;
    private volatile Socket socket;
    private final Object sendLock = new Object();
    private OutputStream sockOut;

    private MediaCodec codec;
    private final Object codecLock = new Object();
    private AudioTrack audioTrack;
    private volatile boolean firstFrameRendered = false;

    // User stream settings persisted in SharedPreferences
    private int resIdx = 1; // default 1440p
    private int cfgFps = 60;
    private int bitrateIdx = 1; // default 50 Mbps

    // Telemetry counters
    private volatile int streamWidth = 0;
    private volatile int streamHeight = 0;
    private volatile int targetFps = 60;
    private volatile long statFrames = 0;
    private volatile long statRendered = 0;
    private volatile long statGenFrames = 0;
    private volatile long statBytes = 0;
    private volatile double lastDecodeMs = 0.0;
    private long lastStatTimeMs = 0;
    private long lastPerfLogTimeMs = 0;

    // Gamepad state (matching gta60::xbox::GamepadFrame 16-byte layout)
    private int padButtons = 0;
    private short padLX = 0, padLY = 0, padRX = 0, padRY = 0;
    private int padLT = 0, padRT = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences("gta60", MODE_PRIVATE);
        String savedHost = prefs.getString("host", DEFAULT_PC_HOST);
        if (!prefs.contains("prefs_v2")) {
            savedHost = DEFAULT_PC_HOST;
            prefs.edit().putBoolean("prefs_v2", true).putString("host", savedHost).apply();
        }
        resIdx = Math.max(0, Math.min(RES_LABELS.length - 1, prefs.getInt("resIdx", 1)));
        cfgFps = prefs.getInt("cfgFps", 60) == 90 ? 90 : 60;
        bitrateIdx = Math.max(0, Math.min(BITRATE_OPTIONS.length - 1, prefs.getInt("bitrateIdx", 1)));
        keepAlive = prefs.getBoolean("keepAlive", true);
        if (!prefs.contains("prefs_v3")) {
            bitrateIdx = 1;
            prefs.edit().putBoolean("prefs_v3", true).putInt("bitrateIdx", bitrateIdx).apply();
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0E14"));

        surfaceView = new SurfaceView(this);
        FrameLayout.LayoutParams svParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        root.addView(surfaceView, svParams);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnClickListener(v -> toggleHud());

        hudBar = new LinearLayout(this);
        hudBar.setOrientation(LinearLayout.VERTICAL);
        hudBar.setBackgroundColor(Color.parseColor("#CC111622"));
        int pad = dp(8);
        hudBar.setPadding(pad, pad, pad, pad);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("GTA-Sixty");
        title.setTextColor(Color.parseColor("#22C55E"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setPadding(0, 0, dp(10), 0);
        row1.addView(title);

        hostInput = new EditText(this);
        hostInput.setText(savedHost);
        hostInput.setHint("PC IP (auto if blank)");
        hostInput.setTextColor(Color.WHITE);
        hostInput.setHintTextColor(Color.GRAY);
        hostInput.setBackgroundColor(Color.parseColor("#1E293B"));
        hostInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hostInput.setPadding(dp(8), dp(5), dp(8), dp(5));
        hostInput.setSingleLine(true);
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(dp(155), LinearLayout.LayoutParams.WRAP_CONTENT);
        ipLp.rightMargin = dp(6);
        row1.addView(hostInput, ipLp);

        Button lanBtn = makeButton("LAN", "#334155");
        lanBtn.setOnClickListener(v -> discoverLanHost(false));
        row1.addView(lanBtn);

        Button usbBtn = makeButton("USB-C", "#334155");
        usbBtn.setOnClickListener(v -> hostInput.setText("127.0.0.1"));
        row1.addView(usbBtn);

        startBtn = makeButton("Start Stream", "#16A34A");
        startBtn.setOnClickListener(v -> {
            if (streaming.get()) disconnect();
            else startStreamClicked();
        });
        row1.addView(startBtn);

        Button refreshBtn = makeButton("Refresh", "#475569");
        refreshBtn.setOnClickListener(v -> sendControlPacket(TYPE_CLIENT_IDR, null));
        row1.addView(refreshBtn);

        Button hideBtn = makeButton("Hide HUD", "#475569");
        hideBtn.setOnClickListener(v -> hudBar.setVisibility(View.GONE));
        row1.addView(hideBtn);

        Button wakeBtn = makeButton("Wake PC", "#7C3AED");
        wakeBtn.setOnClickListener(v -> wakePc());
        row1.addView(wakeBtn);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = dp(6);
        row2.setLayoutParams(r2Lp);

        resBtn = makeButton("Res: " + RES_LABELS[resIdx], "#1E293B");
        resBtn.setOnClickListener(v -> {
            resIdx = (resIdx + 1) % RES_LABELS.length;
            resBtn.setText("Res: " + RES_LABELS[resIdx]);
            saveStreamPrefs();
            if (streaming.get()) sendClientConfig();
        });
        row2.addView(resBtn);

        fpsBtn = makeButton(cfgFps + " FPS", "#1E293B");
        fpsBtn.setOnClickListener(v -> {
            cfgFps = (cfgFps == 60) ? 90 : 60;
            fpsBtn.setText(cfgFps + " FPS");
            saveStreamPrefs();
            if (streaming.get()) sendClientConfig();
        });
        row2.addView(fpsBtn);

        bitrateBtn = makeButton(BITRATE_OPTIONS[bitrateIdx] + " Mbps", "#1E293B");
        bitrateBtn.setOnClickListener(v -> {
            bitrateIdx = (bitrateIdx + 1) % BITRATE_OPTIONS.length;
            bitrateBtn.setText(BITRATE_OPTIONS[bitrateIdx] + " Mbps");
            saveStreamPrefs();
            if (streaming.get()) sendClientConfig();
        });
        row2.addView(bitrateBtn);

        holdBtn = makeButton("Hold: " + (keepAlive ? "On" : "Off"), "#1E293B");
        holdBtn.setOnClickListener(v -> {
            keepAlive = !keepAlive;
            holdBtn.setText("Hold: " + (keepAlive ? "On" : "Off"));
            getSharedPreferences("gta60", MODE_PRIVATE).edit().putBoolean("keepAlive", keepAlive).apply();
            setStatus(keepAlive
                    ? "Session held: the console will not idle us out while you are away"
                    : "Session hold off: the console may end the stream when idle");
        });
        row2.addView(holdBtn);

        statusText = new TextView(this);
        statusText.setText("Ready — Tap Start Stream (or LAN to auto-find PC)");
        statusText.setTextColor(Color.parseColor("#CBD5E1"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusText.setPadding(dp(8), 0, 0, 0);
        row2.addView(statusText);

        hudBar.addView(row1);
        hudBar.addView(row2);

        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(hudBar, hudParams);

        setContentView(root);
        scheduleTelemetryTick();
        learnPcMac();
        Log.i(TAG, "MainActivity created; default host=" + savedHost);
    }

    private void saveStreamPrefs() {
        getSharedPreferences("gta60", MODE_PRIVATE).edit()
                .putInt("resIdx", resIdx)
                .putInt("cfgFps", cfgFps)
                .putInt("bitrateIdx", bitrateIdx)
                .apply();
    }

    private Button makeButton(String label, String hexBg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(hexBg));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34));
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toggleHud() {
        hudBar.setVisibility(hudBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void setStatus(String text) {
        Log.i(TAG, "Status: " + text);
        uiHandler.post(() -> statusText.setText(text));
    }

    private void startStreamClicked() {
        String h = hostInput.getText().toString().trim();
        if (h.isEmpty()) {
            discoverLanHost(true);
        } else {
            connect(h);
        }
    }

    /**
     * One-shot LAN probe at launch purely to cache the PC's MAC for Wake-on-LAN. Kept separate from
     * the connect flow so it never delays or alters how the stream is started.
     */
    private void learnPcMac() {
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                ds.setSoTimeout(1200);
                final byte[] query = "GTA60_DISCOVER".getBytes(StandardCharsets.US_ASCII);
                sendDiscoveryQuery(ds, query);
                final byte[] buf = new byte[256];
                final DatagramPacket resp = new DatagramPacket(buf, buf.length);
                ds.receive(resp);
                final String msg = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.US_ASCII);
                final String[] parts = msg.split("\\|");
                if (parts.length >= 7 && isValidMac(parts[6])) {
                    final String mac = normalizeMac(parts[6]);
                    getSharedPreferences("gta60", MODE_PRIVATE).edit().putString("pcMac", mac).apply();
                    Log.i(TAG, "Cached PC MAC for Wake-on-LAN: " + mac);
                }
            } catch (Exception ignored) {
                // PC may be off or on another subnet; the Wake PC button will ask for the MAC once.
            }
        }, "GTA60-WolLearn").start();
    }

    private void discoverLanHost(boolean autoConnectAfter) {
        setStatus("Scanning LAN (UDP 7661) for GTA-Sixty PC...");
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                ds.setSoTimeout(1500);
                byte[] query = "GTA60_DISCOVER".getBytes(StandardCharsets.US_ASCII);
                sendDiscoveryQuery(ds, query);

                byte[] buf = new byte[256];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                ds.receive(resp);
                String msg = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.US_ASCII);
                String ip = resp.getAddress().getHostAddress();
                Log.i(TAG, "UDP discovery matched " + ip + " (" + msg + ")");
                // Remember the PC's MAC from the offer so Wake-on-LAN also works once it is powered
                // off and can no longer answer discovery. Format: GTA60_OFFER|port|w|h|fps|codec|MAC
                final String[] parts = msg.split("\\|");
                if (parts.length >= 7 && isValidMac(parts[6])) {
                    final String mac = normalizeMac(parts[6]);
                    getSharedPreferences("gta60", MODE_PRIVATE).edit().putString("pcMac", mac).apply();
                    Log.i(TAG, "Learned PC MAC for Wake-on-LAN: " + mac);
                }
                uiHandler.post(() -> {
                    hostInput.setText(ip);
                    if (autoConnectAfter) {
                        connect(ip);
                    } else {
                        setStatus("Found PC at " + ip + " (" + msg + ")");
                    }
                });
            } catch (Exception e) {
                if (autoConnectAfter) {
                    // Nothing answered, which normally means no streamer is running on the PC. Ask
                    // the agent to start one and keep polling; it answers discovery once the Xbox
                    // session is up. This is the "start it from the headset" path.
                    setStatus("No streamer running — asking the PC to start one...");
                    requestPcAppStart();
                } else {
                    setStatus("LAN discovery timed out — enter PC IP or use USB-C (127.0.0.1)");
                }
            }
        }, "GTA60-Discover").start();
    }

    private void connect(String host) {
        if (host == null || host.isEmpty()) {
            // Never recurse back into discovery from here - just tell the user what to do.
            setStatus("No PC address — tap LAN to scan, or type the PC's IP");
            return;
        }
        // Exactly one pipeline, ever. A second concurrent GTA60-Net thread would open a second TCP
        // session against the PC (a second Xbox session / duplicate "playing remotely" notice) and
        // a second MediaCodec, and with two decoders presenting into the same SurfaceView the panel
        // would flicker between two different frames while paying double decode + network cost.
        if (streaming.getAndSet(true)) {
            Log.w(TAG, "connect(" + host + ") ignored: a stream is already running");
            return;
        }
        getSharedPreferences("gta60", MODE_PRIVATE).edit().putString("host", host).apply();

        final String targetHost = host;
        firstFrameRendered = false;
        pruneOtherApps();
        uiHandler.post(() -> {
            startBtn.setText("Stop");
            startBtn.setBackgroundColor(Color.parseColor("#DC2626"));
        });
        setStatus("Connecting to " + targetHost + ":" + DEFAULT_TCP_PORT + "...");

        netThread = new Thread(() -> runStreamLoop(targetHost), "GTA60-Net");
        netThread.start();
    }

    private void disconnect() {
        streaming.set(false);
        closeSocket();
        releaseCodec();
        releaseAudio();
        uiHandler.post(() -> {
            startBtn.setText("Start Stream");
            startBtn.setBackgroundColor(Color.parseColor("#16A34A"));
        });
        setStatus("Disconnected — Tap Start Stream to reconnect");
    }

    private void closeSocket() {
        // Release the virtual pad before dropping the connection. The console keeps whatever state it
        // last received, and a held button or a displaced pad can cost the physical controller its
        // player-1 slot ("controller works for the Xbox menu but not in-game", notes section 12).
        sendNeutralGamepadBlocking();
        Socket s = socket;
        socket = null;
        synchronized (sendLock) {
            sockOut = null;
        }
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Writes an all-released gamepad frame synchronously, straight to the socket, so it cannot be
     * dropped by the queued control-packet path racing the close.
     */
    private void sendNeutralGamepadBlocking() {
        padButtons = 0;
        padLX = 0; padLY = 0; padRX = 0; padRY = 0;
        padLT = 0; padRT = 0;
        synchronized (sendLock) {
            if (sockOut == null) return;
            final ByteBuffer bb = ByteBuffer.allocate(16 + 16).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(GT60_MAGIC);
            bb.put((byte) TYPE_CLIENT_GAMEPAD);
            bb.put((byte) 0);
            bb.putShort((short) 0);
            bb.putInt(16);
            bb.putInt(0);
            bb.put((byte) 0).put((byte) 0);
            bb.putShort((short) 0).putShort((short) 0).putShort((short) 0)
              .putShort((short) 0).putShort((short) 0).putShort((short) 0)
              .putShort((short) 0);
            try {
                sockOut.write(bb.array());
                sockOut.flush();
                Log.i(TAG, "released virtual gamepad before disconnect");
            } catch (IOException ignored) {
            }
        }
    }

    private byte[] buildClientConfigPayload() {
        int w = RES_WIDTHS[resIdx];
        int h = RES_HEIGHTS[resIdx];
        int mbps = BITRATE_OPTIONS[bitrateIdx];
        ByteBuffer bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(w);
        bb.putInt(h);
        bb.putShort((short) cfgFps);
        bb.putShort((short) mbps);
        return bb.array();
    }

    private void sendClientConfig() {
        Log.i(TAG, String.format("Sending config: %dx%d @ %dfps %d Mbps",
                RES_WIDTHS[resIdx], RES_HEIGHTS[resIdx], cfgFps, BITRATE_OPTIONS[bitrateIdx]));
        sendControlPacket(TYPE_CLIENT_CONFIG, buildClientConfigPayload());
    }

    private void runStreamLoop(String host) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) {}
        try {
            // Nothing needs to be running on the PC when a stream is started: the streamer exits by
            // itself when the console ends the session, leaving only the tiny agent. A refused
            // connection is therefore normal - ask the agent to launch the streamer and keep
            // retrying while it boots (Xbox handshake + encoder init is ~6-8 s).
            final long connectDeadline = System.currentTimeMillis() + 60_000L;
            boolean askedAgent = false;
            Socket s = null;
            while (streaming.get()) {
                try {
                    s = new Socket();
                    s.setTcpNoDelay(true);
                    s.setReceiveBufferSize(128 * 1024);
                    try { s.setTrafficClass(0xB8); } catch (Exception ignored) {}
                    s.connect(new InetSocketAddress(host, DEFAULT_TCP_PORT), 4000);
                    break;
                } catch (IOException ce) {
                    if (s != null) { try { s.close(); } catch (IOException ignored) {} }
                    s = null;
                    if (!askedAgent) {
                        askedAgent = true;
                        Log.i(TAG, "no streamer at " + host + "; asking the PC agent to start one");
                        sendStartRequest();
                    }
                    if (System.currentTimeMillis() > connectDeadline) throw ce;
                    setStatus("Starting the PC streamer...");
                    Thread.sleep(1500);
                }
            }
            if (s == null) return;
            socket = s;
            synchronized (sendLock) {
                sockOut = s.getOutputStream();
            }

            setStatus("Connected to " + host + " — requesting " + RES_LABELS[resIdx] + " " + cfgFps + " FPS...");
            sendClientConfig();
            sendControlPacket(TYPE_CLIENT_IDR, null);

            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
            byte[] hdrBytes = new byte[16];
            ByteBuffer hdrBuf = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] payload = new byte[1024 * 1024];

            while (streaming.get()) {
                in.readFully(hdrBytes);
                hdrBuf.clear();
                int magic = hdrBuf.getInt();
                if (magic != GT60_MAGIC) throw new IOException("Bad GT60 magic");
                int type = hdrBuf.get() & 0xFF;
                int flags = hdrBuf.get() & 0xFF;
                int meta = hdrBuf.getShort() & 0xFFFF;
                int size = hdrBuf.getInt();
                int tsMs = hdrBuf.getInt();

                if (size < 0 || size > 8 * 1024 * 1024) throw new IOException("Invalid payload size: " + size);
                if (payload.length < size) payload = new byte[size];
                if (size > 0) in.readFully(payload, 0, size);

                if (type == TYPE_VIDEO_CONFIG && size > VIDEO_CONFIG_HEADER_BYTES) {
                    ByteBuffer cfg = ByteBuffer.wrap(payload, 0, size).order(ByteOrder.LITTLE_ENDIAN);
                    int w = cfg.getInt();
                    int h = cfg.getInt();
                    int codecId = cfg.get() & 0xFF;
                    byte[] csd = new byte[size - VIDEO_CONFIG_HEADER_BYTES];
                    cfg.get(csd);
                    streamWidth = w;
                    streamHeight = h;
                    targetFps = meta > 0 ? meta : 60;
                    final String mime = (codecId == CODEC_HEVC) ? MIME_HEVC : MIME_AVC;
                    Log.i(TAG, String.format("Received VIDEO_CONFIG %dx%d @ %dfps codec=%s csd=%dB",
                            w, h, targetFps, mime, csd.length));
                    setupDecoder(w, h, csd, mime);
                } else if (type == TYPE_VIDEO_AU && size > 0) {
                    feedDecoder(payload, size, tsMs * 1000L, (flags & FLAG_IDR) != 0);
                    statFrames++;
                    if ((flags & FLAG_GENERATED) != 0) statGenFrames++;
                    statBytes += size;
                } else if (type == TYPE_AUDIO_PCM && size > 0) {
                    playPcmAudio(payload, size);
                }
            }
        } catch (Exception e) {
            if (streaming.get()) {
                Log.w(TAG, "Stream loop error", e);
                setStatus("Stream error: " + e.getMessage());
            }
        } finally {
            streaming.set(false);
            closeSocket();
            releaseCodec();
            releaseAudio();
            uiHandler.post(() -> {
                startBtn.setText("Start Stream");
                startBtn.setBackgroundColor(Color.parseColor("#16A34A"));
            });
        }
    }

    private Surface awaitActiveSurface() {
        long deadline = System.currentTimeMillis() + 2000;
        synchronized (surfaceLock) {
            while (activeSurface == null && streaming.get() && System.currentTimeMillis() < deadline) {
                try {
                    surfaceLock.wait(100);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            return activeSurface;
        }
    }

    /**
     * Split an Annex-B parameter-set blob into its individual NAL units, keeping the start code on
     * each. H.264 gives [SPS][PPS]; HEVC gives [VPS][SPS][PPS]. MediaCodec wants one csd-N buffer per
     * parameter set, while NVENC hands over a single concatenated blob, so this is the adapter.
     */
    private static List<byte[]> splitNalUnits(byte[] blob) {
        final List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 3 <= blob.length; i++) {
            if (blob[i] != 0 || blob[i + 1] != 0) continue;
            if (blob[i + 2] == 1) {
                starts.add(i);
                i += 2;
            } else if (i + 4 <= blob.length && blob[i + 2] == 0 && blob[i + 3] == 1) {
                starts.add(i);
                i += 3;
            }
        }
        final List<byte[]> units = new ArrayList<>();
        if (starts.isEmpty()) {
            units.add(blob);
            return units;
        }
        if (starts.get(0) != 0) starts.add(0, 0);
        for (int k = 0; k < starts.size(); k++) {
            final int from = starts.get(k);
            final int to = (k + 1 < starts.size()) ? starts.get(k + 1) : blob.length;
            if (to > from) units.add(Arrays.copyOfRange(blob, from, to));
        }
        return units;
    }

    /**
     * Build the decoder MediaFormat, including the parameter sets as csd-0..csd-N and the low-latency
     * hints. Kept separate so the vendor-extension fallback can rebuild an identical format.
     */
    private static MediaFormat buildDecoderFormat(String mime, int width, int height, List<byte[]> csd) {
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
        for (int i = 0; i < csd.size() && i < 3; i++) {
            fmt.setByteBuffer("csd-" + i, ByteBuffer.wrap(csd.get(i)));
        }
        if (Build.VERSION.SDK_INT >= 30) {
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
        fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 240);
        return fmt;
    }

    private void setupDecoder(int width, int height, byte[] spsPps, String mime) {
        releaseCodec();
        Surface surf = awaitActiveSurface();
        if (surf == null || !surf.isValid()) {
            Log.e(TAG, "Cannot setup MediaCodec: Surface unavailable");
            setStatus("Decoder error: Surface unavailable");
            return;
        }

        try {
            final List<byte[]> csd = splitNalUnits(spsPps);
            MediaCodec mc = MediaCodec.createDecoderByType(mime);
            MediaFormat fmt = buildDecoderFormat(mime, width, height, csd);

            try {
                fmt.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
                fmt.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
                mc.configure(fmt, surf, null, 0);
            } catch (Exception vendorEx) {
                // The vendor extensions are Qualcomm-specific; retry without them.
                mc.configure(buildDecoderFormat(mime, width, height, csd), surf, null, 0);
            }

            mc.start();
            firstFrameRendered = false;

            int cfgIdx = mc.dequeueInputBuffer(30000);
            if (cfgIdx >= 0) {
                ByteBuffer cfgBuf = mc.getInputBuffer(cfgIdx);
                if (cfgBuf != null) {
                    cfgBuf.clear();
                    int n = Math.min(spsPps.length, cfgBuf.remaining());
                    cfgBuf.put(spsPps, 0, n);
                    mc.queueInputBuffer(cfgIdx, 0, n, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                }
            }

            synchronized (codecLock) {
                codec = mc;
            }

            decodeOutThread = new Thread(() -> drainDecoderLoop(mc), "GTA60-DecOut");
            decodeOutThread.start();

            uiHandler.post(() -> {
                surfaceView.getHolder().setFixedSize(width, height);
                adjustSurfaceAspect(width, height);
                applySurfaceFrameRate(targetFps);
            });
            Log.i(TAG, String.format("MediaCodec started (%s) %dx%d %s csdUnits=%d csd0=%dB",
                    mc.getName(), width, height, mime, csd.size(), csd.isEmpty() ? 0 : csd.get(0).length));
            setStatus(String.format("Streaming %dx%d @ %d fps %s (%s)", width, height, targetFps, mime,
                    mc.getName()));
        } catch (Exception e) {
            Log.e(TAG, "Decoder init failed", e);
            setStatus("Decoder init failed: " + e.getMessage());
        }
    }

    /**
     * Pin the compositor's idea of this layer's frame rate to the real content rate.
     *
     * H.264 timing-info can make the decoder advertise the stream as 30 fps (time_scale/(2*num_units_in_tick)),
     * and when SurfaceFlinger/Horizon believes a layer is 30 fps it paces it at 30 fps and holds each
     * buffer for two refresh intervals instead of presenting it the moment it is decoded. Declaring
     * FIXED_SOURCE at the true rate removes that extra presentation interval and the duplicated holds.
     */
    private void applySurfaceFrameRate(int fps) {
        if (fps <= 0) return;
        Surface s = activeSurface;
        if (s == null || !s.isValid()) return;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (Exception e) {
                Log.w(TAG, "setFrameRate(" + fps + ") failed: " + e.getMessage());
            }
        }
    }

    private void adjustSurfaceAspect(int w, int h) {
        if (w <= 0 || h <= 0) return;
        View parent = (View) surfaceView.getParent();
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;

        float targetAspect = (float) w / (float) h;
        float parentAspect = (float) pw / (float) ph;
        int outW = pw, outH = ph;
        if (parentAspect > targetAspect) {
            outW = Math.round(ph * targetAspect);
        } else {
            outH = Math.round(pw / targetAspect);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(outW, outH, Gravity.CENTER);
        surfaceView.setLayoutParams(lp);
    }

    private void feedDecoder(byte[] data, int size, long ptsUs, boolean isIdr) {
        MediaCodec mc;
        synchronized (codecLock) {
            mc = codec;
        }
        if (mc == null) return;

        long t0 = System.nanoTime();
        try {
            int idx = mc.dequeueInputBuffer(30000);
            if (idx >= 0) {
                ByteBuffer buf = mc.getInputBuffer(idx);
                if (buf != null) {
                    buf.clear();
                    int toCopy = Math.min(size, buf.remaining());
                    if (toCopy < size) {
                        Log.w(TAG, "Truncated AU from " + size + " to " + toCopy + "B");
                    }
                    buf.put(data, 0, toCopy);
                    int flags = isIdr ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mc.queueInputBuffer(idx, 0, toCopy, ptsUs, flags);
                }
            } else {
                Log.w(TAG, "No MediaCodec input buffer available (AU size=" + size + ")");
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "feedDecoder error: " + e.getMessage());
        }
        lastDecodeMs = (System.nanoTime() - t0) / 1e6;
    }

    /**
     * True only while {@code mc} is the single codec allowed to present into the surface.
     * Every decode/render path is gated on this, so a superseded codec can never keep pushing
     * frames into the SurfaceView alongside the current one (which is the only way this
     * single-surface, mono-panel pipeline could ever show two different frames).
     */
    private boolean isActiveCodec(MediaCodec mc) {
        synchronized (codecLock) {
            return codec == mc;
        }
    }

    private void drainDecoderLoop(MediaCodec mc) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Exception ignored) {}
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (streaming.get() && isActiveCodec(mc)) {
            try {
                int outIdx = mc.dequeueOutputBuffer(info, 4000);
                if (outIdx >= 0) {
                    for (;;) {
                        int nextIdx = mc.dequeueOutputBuffer(info, 0);
                        if (nextIdx >= 0) {
                            mc.releaseOutputBuffer(outIdx, false);
                            outIdx = nextIdx;
                        } else {
                            if (nextIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                Log.i(TAG, "MediaCodec output format changed: " + mc.getOutputFormat());
                            }
                            break;
                        }
                    }
                    if (!firstFrameRendered) {
                        firstFrameRendered = true;
                        Log.i(TAG, String.format("First video frame rendered (%dx%d)", streamWidth, streamHeight));
                    }
                    statRendered++;
                    final long presentedPtsUs = info.presentationTimeUs;
                    mc.releaseOutputBuffer(outIdx, System.nanoTime()); // immediate SurfaceFlinger present
                    reportRenderedFrame(presentedPtsUs);
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "MediaCodec output format changed: " + mc.getOutputFormat());
                }
            } catch (IllegalStateException e) {
                break;
            }
        }
    }

    private void releaseCodec() {
        MediaCodec mc;
        synchronized (codecLock) {
            mc = codec;
            codec = null;
        }
        // Retire the decode thread that owned this codec *before* tearing the codec down. Clearing
        // `codec` above makes drainDecoderLoop's isActiveCodec() check fail, so it exits at its next
        // iteration instead of racing a new decoder for the same surface buffer queue.
        final Thread drain = decodeOutThread;
        decodeOutThread = null;
        if (drain != null && drain != Thread.currentThread()) {
            try {
                drain.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mc != null) {
            try { mc.stop(); } catch (Exception ignored) {}
            try { mc.release(); } catch (Exception ignored) {}
        }
    }

    private void playPcmAudio(byte[] pcmBytes, int size) {
        try {
            if (audioTrack == null) {
                int minBuf = AudioTrack.getMinBufferSize(
                        48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack.Builder b = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(48000)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minBuf, 48000 * 4 / 50))
                        .setTransferMode(AudioTrack.MODE_STREAM);
                if (Build.VERSION.SDK_INT >= 26) {
                    b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                }
                audioTrack = b.build();
                audioTrack.play();
            }
            if (Build.VERSION.SDK_INT >= 23) {
                audioTrack.write(pcmBytes, 0, size, AudioTrack.WRITE_NON_BLOCKING);
            } else {
                audioTrack.write(pcmBytes, 0, size);
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioTrack error: " + e.getMessage());
        }
    }

    private void releaseAudio() {
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            try { audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    /**
     * Echo back the timestamp of the access unit we just handed to the compositor. The PC
     * stamped that AU when it encoded it, so (PC receive time - AU timestamp) directly measures the
     * PC->Quest leg: TCP transit + MediaCodec queue + decode-to-present, plus the return hop.
     * Sending it per frame (rather than every N ms) avoids adding sampling error to a number we are
     * going to use to decide where the real latency lives.
     */
    private void reportRenderedFrame(long ptsUs) {
        if (ptsUs <= 0) return;
        final ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) (ptsUs / 1000L));
        sendControlPacket(TYPE_CLIENT_RENDERED, bb.array());
    }

    /**
     * Wake-on-LAN: send the standard magic packet (6x 0xFF followed by the MAC repeated 16x) so the
     * PC can be started from the headset. The PC's MAC is learned automatically from the LAN
     * discovery reply ("GTA60_OFFER|...|MAC"); if it has never been seen it is asked for once and
     * remembered, because a powered-off machine cannot answer discovery.
     */
    private void wakePc() {
        // Ask the PC agent to launch the streaming app regardless of MAC state: the machine may
        // already be awake and simply not running the app.
        requestPcAppStart();
        String mac = getSharedPreferences("gta60", MODE_PRIVATE).getString("pcMac", "");
        if (!isValidMac(mac)) {
            promptForMac();
            return;
        }
        sendWakeOnLan(normalizeMac(mac));
    }

    private void promptForMac() {
        final EditText input = new EditText(this);
        input.setHint("AA:BB:CC:DD:EE:FF");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(getSharedPreferences("gta60", MODE_PRIVATE).getString("pcMac", ""));

        new AlertDialog.Builder(this)
                .setTitle("PC MAC address")
                .setMessage("Enter the MAC of the PC's wired network adapter so the headset can wake it.")
                .setView(input)
                .setPositiveButton("Wake", (d, w) -> {
                    final String typed = input.getText().toString().trim();
                    if (!isValidMac(typed)) {
                        setStatus("Wake-on-LAN: invalid MAC address");
                        return;
                    }
                    final String mac = normalizeMac(typed);
                    getSharedPreferences("gta60", MODE_PRIVATE).edit().putString("pcMac", mac).apply();
                    sendWakeOnLan(mac);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static boolean isValidMac(String mac) {
        if (mac == null) return false;
        final String v = mac.trim().replace("-", "").replace(":", "").replace(" ", "");
        return v.matches("(?i)[0-9a-f]{12}");
    }

    private static String normalizeMac(String mac) {
        final String v = mac.trim().replace("-", "").replace(":", "").replace(" ", "").toUpperCase();
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (sb.length() > 0) sb.append(':');
            sb.append(v, i, i + 2);
        }
        return sb.toString();
    }

    private void sendWakeOnLan(String mac) {
        try {
            final byte[] macBytes = new byte[6];
            final String hex = mac.replace(":", "");
            for (int i = 0; i < 6; ++i) {
                macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            final byte[] packet = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; ++i) packet[i] = (byte) 0xFF;
            for (int i = 0; i < 16; ++i) {
                System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6);
            }

            // Global broadcast plus the LAN's directed broadcast, since some switches/routers drop
            // 255.255.255.255. Both UDP 9 and the legacy port 7 are targeted.
            final List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            final String host = getSharedPreferences("gta60", MODE_PRIVATE).getString("host", "");
            if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                final String[] o = host.split("\\.");
                targets.add(InetAddress.getByName(o[0] + "." + o[1] + "." + o[2] + ".255"));
            }

            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                for (InetAddress target : targets) {
                    for (int port : new int[]{9, 7}) {
                        ds.send(new DatagramPacket(packet, packet.length, target, port));
                    }
                }
            }
            Log.i(TAG, "Wake-on-LAN sent to " + mac + " via " + targets);
            setStatus("Wake-on-LAN sent to " + mac + " — PC should boot in a few seconds");
        } catch (Exception e) {
            Log.w(TAG, "Wake-on-LAN failed: " + e.getMessage());
            setStatus("Wake-on-LAN failed: " + e.getMessage());
        }
    }

    /**
     * Clear background copies of other installed apps before we start decoding. A leftover VR app
     * (we have seen one hold the OpenXR runtime open) competes for the compositor, GPU and decoder.
     * Android will not let a normal app kill a *foreground* app, but KILL_BACKGROUND_PROCESSES
     * covers the stuck-in-background case, which is what actually bites.
     */
    private void pruneOtherApps() {
        new Thread(() -> {
            try {
                final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am == null) return;
                final PackageManager pm = getPackageManager();
                final String self = getPackageName();
                int cleared = 0;
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai.packageName.equals(self)) continue;
                    // FLAG_SYSTEM means a preinstalled package; only touch user-installed apps.
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    am.killBackgroundProcesses(ai.packageName);
                    ++cleared;
                }
                Log.i(TAG, "Cleared background processes for " + cleared + " third-party app(s)");
            } catch (Exception e) {
                Log.w(TAG, "pruneOtherApps failed: " + e.getMessage());
            }
        }, "GTA60-Prune").start();
    }

    /**
     * Ask the PC-side agent to launch the streaming app. Wake-on-LAN can power the machine on, but
     * something already running on it has to start the streamer, so the agent listens on UDP 7662.
     * Also polls discovery, so this works whether the PC was asleep, off, or already streaming.
     */
    /**
     * Fire-and-forget: ask the PC agent on UDP 7662 to launch the streamer. The agent is the only
     * thing expected to be running on the PC when nothing is streaming.
     */
    private void sendStartRequest() {
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                final byte[] cmd = "GTA60_START".getBytes(StandardCharsets.US_ASCII);
                for (InetAddress t : discoveryTargets()) {
                    try { ds.send(new DatagramPacket(cmd, cmd.length, t, START_UDP_PORT)); } catch (Exception ignored) {}
                }
                Log.i(TAG, "sent GTA60_START to the PC agent");
            } catch (Exception e) {
                Log.w(TAG, "could not send GTA60_START: " + e.getMessage());
            }
        }, "GTA60-StartPc").start();
    }

    private void requestPcAppStart() {
        new Thread(() -> {
            final byte[] startCmd = "GTA60_START".getBytes(StandardCharsets.US_ASCII);
            final byte[] discover = "GTA60_DISCOVER".getBytes(StandardCharsets.US_ASCII);
            final long deadline = System.currentTimeMillis() + 90_000L;
            // The old loop was gated on streaming.get(), which is false precisely when this needs to
            // run, so the Wake PC chain never actually broadcast anything. Poll until connected.
            boolean startSent = false;
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                ds.setSoTimeout(1200);
                while (!streaming.get() && System.currentTimeMillis() < deadline) {
                    final List<InetAddress> targets = discoveryTargets();
                    for (InetAddress t : targets) {
                        // Send GTA60_START exactly once: the agent needs ~7 s to bring the streamer
                        // up, and a repeat request in that window makes it launch a second one.
                        if (!startSent) {
                            try { ds.send(new DatagramPacket(startCmd, startCmd.length, t, START_UDP_PORT)); } catch (Exception ignored) {}
                        }
                        try { ds.send(new DatagramPacket(discover, discover.length, t, DISCOVERY_UDP_PORT)); } catch (Exception ignored) {}
                    }
                    startSent = true;
                    try {
                        final byte[] buf = new byte[256];
                        final DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        ds.receive(resp);
                        final String msg = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.US_ASCII);
                        if (msg.startsWith("GTA60_OFFER")) {
                            final String ip = resp.getAddress().getHostAddress();
                            Log.i(TAG, "PC streaming app answered at " + ip);
                            uiHandler.post(() -> {
                                hostInput.setText(ip);
                                setStatus("PC app ready at " + ip);
                                if (!streaming.get()) connect(ip);
                            });
                            break;
                        }
                    } catch (Exception ignored) {
                        // keep polling until the deadline
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "start request failed: " + e.getMessage());
            }
        }, "GTA60-StartPc").start();
    }

    /**
     * Every address worth probing for the PC.
     *
     * 255.255.255.255 is frequently dropped by Android or the Wi-Fi driver - and on a first run
     * there is no remembered host to derive a subnet from, so probing only the global broadcast
     * means discovery can never succeed on a fresh install. The directed broadcast of each live
     * interface is the address that actually arrives, so probe those too (plus the /24 of the last
     * known PC, which still helps when the interface list is unhelpful).
     */
    private List<InetAddress> discoveryTargets() {
        final List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    final InetAddress addr = ia.getAddress();
                    if (addr == null || addr.getAddress() == null || addr.getAddress().length != 4) continue;
                    final int prefix = ia.getNetworkPrefixLength();
                    if (prefix < 1 || prefix > 30) continue;
                    final byte[] b = addr.getAddress();
                    final int ip = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) |
                            ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                    final int broadcast = ip | ~(0xFFFFFFFF << (32 - prefix));
                    final byte[] bb = {(byte) (broadcast >>> 24), (byte) (broadcast >>> 16),
                            (byte) (broadcast >>> 8), (byte) broadcast};
                    final InetAddress directed = InetAddress.getByAddress(bb);
                    if (!targets.contains(directed)) targets.add(directed);
                }
            }
        } catch (Exception ignored) {
        }
        final String known = getSharedPreferences("gta60", MODE_PRIVATE).getString("host", "");
        if (known != null && known.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            try {
                final String[] o = known.split("\\.");
                final InetAddress remembered = InetAddress.getByName(o[0] + "." + o[1] + "." + o[2] + ".255");
                if (!targets.contains(remembered)) targets.add(remembered);
            } catch (Exception ignored) {
            }
        }
        return targets;
    }

    private void sendDiscoveryQuery(DatagramSocket ds, byte[] query) {
        for (InetAddress target : discoveryTargets()) {
            try {
                ds.send(new DatagramPacket(query, query.length, target, DISCOVERY_UDP_PORT));
            } catch (Exception ignored) {
            }
        }
    }

    private void sendControlPacket(int type, byte[] payload) {
        final int len = payload == null ? 0 : payload.length;
        final ByteBuffer bb = ByteBuffer.allocate(16 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(GT60_MAGIC);
        bb.put((byte) type);
        bb.put((byte) 0);
        bb.putShort((short) 0);
        bb.putInt(len);
        bb.putInt(0);
        if (payload != null) bb.put(payload);

        sendExecutor.execute(() -> {
            synchronized (sendLock) {
                if (sockOut != null) {
                    try {
                        sockOut.write(bb.array());
                        sockOut.flush();
                    } catch (IOException ignored) {}
                }
            }
        });
    }

    private void sendGamepadState() {
        if (!streaming.get()) return;
        ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.putShort((short) padButtons);
        bb.putShort(padLX);
        bb.putShort(padLY);
        bb.putShort(padRX);
        bb.putShort(padRY);
        bb.putShort((short) padLT);
        bb.putShort((short) padRT);
        lastGamepadSendMs = System.currentTimeMillis();
        sendControlPacket(TYPE_CLIENT_GAMEPAD, bb.array());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            int mask = mapGamepadButton(event.getKeyCode());
            if (mask != 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) padButtons |= mask;
                else if (event.getAction() == KeyEvent.ACTION_UP) padButtons &= ~mask;
                sendGamepadState();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                ev.getAction() == MotionEvent.ACTION_MOVE) {
            short lx = axisToShort(ev.getAxisValue(MotionEvent.AXIS_X));
            short ly = axisToShort(-ev.getAxisValue(MotionEvent.AXIS_Y));
            short rx = axisToShort(ev.getAxisValue(MotionEvent.AXIS_Z));
            short ry = axisToShort(-ev.getAxisValue(MotionEvent.AXIS_RZ));
            int lt = Math.round(Math.max(0f, Math.min(1f, ev.getAxisValue(MotionEvent.AXIS_LTRIGGER))) * 65535f);
            int rt = Math.round(Math.max(0f, Math.min(1f, ev.getAxisValue(MotionEvent.AXIS_RTRIGGER))) * 65535f);
            if (lx != padLX || ly != padLY || rx != padRX || ry != padRY || lt != padLT || rt != padRT) {
                padLX = lx;
                padLY = ly;
                padRX = rx;
                padRY = ry;
                padLT = lt;
                padRT = rt;
                sendGamepadState();
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private static short axisToShort(float v) {
        float c = Math.max(-1f, Math.min(1f, v));
        return (short) Math.round(c * 32767f);
    }

    private static int mapGamepadButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 16;
            case KeyEvent.KEYCODE_BUTTON_B: return 32;
            case KeyEvent.KEYCODE_BUTTON_X: return 64;
            case KeyEvent.KEYCODE_BUTTON_Y: return 128;
            case KeyEvent.KEYCODE_DPAD_UP: return 256;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 512;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 1024;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 2048;
            case KeyEvent.KEYCODE_BUTTON_L1: return 4096;
            case KeyEvent.KEYCODE_BUTTON_R1: return 8192;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 16384;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 32768;
            case KeyEvent.KEYCODE_BUTTON_START: return 4;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 8;
            case KeyEvent.KEYCODE_BUTTON_MODE: return 2;
            default: return 0;
        }
    }

    private void scheduleTelemetryTick() {
        lastStatTimeMs = System.currentTimeMillis();
        lastPerfLogTimeMs = lastStatTimeMs;
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                double dt = (now - lastStatTimeMs) / 1000.0;
                // Keep the console from timing the session out. Repeating the *current* pad state is
                // a no-op for the game (same buttons, same axes), it only proves the player is still
                // there. See the Hold button and notes 10.5.
                if (streaming.get() && keepAlive && (now - lastGamepadSendMs) > 20_000L) {
                    sendGamepadState();
                }
                if (streaming.get() && dt >= 0.5 && streamWidth > 0) {
                    long f = statFrames;
                    long r = statRendered;
                    long g = statGenFrames;
                    long b = statBytes;
                    statFrames = 0;
                    statRendered = 0;
                    statGenFrames = 0;
                    statBytes = 0;
                    lastStatTimeMs = now;
                    double recvFps = f / dt;
                    double renFps = r / dt;
                    double mbps = (b * 8.0) / (dt * 1e6);
                    long genPct = f > 0 ? Math.round((g * 100.0) / f) : 0;
                    String hudMsg = String.format("%dx%d @ %.1f/%.1f fps (Gen %d%%) | %.1f Mbps | Feed %.2f ms",
                            streamWidth, streamHeight, renFps, recvFps, genPct, mbps, lastDecodeMs);
                    statusText.setText(hudMsg);
                    if (now - lastPerfLogTimeMs >= 2000) {
                        lastPerfLogTimeMs = now;
                        Log.i(TAG_PERF, hudMsg);
                    }
                }
                uiHandler.postDelayed(this, 500);
            }
        }, 500);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            activeSurface = holder.getSurface();
            surfaceLock.notifyAll();
        }
        if (!autoStartTriggered) {
            autoStartTriggered = true;
            Log.i(TAG, "Surface created; auto-starting stream to " + hostInput.getText().toString().trim());
            uiHandler.post(() -> {
                if (!streaming.get()) startStreamClicked();
            });
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (surfaceLock) {
            activeSurface = holder.getSurface();
            surfaceLock.notifyAll();
        }
        if (streamWidth > 0 && streamHeight > 0) {
            adjustSurfaceAspect(streamWidth, streamHeight);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            activeSurface = null;
            surfaceLock.notifyAll();
        }
        disconnect();
    }

    @Override
    protected void onDestroy() {
        disconnect();
        sendExecutor.shutdownNow();
        super.onDestroy();
    }
}

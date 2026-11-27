package com.gtasixty.spatial;

import java.util.ArrayList;
import java.util.List;

/**
 * JNI wrapper around {@code libgtaps.so} (compiled from {@code chiaki-ng}'s {@code libchiaki}).
 */
public final class PsChiakiNative {

    public static final int CODEC_H264 = 0;
    public static final int CODEC_H265 = 1;

    // ChiakiEventType values from lib/include/chiaki/session.h
    public static final int EVENT_CONNECTED = 0;
    public static final int EVENT_LOGIN_PIN_REQUEST = 1;
    public static final int EVENT_NICKNAME_RECEIVED = 4;
    public static final int EVENT_RUMBLE = 8;
    public static final int EVENT_QUIT = 9;

    // ChiakiDiscoveryHostState values from lib/include/chiaki/discovery.h
    public static final int HOST_STATE_UNKNOWN = 0;
    public static final int HOST_STATE_READY = 1;
    public static final int HOST_STATE_STANDBY = 2;

    public interface SessionCallback {
        boolean onVideoSample(byte[] data, int size, int framesLost, boolean frameRecovered);
        void onAudioSettings(int channels, int rate);
        void onAudioPcm(byte[] pcm, int byteLength);
        void onEvent(int eventType, int arg0, int arg1, String strArg);
    }

    public static final class DiscoveredConsole {
        public final String hostAddr;
        public final String hostName;
        public final String hostType;
        public final int state;
        public final String hostId;
        public final String systemVersion;
        public final boolean isPs5;
        public final String runningApp;

        public DiscoveredConsole(
                String hostAddr,
                String hostName,
                String hostType,
                int state,
                String hostId,
                String systemVersion,
                boolean isPs5,
                String runningApp) {
            this.hostAddr = hostAddr;
            this.hostName = hostName;
            this.hostType = hostType;
            this.state = state;
            this.hostId = hostId;
            this.systemVersion = systemVersion;
            this.isPs5 = isPs5;
            this.runningApp = runningApp;
        }

        public String stateLabel() {
            if (state == HOST_STATE_READY) return "Ready";
            if (state == HOST_STATE_STANDBY) return "Rest Mode";
            return "Unknown";
        }
    }

    public static final class RegistResult {
        public final boolean ok;
        public final String error;
        public final int target;
        public final String nickname;
        public final String macHex;
        public final String registKeyHex;
        public final String morningHex;

        private RegistResult(
                boolean ok,
                String error,
                int target,
                String nickname,
                String macHex,
                String registKeyHex,
                String morningHex) {
            this.ok = ok;
            this.error = error;
            this.target = target;
            this.nickname = nickname;
            this.macHex = macHex;
            this.registKeyHex = registKeyHex;
            this.morningHex = morningHex;
        }

        public static RegistResult success(
                int target, String nickname, String macHex, String registKeyHex, String morningHex) {
            return new RegistResult(true, "", target, nickname, macHex, registKeyHex, morningHex);
        }

        public static RegistResult error(String msg) {
            return new RegistResult(false, msg == null ? "Registration failed" : msg, 0, "", "", "", "");
        }
    }

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = "";
        try {
            System.loadLibrary("gtaps");
            ok = nativeInit();
            if (!ok) err = "chiaki_lib_init failed";
        } catch (UnsatisfiedLinkError | SecurityException e) {
            err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        LOADED = ok;
        LOAD_ERROR = err;
    }

    private PsChiakiNative() {}

    public static boolean isAvailable() {
        return LOADED;
    }

    public static String getLoadError() {
        return LOAD_ERROR;
    }

    public static List<DiscoveredConsole> parseDiscoveryResults(String[] lines) {
        final List<DiscoveredConsole> out = new ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            final String[] p = line.split("\\|", -1);
            if (p.length < 7 || p[0].isEmpty()) continue;
            int state = HOST_STATE_UNKNOWN;
            try {
                state = Integer.parseInt(p[3]);
            } catch (NumberFormatException ignored) {
            }
            final boolean ps5 = "1".equals(p[6]) || "PS5".equalsIgnoreCase(p[2]);
            final String app = p.length >= 8 ? p[7] : "";
            out.add(new DiscoveredConsole(p[0], p[1], p[2], state, p[4], p[5], ps5, app));
        }
        return out;
    }

    public static RegistResult parseRegistrationResult(String raw) {
        if (raw == null || raw.isEmpty()) {
            return RegistResult.error("Empty registration response");
        }
        final String[] p = raw.split("\\|", -1);
        if (p.length >= 6 && "OK".equals(p[0])) {
            int target = 1000100;
            try {
                target = Integer.parseInt(p[1]);
            } catch (NumberFormatException ignored) {
            }
            return RegistResult.success(target, p[2], p[3], p[4], p[5]);
        }
        if (p.length >= 2 && "ERR".equals(p[0])) {
            return RegistResult.error(p[1]);
        }
        return RegistResult.error(raw);
    }

    public static native boolean nativeInit();

    public static native String[] nativeDiscoverConsoles(String[] broadcastAddrs, int timeoutMs);

    public static native int nativeWakeConsole(String host, long registKeyU64, boolean isPs5);

    public static native String nativeRegisterConsole(
            String host,
            boolean isPs5,
            byte[] psnAccountId,
            String psnOnlineId,
            int pin,
            int consolePin);

    public static native long nativeSessionStart(
            String host,
            boolean isPs5,
            byte[] registKey16,
            byte[] morning16,
            int width,
            int height,
            int fps,
            int bitrateKbps,
            int codecId,
            SessionCallback callback);

    public static native void nativeSessionSetControllerState(
            long handle,
            int buttons,
            int l2,
            int r2,
            short lx,
            short ly,
            short rx,
            short ry,
            boolean touchDown,
            int touchX,
            int touchY);

    public static native void nativeSessionSetLoginPin(long handle, String pin);

    public static native void nativeSessionRequestIdr(long handle);

    public static native void nativeSessionStop(long handle);
}

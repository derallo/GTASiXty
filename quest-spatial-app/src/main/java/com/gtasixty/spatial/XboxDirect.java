package com.gtasixty.spatial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * Direct-to-console xhome client: Microsoft sign-in, console discovery, session provisioning and the
 * SDP/ICE exchange. The actual WebRTC media lives in the WebView page (assets/direct), because
 * Chromium already ships a complete WebRTC stack - see the notes in DIRECT_MODE.md.
 *
 * <p>Everything here is a Java port of the proven PC path in src/xbox/{Account,StreamApi}.cpp and
 * deliberately uses the same client id, scopes, device info and REST bodies: the console accepted
 * those, so they are not up for creative reinterpretation.
 *
 * <p>All methods are blocking; callers must use a background thread.
 */
final class XboxDirect {
    private static final String TAG = "GTA60Direct";

    // Public MSA client id used by the Xbox app / Greenlight. This is not a secret.
    private static final String CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e";
    private static final String SCOPE = "xboxlive.signin openid profile offline_access";
    private static final String TRANSFER_SCOPE =
            "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN";
    private static final String XHOME_LOGIN = "https://xhome.gssv-play-prod.xboxlive.com";

    // Match the proven PC path (src/xbox/StreamApi.cpp) byte-for-byte.
    private static final String NATIVE_DEVICE_INFO =
            "{\"appInfo\":{\"env\":{\"clientAppId\":\"Microsoft.GamingApp\",\"clientAppType\":\"native\","
                    + "\"clientAppVersion\":\"2203.1001.4.0\",\"clientSdkVersion\":\"8.5.2\","
                    + "\"httpEnvironment\":\"prod\",\"sdkInstallId\":\"\"}},"
                    + "\"dev\":{\"hw\":{\"make\":\"Microsoft\",\"model\":\"Surface Pro\",\"sdktype\":\"native\"},"
                    + "\"os\":{\"name\":\"Windows 11\",\"ver\":\"22631.2715\",\"platform\":\"desktop\"},"
                    + "\"displayInfo\":{\"dimensions\":{\"widthInPixels\":1920,\"heightInPixels\":1080},"
                    + "\"pixelDensity\":{\"dpiX\":1,\"dpiY\":1}}}}";

    private static final String PREFS = "gta60_direct";
    private static final String KEY_ALIAS = "gta60-xbox-direct-v1";
    private static final String KEY_BLOB = "tokens";
    private static final String KEY_ACTIVE_SESSION = "activeSessionId";

    static final class Console {
        String serverId = "";
        String name = "";
        String powerState = "";
        String consoleType = "";
    }

    static final class DeviceCode {
        String userCode = "";
        String deviceCode = "";
        String verificationUri = "https://www.microsoft.com/link";
        int intervalSeconds = 5;
        int expiresInSeconds = 900;
    }

    private static final class Response {
        int status;
        String body = "";
    }

    private final SharedPreferences prefs;
    private final Context context;

    private String refreshToken = "";
    private String accessToken = "";
    private long accessExpiresAt = 0;
    private String userHash = "";

    private String userToken = "";
    private long userTokenExpiresAt = 0;
    private String gsToken = "";
    private String gsHost = "";
    private long gsExpiresAt = 0;

    private String accountHint = "";

    XboxDirect(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    boolean signedIn() {
        return !refreshToken.isEmpty();
    }

    String accountHint() {
        return accountHint;
    }

    synchronized void signOut() {
        refreshToken = accessToken = userToken = gsToken = gsHost = "";
        accessExpiresAt = userTokenExpiresAt = gsExpiresAt = 0;
        prefs.edit().remove(KEY_BLOB).apply();
    }

    // ---------------------------------------------------------------- sign-in

    synchronized DeviceCode beginSignIn() throws Exception {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", SCOPE);
        final Response r = request("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                encodeForm(form), contentType("application/x-www-form-urlencoded"), false);
        final JSONObject j = json(r, "device code request");
        final DeviceCode code = new DeviceCode();
        code.userCode = j.optString("user_code", "");
        code.deviceCode = j.optString("device_code", "");
        code.verificationUri = j.optString("verification_uri", code.verificationUri);
        code.intervalSeconds = Math.max(1, j.optInt("interval", 5));
        code.expiresInSeconds = j.optInt("expires_in", 900);
        if (code.userCode.isEmpty() || code.deviceCode.isEmpty()) {
            throw new IOException("device code request returned no code");
        }
        return code;
    }

    /** Returns true when the user has finished signing in, false while still pending. */
    synchronized boolean pollSignIn(DeviceCode code) throws Exception {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        form.put("client_id", CLIENT_ID);
        form.put("device_code", code.deviceCode);
        final Response r = request("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                encodeForm(form), contentType("application/x-www-form-urlencoded"), false);
        JSONObject j;
        try {
            j = new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("sign-in returned something that is not JSON (HTTP " + r.status + ")");
        }
        if (r.status >= 200 && r.status <= 299) {
            final String refresh = j.optString("refresh_token", "");
            final String access = j.optString("access_token", "");
            if (refresh.isEmpty() || access.isEmpty()) throw new IOException("sign-in returned no tokens");
            refreshToken = refresh;
            accessToken = access;
            accessExpiresAt = now() + j.optInt("expires_in", 3600);
            userToken = gsToken = gsHost = "";
            userTokenExpiresAt = gsExpiresAt = 0;
            save();
            // Best effort: the UHS only labels the account, it is not required for streaming.
            try {
                accountHint = xstsUserToken();
                final int dot = accountHint.lastIndexOf('.');
                accountHint = dot > 0 ? "Xbox account (token ok)" : "Xbox account";
            } catch (Exception ignored) {
                accountHint = "Xbox account";
            }
            return true;
        }
        final String error = j.optString("error", "");
        if ("authorization_pending".equals(error)) return false;
        if ("slow_down".equals(error)) {
            code.intervalSeconds += 5;
            return false;
        }
        if ("expired_token".equals(error) || "code_expired".equals(error)) {
            throw new IOException("the sign-in code expired; start again");
        }
        if ("authorization_declined".equals(error) || "access_denied".equals(error)) {
            throw new IOException("sign-in was declined");
        }
        throw new IOException("sign-in failed: " + (error.isEmpty() ? "HTTP " + r.status : error));
    }

    private String userAccessToken() throws Exception {
        if (refreshToken.isEmpty()) throw new IOException("not signed in");
        if (!accessToken.isEmpty() && accessExpiresAt - 60 > now()) return accessToken;
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("scope", SCOPE);
        final Response r = request("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                encodeForm(form), contentType("application/x-www-form-urlencoded"), false);
        if (r.status == 400 || r.status == 401) {
            signOut();
            throw new IOException("sign-in expired; sign in again");
        }
        final JSONObject j = json(r, "token refresh");
        accessToken = j.optString("access_token", "");
        accessExpiresAt = now() + j.optInt("expires_in", 3600);
        final String rotated = j.optString("refresh_token", "");
        if (!rotated.isEmpty()) refreshToken = rotated;
        save();
        if (accessToken.isEmpty()) throw new IOException("token refresh returned no access token");
        return accessToken;
    }

    private String xstsUserToken() throws Exception {
        if (!userToken.isEmpty() && userTokenExpiresAt - 60 > now()) return userToken;
        final JSONObject body = new JSONObject();
        body.put("Properties", new JSONObject()
                .put("AuthMethod", "RPS")
                .put("RpsTicket", "d=" + userAccessToken())
                .put("SiteName", "user.auth.xboxlive.com"));
        body.put("RelyingParty", "http://auth.xboxlive.com");
        body.put("TokenType", "JWT");
        final Response r = request("POST", "https://user.auth.xboxlive.com/user/authenticate", body.toString(),
                xblHeaders(), false);
        final JSONObject j = json(r, "Xbox Live user authentication");
        userToken = j.optString("Token", "");
        userTokenExpiresAt = parseTime(j.optString("NotAfter", ""));
        if (userTokenExpiresAt == 0) userTokenExpiresAt = now() + 3600;
        try {
            userHash = j.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).optString("uhs", userHash);
        } catch (Exception ignored) {
        }
        if (userToken.isEmpty()) throw new IOException("Xbox Live user authentication returned no token");
        return userToken;
    }

    private void ensureStreaming() throws Exception {
        if (!gsToken.isEmpty() && !gsHost.isEmpty() && gsExpiresAt - 120 > now()) return;

        final JSONObject authorize = new JSONObject();
        authorize.put("Properties", new JSONObject()
                .put("SandboxId", "RETAIL")
                .put("UserTokens", new JSONArray().put(xstsUserToken())));
        authorize.put("RelyingParty", "http://gssv.xboxlive.com/");
        authorize.put("TokenType", "JWT");
        final Response xr = request("POST", "https://xsts.auth.xboxlive.com/xsts/authorize", authorize.toString(),
                xblHeaders(), false);
        final JSONObject xj = json(xr, "Xbox Live authorization");
        final String gssv = xj.optString("Token", "");
        if (gssv.isEmpty()) throw new IOException("Xbox Live authorization returned no token");

        final JSONObject login = new JSONObject().put("token", gssv).put("offeringId", "xhome");
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Cache-Control", "no-store, must-revalidate, no-cache");
        headers.put("x-gssv-client", "XboxComBrowser");
        final Response lr = request("POST", XHOME_LOGIN + "/v2/login/user", login.toString(), headers, false);
        final JSONObject lj = json(lr, "streaming sign-in");
        gsToken = lj.optString("gsToken", "");
        gsExpiresAt = now() + lj.optLong("durationInSeconds", 3600);
        gsHost = "";
        try {
            final JSONArray regions = lj.getJSONObject("offeringSettings").getJSONArray("regions");
            for (int i = 0; i < regions.length(); i++) {
                final JSONObject region = regions.getJSONObject(i);
                final boolean isDefault = region.optBoolean("isDefault", false);
                if (!gsHost.isEmpty() && !isDefault) continue;
                String uri = region.optString("baseUri", "");
                if (uri.startsWith("https://")) uri = uri.substring(8);
                while (uri.endsWith("/")) uri = uri.substring(0, uri.length() - 1);
                gsHost = uri;
                if (isDefault) break;
            }
        } catch (Exception ignored) {
        }
        if (gsToken.isEmpty() || gsHost.isEmpty()) {
            throw new IOException("streaming sign-in returned no region or token");
        }
    }

    private String transferToken() throws Exception {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", TRANSFER_SCOPE);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        final Response r = request("POST", "https://login.live.com/oauth20_token.srf", encodeForm(form),
                contentType("application/x-www-form-urlencoded"), false);
        final JSONObject j = json(r, "console transfer token");
        final String token = j.optString("access_token", "");
        if (token.isEmpty()) throw new IOException("console transfer token missing");
        return token;
    }

    // ---------------------------------------------------------------- streaming

    synchronized List<Console> consoles() throws Exception {
        ensureStreaming();
        final Response r = api("GET", "/v6/servers/home", null);
        final List<Console> out = new ArrayList<>();
        try {
            final JSONArray results = json(r, "console list").optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    final JSONObject c = results.getJSONObject(i);
                    final Console console = new Console();
                    console.serverId = c.optString("serverId", "");
                    console.name = c.optString("deviceName", "");
                    console.powerState = c.optString("powerState", "");
                    console.consoleType = c.optString("consoleType", "");
                    if (!console.serverId.isEmpty()) out.add(console);
                }
            }
        } catch (Exception e) {
            throw new IOException("console list is not JSON", e);
        }
        return out;
    }

    synchronized String getPersistedSession() {
        final String id = prefs.getString(KEY_ACTIVE_SESSION, "");
        return id == null ? "" : id;
    }

    synchronized void persistActiveSession(String id) {
        if (id == null || id.isEmpty()) return;
        prefs.edit().putString(KEY_ACTIVE_SESSION, id).commit();
    }

    synchronized void clearPersistedSession(String id) {
        final String current = getPersistedSession();
        if (current.isEmpty()) return;
        if (id == null || id.equals(current)) {
            prefs.edit().remove(KEY_ACTIVE_SESSION).apply();
        }
    }

    synchronized void cleanupPersistedSession() {
        final String staleId = getPersistedSession();
        if (staleId.isEmpty()) return;
        if (!signedIn()) {
            clearPersistedSession(staleId);
            return;
        }
        Log.i(TAG, "Cleaning up persisted orphaned Direct session: " + staleId);
        stop(staleId);
        clearPersistedSession(staleId);
    }

    synchronized String play(String serverId) throws Exception {
        ensureStreaming();
        if (!getPersistedSession().isEmpty()) {
            cleanupPersistedSession();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        final JSONObject settings = new JSONObject()
                .put("nanoVersion", "V3;WebrtcTransport.dll")
                .put("enableTextToSpeech", false)
                .put("highContrast", 0)
                .put("locale", "en-US")
                .put("useIceConnection", false)
                .put("timezoneOffsetMinutes", 300)
                .put("sdkType", "web")
                .put("osName", "windows");
        final JSONObject body = new JSONObject()
                .put("titleId", "")
                .put("systemUpdateGroup", "")
                .put("clientSessionId", "")
                .put("settings", settings)
                .put("serverId", serverId)
                .put("fallbackRegionNames", new JSONArray());
        final String bodyText = body.toString();

        // The console allows one streaming session at a time. If the PC streamer (or a crashed
        // client) is still holding it, play returns HTTP 409 with "Exclusive operation already in
        // progress" while the console finishes ServerStartStreamingV2CommandSent. That clears on its
        // own after the other side tears down, so wait for it instead of surfacing a dead end.
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                final Response r = api("POST", "/v5/sessions/home/play", bodyText, true);
                final JSONObject j = json(r, "play");
                final String path = j.optString("sessionPath", "");
                final int slash = path.lastIndexOf('/');
                final String id = slash < 0 ? path : path.substring(slash + 1);
                if (id.isEmpty()) throw new IOException("play returned no session");
                persistActiveSession(id);
                return id;
            } catch (IOException e) {
                last = e;
                if (!exclusive(e.getMessage())) throw e;
                Log.w(TAG, "console still busy with another session; retrying in 3 s ("
                        + (attempt + 1) + "/20): " + e.getMessage());
                Thread.sleep(3000);
            }
        }
        throw new IOException("the console is still holding another streaming session "
                + "(exclusive operation). Stop the PC streamer, then retry direct mode.", last);
    }

    private static boolean exclusive(String message) {
        if (message == null) return false;
        final String m = message.toLowerCase(java.util.Locale.US);
        return m.contains("409") || m.contains("exclusive") || m.contains("already in progress");
    }

    /** Returns Provisioned / ReadyToConnect / Failed / ... (empty on a transient response). */
    synchronized String state(String sessionId) throws Exception {
        ensureStreaming();
        final Response r = api("GET", "/v5/sessions/home/" + safeId(sessionId) + "/state", null);
        final JSONObject j = json(r, "session state");
        final String st = j.optString("state", "");
        if ("Failed".equals(st) && j.optJSONObject("errorDetails") != null) {
            final JSONObject e = j.optJSONObject("errorDetails");
            throw new IOException("session failed: " + e.optString("code", "") + " " + e.optString("message", ""));
        }
        return st;
    }

    synchronized void connect(String sessionId) throws Exception {
        ensureStreaming();
        final JSONObject body = new JSONObject().put("userToken", transferToken());
        api("POST", "/v5/sessions/home/" + safeId(sessionId) + "/connect", body.toString());
    }

    /** Offers our SDP and returns the console's answer. */
    synchronized String exchangeSdp(String sessionId, String offerSdp) throws Exception {
        ensureStreaming();
        final JSONObject configuration = new JSONObject()
                .put("chatConfiguration", new JSONObject()
                        .put("bytesPerSample", 2)
                        .put("expectedClipDurationMs", 20)
                        .put("format", new JSONObject().put("codec", "opus").put("container", "webm"))
                        .put("numChannels", 1)
                        .put("sampleFrequencyHz", 24000))
                .put("chat", new JSONObject().put("minVersion", 1).put("maxVersion", 1))
                .put("control", new JSONObject().put("minVersion", 1).put("maxVersion", 3))
                .put("input", new JSONObject().put("minVersion", 1).put("maxVersion", 8))
                .put("message", new JSONObject().put("minVersion", 1).put("maxVersion", 1));
        final JSONObject body = new JSONObject()
                .put("messageType", "offer")
                .put("sdp", offerSdp)
                .put("configuration", configuration);
        final String path = "/v5/sessions/home/" + safeId(sessionId) + "/sdp";
        api("POST", path, body.toString());
        final JSONObject answer = new JSONObject(pollExchange(path));
        final String sdp = answer.optString("sdp", "");
        if (sdp.isEmpty()) {
            throw new IOException("the console returned no SDP (" + answer.optString("status", "no reason given") + ")");
        }
        return sdp;
    }

    /**
     * Sends our ICE candidates (already rewritten to real LAN addresses) and returns the console's
     * candidates, with Teredo addresses expanded into the synthetic host candidates that work on a
     * local network.
     */
    synchronized JSONArray exchangeIce(String sessionId, JSONArray candidates) throws Exception {
        ensureStreaming();
        final JSONObject body = new JSONObject()
                .put("messageType", "iceCandidate")
                .put("candidate", candidates);
        final String path = "/v5/sessions/home/" + safeId(sessionId) + "/ice";
        api("POST", path, body.toString());
        final String exchange = pollExchange(path);
        final String trimmed = exchange.trim();
        final JSONArray rawCandidates = trimmed.startsWith("[")
                ? new JSONArray(trimmed)
                : new JSONObject(trimmed).optJSONArray("candidates");
        final JSONArray rewritten = rewriteRemoteCandidates(rawCandidates == null ? new JSONArray() : rawCandidates);
        appendConfigServerCandidates(sessionId, rewritten);
        return rewritten;
    }

    /**
     * Borrowed from Better xCloud (XhomeInterceptor.handleConfiguration): fetch the session's
     * /configuration endpoint and synthesize direct host candidates on port 9002 and the reported
     * port for serverDetails.{ipAddress,ipV4Address,ipV6Address} in case Teredo candidates are absent.
     */
    private void appendConfigServerCandidates(String sessionId, JSONArray out) {
        try {
            final Response r = api("GET", "/v5/sessions/home/" + safeId(sessionId) + "/configuration", null);
            if (r.status < 200 || r.status > 299 || r.body == null || r.body.isEmpty()) return;
            final JSONObject sd = new JSONObject(r.body).optJSONObject("serverDetails");
            if (sd == null) return;
            final String[][] pairs = {
                {"ipAddress", "port"},
                {"ipV4Address", "ipV4Port"},
                {"ipV6Address", "ipV6Port"}
            };
            int nextFoundation = 20;
            for (String[] pair : pairs) {
                final String ip = optCleanString(sd, pair[0]);
                if (ip.isEmpty()) continue;
                final int port = sd.optInt(pair[1], 0);
                final int[] ports = (port > 0 && port != 9002) ? new int[]{9002, port} : new int[]{9002};
                for (int p : ports) {
                    final String needle = " " + ip + " " + p + " ";
                    boolean exists = false;
                    for (int i = 0; i < out.length(); i++) {
                        final JSONObject c = out.optJSONObject(i);
                        if (c != null && c.optString("candidate", "").contains(needle)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        out.put(candidateJson("candidate:" + (nextFoundation++) + " 1 UDP 1 " + ip + " " + p + " typ host", "0", 0));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    synchronized void keepalive(String sessionId) {
        try {
            ensureStreaming();
            api("POST", "/v5/sessions/home/" + safeId(sessionId) + "/keepalive", "{}");
        } catch (Exception e) {
            Log.w(TAG, "keepalive failed: " + e.getMessage());
        }
    }

    synchronized void stop(String sessionId) {
        try {
            ensureStreaming();
            api("DELETE", "/v5/sessions/home/" + safeId(sessionId), null);
        } catch (Exception e) {
            Log.w(TAG, "stop failed: " + e.getMessage());
        } finally {
            clearPersistedSession(sessionId);
        }
    }

    // ---------------------------------------------------------------- SDP helpers

    /**
     * Chromium hides host candidates behind mDNS (.local) names that the console cannot resolve.
     * The port is real and bound on every interface, so rewriting the address to this device's LAN
     * address makes the candidate usable; the console then reaches the WebView's ICE socket directly.
     */
    static JSONArray rewriteLocalCandidates(JSONArray in, List<String> localAddresses) {
        String ipv4 = null, ipv6 = null;
        for (String a : localAddresses) {
            if (a.contains(":")) {
                if (ipv6 == null) ipv6 = a;
            } else if (ipv4 == null) {
                ipv4 = a;
            }
        }
        final String replacement = ipv4 != null ? ipv4 : ipv6;
        final JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            final JSONObject c = in.optJSONObject(i);
            if (c == null) continue;
            String text = c.optString("candidate", "");
            if (text.startsWith("a=")) text = text.substring(2);
            if (replacement != null && text.startsWith("candidate:")) {
                final String[] parts = text.split(" ");
                if (parts.length > 5 && parts[4].endsWith(".local")) {
                    parts[4] = replacement;
                    text = join(parts, " ");
                }
            }
            try {
                out.put(new JSONObject()
                        .put("candidate", text)
                        .put("sdpMid", c.optString("sdpMid", "0"))
                        .put("sdpMLineIndex", c.optInt("sdpMLineIndex", 0)));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Expand Teredo candidates into the IPv4 host candidates the PC build already relies on. */
    static JSONArray rewriteRemoteCandidates(JSONArray in) {
        final JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            final JSONObject c = in.optJSONObject(i);
            if (c == null) continue;
            String text = c.optString("candidate", "");
            String mid = c.optString("sdpMid", "");
            if (mid.isEmpty()) mid = "0";
            final int index = c.optInt("sdpMLineIndex", 0);
            if (text.startsWith("a=")) text = text.substring(2);
            if (text.isEmpty() || "end-of-candidates".equals(text) || "a=end-of-candidates".equals(text)) continue;
            if (!text.startsWith("candidate:")) text = "candidate:" + text;
            final String[] parts = text.split(" ");
            if (parts.length > 5 && parts[4].startsWith("2001:0:")) {
                final String[] decoded = teredo(parts[4]);
                if (decoded != null) {
                    final String privateLan = decoded[0].startsWith("192.168.") || decoded[0].startsWith("10.")
                            || decoded[0].startsWith("172.") ? "2130706431" : "1";
                    final String privateLan2 = decoded[0].startsWith("192.168.") || decoded[0].startsWith("10.")
                            || decoded[0].startsWith("172.") ? "2130706430" : "1";
                    out.put(candidateJson("candidate:10 1 UDP " + privateLan + " " + decoded[0] + " 9002 typ host", mid, index));
                    out.put(candidateJson("candidate:11 1 UDP " + privateLan2 + " " + decoded[0] + " "
                            + decoded[1] + " typ host", mid, index));
                }
            }
            out.put(candidateJson(text, mid, index));
        }
        return out;
    }

    private static JSONObject candidateJson(String text, String mid, int index) {
        try {
            return new JSONObject().put("candidate", text).put("sdpMid", mid).put("sdpMLineIndex", index);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String[] teredo(String ipv6) {
        try {
            final byte[] b = InetAddress.getByName(ipv6).getAddress();
            if (b.length != 16 || (b[0] & 0xff) != 0x20 || (b[1] & 0xff) != 0x01) return null;
            final int port = (((b[10] & 0xff) << 8) | (b[11] & 0xff)) ^ 0xFFFF;
            final String ip = ((b[12] & 0xff) ^ 0xFF) + "." + ((b[13] & 0xff) ^ 0xFF) + "."
                    + ((b[14] & 0xff) ^ 0xFF) + "." + ((b[15] & 0xff) ^ 0xFF);
            return new String[]{ip, Integer.toString(port)};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The address the console must be able to reach: the active network's IPv4 first. Enumerating
     * interfaces blindly is not good enough on the headset, which can expose Wi-Fi Direct / VPN
     * addresses before wlan0; a candidate on the wrong interface makes the console's SDP exchange
     * time out after 30 s.
     */
    List<String> localAddresses() {
        final List<String> out = new ArrayList<>();
        try {
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            final android.net.Network network = cm == null ? null : cm.getActiveNetwork();
            final android.net.LinkProperties lp = network == null ? null : cm.getLinkProperties(network);
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    final InetAddress a = la.getAddress();
                    if (a == null || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    final String host = a.getHostAddress();
                    if (host != null && host.indexOf('%') < 0) out.add(host);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "active-network addresses unavailable: " + e.getMessage());
        }
        if (out.isEmpty()) {
            try {
                for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                        if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                        final String host = a.getHostAddress();
                        if (host != null && host.indexOf('%') < 0) out.add(host);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "cannot enumerate local addresses: " + e.getMessage());
            }
        }
        // IPv4 first so the console (and most home LANs) get a directly routable candidate.
        out.sort((a, b) -> Boolean.compare(a.contains(":"), b.contains(":")));
        Log.i(TAG, "local addresses: " + out);
        return out;
    }

    // ---------------------------------------------------------------- HTTP

    private Response api(String method, String path, String body) throws Exception {
        return api(method, path, body, false);
    }

    private Response api(String method, String path, String body, boolean deviceInfo) throws Exception {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + gsToken);
        if (deviceInfo) headers.put("X-MS-Device-Info", NATIVE_DEVICE_INFO);
        return request(method, "https://" + gsHost + path, body == null ? ("POST".equals(method) ? "{}" : "") : body, headers, true);
    }

    private static String optCleanString(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        final String v = obj.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    private String pollExchange(String path) throws Exception {
        // The console can take a while to bring its streaming stack up after a restart; 60 s is
        // still bounded but long enough that a slow start is not reported as a protocol failure.
        for (int attempt = 0; attempt < 120; attempt++) {
            final Response r = api("GET", path, null);
            if (r.status != 204 && r.body != null && !r.body.trim().isEmpty()) {
                final JSONObject j = new JSONObject(r.body);
                final JSONObject e = j.optJSONObject("errorDetails");
                if (e != null) {
                    final String code = optCleanString(e, "code");
                    final String msg = optCleanString(e, "message");
                    if ((!code.isEmpty() && !"0".equals(code)) || !msg.isEmpty()) {
                        throw new IOException("exchange failed: " + code + " " + msg);
                    }
                }
                if (!j.isNull("exchangeResponse")) {
                    final String value = optCleanString(j, "exchangeResponse");
                    if (!value.isEmpty()) return value;
                }
            }
            Thread.sleep(500);
        }
        throw new IOException("the console did not answer " + path);
    }

    private Response request(String method, String url, String body, Map<String, String> headers, boolean authorized)
            throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            // WinHTTP (the PC path) follows redirects; keep the same behaviour here.
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "GTA-Sixty/1.0");
            c.setRequestProperty("Accept", "*/*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            }
            if (body != null && !body.isEmpty() && !"GET".equals(method) && !"DELETE".equals(method)) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            final Response r = new Response();
            r.status = c.getResponseCode();
            final InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : readAll(in);
            if (r.status >= 400 && !authorized) {
                // Auth endpoints spell the failure out in JSON; surface it rather than the HTTP code.
                Log.w(TAG, method + " " + url + " -> HTTP " + r.status + ": " + r.body);
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject json(Response r, String what) throws Exception {
        if (r.status < 200 || r.status > 299) {
            throw new IOException(what + " returned HTTP " + r.status + ": " + trim(r.body));
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException(what + " returned something that is not JSON", e);
        }
    }

    // ---------------------------------------------------------------- tokens at rest

    private void load() {
        final String blob = prefs.getString(KEY_BLOB, "");
        if (blob.isEmpty()) return;
        try {
            final String json = new String(decrypt(blob), StandardCharsets.UTF_8);
            final JSONObject j = new JSONObject(json);
            refreshToken = j.optString("refresh", "");
            accessToken = j.optString("access", "");
            accessExpiresAt = j.optLong("accessExpires", 0);
            userHash = j.optString("uhs", "");
            accountHint = j.optString("hint", "");
        } catch (Exception e) {
            Log.w(TAG, "stored tokens are unreadable; sign in again: " + e.getMessage());
            prefs.edit().remove(KEY_BLOB).apply();
        }
    }

    private void save() {
        try {
            final String json = new JSONObject()
                    .put("refresh", refreshToken)
                    .put("access", accessToken)
                    .put("accessExpires", accessExpiresAt)
                    .put("uhs", userHash)
                    .put("hint", accountHint)
                    .toString();
            prefs.edit().putString(KEY_BLOB, encrypt(json.getBytes(StandardCharsets.UTF_8))).apply();
        } catch (Exception e) {
            Log.w(TAG, "cannot store tokens: " + e.getMessage());
        }
    }

    private static String encrypt(byte[] plain) throws Exception {
        final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        final byte[] iv = c.getIV();
        final byte[] ct = c.doFinal(plain);
        final byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return "enc:" + Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static byte[] decrypt(String blob) throws Exception {
        if (blob.startsWith("enc:")) {
            final byte[] raw = Base64.decode(blob.substring(4), Base64.NO_WRAP);
            final byte[] iv = new byte[12];
            System.arraycopy(raw, 0, iv, 0, 12);
            final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return c.doFinal(raw, 12, raw.length - 12);
        }
        return Base64.decode(blob, Base64.NO_WRAP);
    }

    private static SecretKey key() throws Exception {
        final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        final KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    // ---------------------------------------------------------------- small helpers

    private static String safeId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("invalid session id");
        return id;
    }

    private static Map<String, String> contentType(String value) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("Content-Type", value);
        return m;
    }

    private static Map<String, String> xblHeaders() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("x-xbl-contract-version", "1");
        m.put("Cache-Control", "no-cache");
        m.put("Content-Type", "application/json");
        m.put("Accept", "*/*");
        m.put("Origin", "https://www.xbox.com");
        m.put("Referer", "https://www.xbox.com/");
        m.put("ms-cv", "0");
        return m;
    }

    private static String encodeForm(Map<String, String> form) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trim(String s) {
        if (s == null) return "";
        final String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 240 ? t.substring(0, 240) + "..." : t;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static long parseTime(String text) {
        if (text == null || text.length() < 19) return 0;
        try {
            final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(text.substring(0, 19)).getTime() / 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String join(String[] parts, String sep) {
        final StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }
}

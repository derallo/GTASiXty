package com.gtasixty.spatial;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Sony PSN OAuth helper for retrieving the 8-byte little-endian PSN Account ID required by
 * {@code libchiaki} when registering a PS4 (7.0+) or PS5 console.
 */
final class PsnAuthHelper {

    interface Callback {
        void onSuccess(String accountIdBase64, String onlineId);
        void onError(String message);
    }

    private static final String CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745";
    private static final String CLIENT_SECRET = "mvaiZkRsAsI1IBkY";
    private static final String REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect";
    static final String LOGIN_URL =
            "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize"
            + "?service_entity=urn:service-entity:psn"
            + "&response_type=code"
            + "&client_id=" + CLIENT_ID
            + "&redirect_uri=" + REDIRECT_URI
            + "&scope=psn:clientapp"
            + "&request_locale=en_US"
            + "&ui=pr"
            + "&service_logo=ps"
            + "&layout_type=popup"
            + "&smcid=remoteplay"
            + "&prompt=always"
            + "&PlatformPrivacyWs1=minimal&";
    private static final String TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token";

    private PsnAuthHelper() {}

    static void showSignInDialog(Activity activity, Callback callback) {
        final FrameLayout container = new FrameLayout(activity);
        container.setBackgroundColor(Color.WHITE);
        final WebView webView = new WebView(activity);
        final WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 900));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Sign in to PlayStation Network")
                .setView(container)
                .setNegativeButton("Cancel", (d, w) -> webView.destroy())
                .create();

        final boolean[] handled = {false};
        webView.setWebViewClient(new WebViewClient() {
            private boolean checkRedirect(String url) {
                if (url == null || handled[0]) return false;
                if (url.startsWith(REDIRECT_URI) || url.contains("code=")) {
                    final String code = ConsolePlatform.extractOAuthCodeFromRedirectUrl(url);
                    if (code != null && !code.isEmpty()) {
                        handled[0] = true;
                        dialog.dismiss();
                        webView.destroy();
                        exchangeCodeAsync(activity, code, callback);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return checkRedirect(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                checkRedirect(url);
            }
        });

        dialog.setOnDismissListener(d -> {
            try {
                webView.destroy();
            } catch (Exception ignored) {
            }
        });
        dialog.show();
        webView.loadUrl(LOGIN_URL);
    }

    static void exchangeCodeAsync(Activity activity, String codeOrUrl, Callback callback) {
        String code = ConsolePlatform.extractOAuthCodeFromRedirectUrl(codeOrUrl);
        if (code == null || code.isEmpty()) {
            code = codeOrUrl == null ? "" : codeOrUrl.trim();
        }
        final String finalCode = code;
        if (finalCode.isEmpty()) {
            activity.runOnUiThread(() -> callback.onError("Missing OAuth code or redirect URL."));
            return;
        }

        new Thread(() -> {
            try {
                final String authHeader = "Basic " + Base64.encodeToString(
                        (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP);
                final String body = "grant_type=authorization_code&code="
                        + URLEncoder.encode(finalCode, "UTF-8")
                        + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8");

                final JSONObject tokenJson = postForm(TOKEN_URL, authHeader, body);
                final String accessToken = tokenJson.optString("access_token", "");
                if (accessToken.isEmpty()) {
                    throw new IllegalStateException("Sony OAuth response did not include access_token.");
                }

                final JSONObject infoJson = getJson(
                        TOKEN_URL + "/" + URLEncoder.encode(accessToken, "UTF-8"),
                        authHeader);
                final String userIdStr = infoJson.optString("user_id", "");
                if (userIdStr.isEmpty()) {
                    throw new IllegalStateException("Sony account info missing user_id.");
                }
                final long userId = Long.parseUnsignedLong(userIdStr);
                final ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                bb.putLong(userId);
                final String b64 = ConsolePlatform.encodePsnAccountIdBase64(bb.array());
                final String onlineId = infoJson.optString("online_id", "");

                activity.runOnUiThread(() -> callback.onSuccess(b64, onlineId));
            } catch (Exception e) {
                activity.runOnUiThread(() -> callback.onError("PSN sign-in failed: " + e.getMessage()));
            }
        }, "GTA60-PsnAuth").start();
    }

    private static JSONObject postForm(String urlStr, String authHeader, String formBody) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", authHeader);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(formBody.getBytes(StandardCharsets.UTF_8));
        }
        return readJsonResponse(conn);
    }

    private static JSONObject getJson(String urlStr, String authHeader) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", authHeader);
        return readJsonResponse(conn);
    }

    private static JSONObject readJsonResponse(HttpURLConnection conn) throws Exception {
        final int code = conn.getResponseCode();
        final InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        final StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + sb);
        }
        return new JSONObject(sb.toString());
    }
}

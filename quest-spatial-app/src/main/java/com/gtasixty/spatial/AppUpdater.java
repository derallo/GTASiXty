package com.gtasixty.spatial;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks GitHub (derallo/GTASiXty) every 3 hours (and on demand via the Update button) for a newer
 * Quest 3 APK, downloads it to cacheDir, and launches Android's package installer.
 */
final class AppUpdater {
    private static final String TAG = "GTA60Update";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/derallo/GTASiXty/main/releases/version.json";
    private static final long CHECK_INTERVAL_MS = 3L * 60L * 60L * 1000L; // 3 hours
    private static final String PREF_LAST_CHECK = "lastUpdateCheckMs";

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private boolean destroyed = false;

    private final Runnable periodicCheck = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            checkIfDue();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    AppUpdater(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("gta60", Context.MODE_PRIVATE);
    }

    void startPeriodicChecks() {
        handler.removeCallbacks(periodicCheck);
        final long last = prefs.getLong(PREF_LAST_CHECK, 0L);
        final long elapsed = Math.max(0L, System.currentTimeMillis() - last);
        final long initialDelay = elapsed >= CHECK_INTERVAL_MS ? 4000L : (CHECK_INTERVAL_MS - elapsed);
        handler.postDelayed(periodicCheck, initialDelay);
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacks(periodicCheck);
        io.shutdownNow();
    }

    void checkIfDue() {
        final long now = System.currentTimeMillis();
        final long last = prefs.getLong(PREF_LAST_CHECK, 0L);
        if (now - last >= CHECK_INTERVAL_MS) {
            checkNow(false);
        }
    }

    void checkNow(final boolean userInitiated) {
        if (!checking.compareAndSet(false, true)) {
            if (userInitiated) {
                Toast.makeText(activity, "Already checking for updates...", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (userInitiated) {
            Toast.makeText(activity, "Checking GitHub for updates...", Toast.LENGTH_SHORT).show();
        }
        io.execute(() -> {
            try {
                final String raw = fetchText(VERSION_URL);
                final JSONObject j = new JSONObject(raw);
                final long remoteCode = j.optLong("versionCode", 0L);
                final String remoteName = j.optString("versionName", "?");
                final String apkUrl = j.optString("apkUrl", "");
                final String notes = j.optString("notes", "");
                final long localCode = localVersionCode();
                final String localName = localVersionName();

                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                Log.i(TAG, "Update check: local=" + localCode + " (" + localName + "), remote="
                        + remoteCode + " (" + remoteName + ")");

                handler.post(() -> {
                    checking.set(false);
                    if (destroyed || activity.isFinishing()) return;
                    if (remoteCode > localCode && !apkUrl.isEmpty()) {
                        showUpdateDialog(remoteName, remoteCode, apkUrl, notes, localName);
                    } else if (userInitiated) {
                        new AlertDialog.Builder(activity)
                                .setTitle("GTA-Sixty is up to date")
                                .setMessage("Installed version: v" + localName + " (build " + localCode + ")\n"
                                        + "Latest on GitHub: v" + remoteName + " (build " + remoteCode + ")")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Update check failed: " + e.getMessage());
                handler.post(() -> {
                    checking.set(false);
                    if (userInitiated && !destroyed && !activity.isFinishing()) {
                        Toast.makeText(activity, "Update check failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showUpdateDialog(String remoteName, long remoteCode, String apkUrl,
                                  String notes, String localName) {
        final StringBuilder msg = new StringBuilder();
        msg.append("A new version of GTA-Sixty is available on GitHub.\n\n")
           .append("Installed: v").append(localName).append("\n")
           .append("Available: v").append(remoteName).append(" (build ").append(remoteCode).append(")");
        if (!notes.isEmpty()) {
            msg.append("\n\nWhat's new:\n").append(notes);
        }
        new AlertDialog.Builder(activity)
                .setTitle("Update Available")
                .setMessage(msg.toString())
                .setPositiveButton("Download & Install", (d, w) -> downloadAndInstall(apkUrl))
                .setNegativeButton("Later", null)
                .show();
    }

    private void downloadAndInstall(final String apkUrl) {
        Toast.makeText(activity, "Downloading update APK...", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                final File outFile = new File(activity.getCacheDir(), "update.apk");
                downloadFile(apkUrl, outFile);
                handler.post(() -> {
                    if (destroyed || activity.isFinishing()) return;
                    launchInstaller();
                });
            } catch (Exception e) {
                Log.w(TAG, "APK download failed: " + e.getMessage());
                handler.post(() -> {
                    if (!destroyed && !activity.isFinishing()) {
                        Toast.makeText(activity, "Download failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void launchInstaller() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow App Installs")
                    .setMessage("Android needs permission for GTA-Sixty to install updates. "
                            + "Enable \"Allow from this source\" in Settings, then tap Update again.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        final Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(perm);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(ApkFileProvider.UPDATE_URI, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }

    private long localVersionCode() {
        try {
            final PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
            @SuppressWarnings("deprecation")
            final long v = pi.versionCode;
            return v;
        } catch (Exception e) {
            return 0L;
        }
    }

    private String localVersionName() {
        try {
            final PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    private static String fetchText(String urlStr) throws Exception {
        final HttpURLConnection c = openConnection(urlStr);
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static void downloadFile(String urlStr, File dest) throws Exception {
        final File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        final HttpURLConnection c = openConnection(urlStr);
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(tmp, false)) {
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (dest.exists() && !dest.delete()) {
            throw new IllegalStateException("Cannot replace " + dest.getAbsolutePath());
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("Cannot rename " + tmp.getAbsolutePath());
        }
    }

    private static HttpURLConnection openConnection(String urlStr) throws Exception {
        String cur = urlStr;
        for (int i = 0; i < 5; i++) {
            final HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "GTA-Sixty-Quest3");
            final int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                final String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IllegalStateException("Redirect without Location");
                cur = loc;
                continue;
            }
            if (code < 200 || code >= 300) {
                c.disconnect();
                throw new IllegalStateException("HTTP " + code);
            }
            return c;
        }
        throw new IllegalStateException("Too many redirects");
    }
}

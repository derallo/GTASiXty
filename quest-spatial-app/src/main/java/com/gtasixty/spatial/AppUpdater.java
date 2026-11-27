package com.gtasixty.spatial;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
 * Checks GitHub (derallo/GTASiXty) every time the app is opened, every 3 hours while open,
 * and on demand via the Update button for a newer Quest 3 APK, downloads it to cacheDir, and
 * launches Android's package installer.
 */
final class AppUpdater {
    interface Listener {
        void onUpdateAvailable(String remoteVersionName);
    }

    private static final String TAG = "GTA60Update";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/derallo/GTASiXty/main/releases/version.json";
    private static final String GITHUB_RELEASES_URL =
            "https://api.github.com/repos/derallo/GTASiXty/releases?per_page=8";
    private static final long CHECK_INTERVAL_MS = 3L * 60L * 60L * 1000L; // 3 hours
    private static final long OPEN_DEBOUNCE_MS = 15_000L;
    private static final String PREF_LAST_CHECK = "lastUpdateCheckMs";

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private boolean destroyed = false;
    private long lastOpenCheckMs = 0L;
    private Listener listener;
    private AlertDialog activeDialog;

    private final Runnable periodicCheck = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            checkIfDue();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private final Runnable openCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && !activity.isFinishing()) {
                checkNow(false);
            }
        }
    };

    AppUpdater(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("gta60", Context.MODE_PRIVATE);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void checkOnAppOpen() {
        if (destroyed) return;
        final long now = android.os.SystemClock.uptimeMillis();
        if (lastOpenCheckMs > 0L && (now - lastOpenCheckMs) < OPEN_DEBOUNCE_MS) {
            return;
        }
        lastOpenCheckMs = now;
        handler.removeCallbacks(openCheckRunnable);
        handler.postDelayed(openCheckRunnable, 1500L);
    }

    void startPeriodicChecks() {
        handler.removeCallbacks(periodicCheck);
        handler.postDelayed(periodicCheck, CHECK_INTERVAL_MS);
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacks(periodicCheck);
        handler.removeCallbacks(openCheckRunnable);
        if (activeDialog != null) {
            try {
                activeDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeDialog = null;
        }
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
            String versionRaw = null;
            String releasesRaw = null;
            Exception versionErr = null;
            Exception releasesErr = null;
            try {
                versionRaw = fetchText(VERSION_URL + "?t=" + System.currentTimeMillis(), 10000, 15000, null);
            } catch (Exception e) {
                versionErr = e;
            }
            try {
                releasesRaw = fetchText(GITHUB_RELEASES_URL, 5000, 5000, "application/vnd.github+json");
            } catch (Exception e) {
                releasesErr = e;
            }

            if (versionRaw == null && releasesRaw == null) {
                final String errMsg = versionErr != null
                        ? versionErr.getMessage()
                        : (releasesErr != null ? releasesErr.getMessage() : "Network error");
                Log.w(TAG, "Update check failed: " + errMsg);
                handler.post(() -> {
                    checking.set(false);
                    if (userInitiated && !destroyed && !activity.isFinishing()) {
                        Toast.makeText(activity, "Update check failed: " + errMsg,
                                Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            final long localCode = localVersionCode();
            final String localName = localVersionName();
            final AppUpdateParser.UpdateInfo info = AppUpdateParser.resolve(
                    versionRaw, releasesRaw, localCode, localName);

            prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
            Log.i(TAG, "Update check: local=" + localCode + " (" + localName + "), remote="
                    + info.versionCode + " (" + info.versionName + "), available=" + info.updateAvailable);

            handler.post(() -> {
                checking.set(false);
                if (destroyed || activity.isFinishing()) return;
                if (info.updateAvailable) {
                    if (listener != null) {
                        listener.onUpdateAvailable(info.versionName);
                    }
                    showUpdateDialog(info, localName, localCode);
                } else if (userInitiated) {
                    showUpToDateDialog(info, localName, localCode);
                }
            });
        });
    }

    private void showUpdateDialog(AppUpdateParser.UpdateInfo info, String localName, long localCode) {
        if (activeDialog != null && activeDialog.isShowing()) {
            return;
        }
        final StringBuilder msg = new StringBuilder();
        msg.append("A newer version of GTA-Sixty is available on GitHub.\n\n")
           .append("Your version: v").append(localName).append(" (build ").append(localCode).append(")\n")
           .append("Latest on GitHub: v").append(info.versionName);
        if (info.versionCode > 0) {
            msg.append(" (build ").append(info.versionCode).append(")");
        }
        if (info.recentNotes != null && !info.recentNotes.isEmpty()) {
            msg.append("\n\nRecent enhancements:\n\n").append(info.recentNotes);
        }

        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("Update Available — v" + info.versionName)
                .setView(makeScrollableMessageView(msg.toString()))
                .setPositiveButton("Download & Install", (d, w) -> downloadAndInstall(info.apkUrl))
                .setNegativeButton("Later", null)
                .create();
        dlg.setOnDismissListener(d -> {
            if (activeDialog == dlg) activeDialog = null;
        });
        activeDialog = dlg;
        dlg.show();
    }

    private void showUpToDateDialog(AppUpdateParser.UpdateInfo info, String localName, long localCode) {
        final StringBuilder msg = new StringBuilder();
        msg.append("Your version: v").append(localName).append(" (build ").append(localCode).append(")\n")
           .append("Latest on GitHub: v").append(info.versionName);
        if (info.versionCode > 0) {
            msg.append(" (build ").append(info.versionCode).append(")");
        }
        if (info.recentNotes != null && !info.recentNotes.isEmpty()) {
            msg.append("\n\nRecent enhancements:\n\n").append(info.recentNotes);
        }

        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("GTA-Sixty is up to date")
                .setView(makeScrollableMessageView(msg.toString()))
                .setPositiveButton("OK", null)
                .create();
        dlg.setOnDismissListener(d -> {
            if (activeDialog == dlg) activeDialog = null;
        });
        activeDialog = dlg;
        dlg.show();
    }

    private ScrollView makeScrollableMessageView(String text) {
        final ScrollView sv = new ScrollView(activity);
        final TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        final int p = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);
        return sv;
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

    private static String fetchText(String urlStr, int connectMs, int readMs, String accept) throws Exception {
        final HttpURLConnection c = openConnection(urlStr, connectMs, readMs, accept);
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
        final HttpURLConnection c = openConnection(urlStr, 10000, 20000, null);
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

    private static HttpURLConnection openConnection(String urlStr, int connectMs, int readMs,
                                                    String accept) throws Exception {
        String cur = urlStr;
        for (int i = 0; i < 5; i++) {
            final HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "GTA-Sixty-Quest3");
            if (accept != null && !accept.isEmpty()) {
                c.setRequestProperty("Accept", accept);
            }
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

package com.gtasixty.spatial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-JVM parser/merger for {@code releases/version.json} and the GitHub Releases API payload.
 * Resolves whether a newer Quest APK exists and collects human-readable enhancement notes from
 * recent versions newer than the installed build.
 */
final class AppUpdateParser {

    static final class UpdateInfo {
        final String versionName;
        final long versionCode;
        final String apkUrl;
        final String recentNotes;
        final boolean updateAvailable;

        UpdateInfo(String versionName, long versionCode, String apkUrl,
                   String recentNotes, boolean updateAvailable) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.recentNotes = recentNotes;
            this.updateAvailable = updateAvailable;
        }
    }

    private static final class ReleaseEntry {
        final String versionName;
        final long versionCode;
        final String apkUrl;
        final String notes;

        ReleaseEntry(String versionName, long versionCode, String apkUrl, String notes) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    private AppUpdateParser() {}

    static int compareVersionNames(String a, String b) {
        final List<Integer> pa = parseVersionSegments(a);
        final List<Integer> pb = parseVersionSegments(b);
        final int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            final int va = i < pa.size() ? pa.get(i) : 0;
            final int vb = i < pb.size() ? pb.get(i) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    static boolean isNewer(long remoteCode, String remoteName, long localCode, String localName) {
        if (remoteCode > 0 && localCode > 0 && remoteCode > localCode) {
            return true;
        }
        return compareVersionNames(remoteName, localName) > 0;
    }

    static UpdateInfo resolve(String versionJsonRaw, String githubReleasesJsonRaw,
                              long localCode, String localName) {
        ReleaseEntry versionJsonLatest = null;
        final List<ReleaseEntry> allEntries = new ArrayList<>();

        if (versionJsonRaw != null && !versionJsonRaw.trim().isEmpty()) {
            try {
                final JSONObject j = new JSONObject(versionJsonRaw);
                final long code = j.optLong("versionCode", 0L);
                final String name = stripLeadingV(j.optString("versionName", ""));
                final String apkUrl = j.optString("apkUrl", "").trim();
                final String notes = cleanNotes(j.optString("notes", ""));
                if (!name.isEmpty() || code > 0) {
                    versionJsonLatest = new ReleaseEntry(name.isEmpty() ? "?" : name, code, apkUrl, notes);
                    allEntries.add(versionJsonLatest);
                }
                final JSONArray history = j.optJSONArray("history");
                if (history != null) {
                    for (int i = 0; i < history.length(); i++) {
                        final JSONObject h = history.optJSONObject(i);
                        if (h == null) continue;
                        final long hCode = h.optLong("versionCode", 0L);
                        final String hName = stripLeadingV(h.optString("versionName", ""));
                        final String hNotes = cleanNotes(h.optString("notes", ""));
                        if (!hName.isEmpty() || hCode > 0) {
                            allEntries.add(new ReleaseEntry(hName.isEmpty() ? "?" : hName, hCode, "", hNotes));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        ReleaseEntry githubLatest = null;
        if (githubReleasesJsonRaw != null && !githubReleasesJsonRaw.trim().isEmpty()) {
            try {
                final JSONArray releases = new JSONArray(githubReleasesJsonRaw);
                for (int i = 0; i < releases.length(); i++) {
                    final JSONObject rel = releases.optJSONObject(i);
                    if (rel == null) continue;
                    if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) {
                        continue;
                    }
                    String tag = stripLeadingV(rel.optString("tag_name", ""));
                    if (tag.isEmpty()) {
                        tag = stripLeadingV(rel.optString("name", ""));
                    }
                    if (tag.isEmpty()) continue;

                    String apkUrl = "";
                    final JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int a = 0; a < assets.length(); a++) {
                            final JSONObject asset = assets.optJSONObject(a);
                            if (asset == null) continue;
                            final String assetName = asset.optString("name", "");
                            if (assetName.toLowerCase(Locale.US).endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "").trim();
                                break;
                            }
                        }
                    }

                    final String body = cleanNotes(rel.optString("body", ""));
                    final ReleaseEntry entry = new ReleaseEntry(tag, 0L, apkUrl, body);
                    allEntries.add(entry);

                    if (!apkUrl.isEmpty() && (githubLatest == null
                            || compareVersionNames(entry.versionName, githubLatest.versionName) > 0)) {
                        githubLatest = entry;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        ReleaseEntry best = versionJsonLatest;
        if (githubLatest != null) {
            if (best == null || best.apkUrl.isEmpty()) {
                best = githubLatest;
            } else {
                final int cmp = compareVersionNames(githubLatest.versionName, best.versionName);
                if (cmp > 0) {
                    best = githubLatest;
                }
            }
        }

        if (best == null) {
            return new UpdateInfo(localName == null ? "?" : localName, 0L, "", "", false);
        }

        final boolean available = !best.apkUrl.isEmpty()
                && isNewer(best.versionCode, best.versionName, localCode, localName);

        final String recentNotes = buildRecentNotes(allEntries, best, localCode, localName);
        return new UpdateInfo(best.versionName, best.versionCode, best.apkUrl, recentNotes, available);
    }

    private static String buildRecentNotes(List<ReleaseEntry> rawEntries, ReleaseEntry best,
                                           long localCode, String localName) {
        final Map<String, ReleaseEntry> dedup = new LinkedHashMap<>();
        for (ReleaseEntry e : rawEntries) {
            final String key = canonicalVersionKey(e.versionName);
            final ReleaseEntry existing = dedup.get(key);
            if (existing == null) {
                dedup.put(key, e);
            } else {
                final long mergedCode = Math.max(existing.versionCode, e.versionCode);
                final String mergedApk = !existing.apkUrl.isEmpty() ? existing.apkUrl : e.apkUrl;
                final String mergedNotes = existing.notes.length() >= e.notes.length()
                        ? existing.notes : e.notes;
                dedup.put(key, new ReleaseEntry(existing.versionName, mergedCode, mergedApk, mergedNotes));
            }
        }

        final List<ReleaseEntry> newerEntries = new ArrayList<>();
        for (ReleaseEntry e : dedup.values()) {
            if (isNewer(e.versionCode, e.versionName, localCode, localName) && !e.notes.isEmpty()) {
                newerEntries.add(e);
            }
        }

        Collections.sort(newerEntries, (a, b) -> {
            final int nameCmp = compareVersionNames(b.versionName, a.versionName);
            if (nameCmp != 0) return nameCmp;
            return Long.compare(b.versionCode, a.versionCode);
        });

        final StringBuilder sb = new StringBuilder();
        final int maxVersions = Math.min(5, newerEntries.size());
        for (int i = 0; i < maxVersions; i++) {
            final ReleaseEntry e = newerEntries.get(i);
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("v").append(e.versionName).append("\n").append(e.notes);
        }

        if (sb.length() == 0 && best != null && !best.notes.isEmpty()) {
            sb.append("v").append(best.versionName).append("\n").append(best.notes);
        }
        return sb.toString().trim();
    }

    private static List<Integer> parseVersionSegments(String version) {
        final List<Integer> segs = new ArrayList<>();
        if (version == null) return segs;
        String s = stripLeadingV(version.trim());
        if (s.isEmpty()) return segs;
        final String[] parts = s.split("\\.");
        for (String p : parts) {
            final StringBuilder digits = new StringBuilder();
            for (int i = 0; i < p.length(); i++) {
                final char ch = p.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    digits.append(ch);
                } else {
                    break;
                }
            }
            if (digits.length() == 0) break;
            try {
                segs.add(Integer.parseInt(digits.toString()));
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        return segs;
    }

    private static String canonicalVersionKey(String version) {
        final List<Integer> segs = parseVersionSegments(version);
        if (segs.isEmpty()) return stripLeadingV(version == null ? "" : version);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(segs.get(i));
        }
        return sb.toString();
    }

    private static String stripLeadingV(String s) {
        if (s == null) return "";
        final String t = s.trim();
        if (t.length() > 1 && (t.charAt(0) == 'v' || t.charAt(0) == 'V')
                && Character.isDigit(t.charAt(1))) {
            return t.substring(1);
        }
        return t;
    }

    private static String cleanNotes(String raw) {
        if (raw == null) return "";
        final String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        final StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            while (trimmed.startsWith("#")) {
                trimmed = trimmed.substring(1).trim();
            }
            trimmed = trimmed.replace("**", "").replace("__", "");
            if (trimmed.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
        }
        return out.toString().trim();
    }
}

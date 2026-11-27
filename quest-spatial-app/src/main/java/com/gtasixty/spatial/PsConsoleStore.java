package com.gtasixty.spatial;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent store for registered PlayStation 4 / PlayStation 5 Remote Play consoles.
 */
public final class PsConsoleStore {

    private static final String PREFS = "gta60_ps";
    private static final String KEY_CONSOLES = "consoles_json";
    private static final String KEY_LAST_HOST = "last_host";
    private static final String KEY_PSN_ACCOUNT_ID = "psn_account_id_b64";

    public static final class PsConsole {
        public final String host;
        public final String nickname;
        public final boolean isPs5;
        public final int target;
        public final String macHex;
        public final String registKeyHex;
        public final String morningHex;
        public final String psnAccountIdBase64;

        public PsConsole(
                String host,
                String nickname,
                boolean isPs5,
                int target,
                String macHex,
                String registKeyHex,
                String morningHex,
                String psnAccountIdBase64) {
            this.host = host == null ? "" : host;
            this.nickname = nickname == null ? "" : nickname;
            this.isPs5 = isPs5;
            this.target = target;
            this.macHex = macHex == null ? "" : macHex;
            this.registKeyHex = registKeyHex == null ? "" : registKeyHex;
            this.morningHex = morningHex == null ? "" : morningHex;
            this.psnAccountIdBase64 = psnAccountIdBase64 == null ? "" : psnAccountIdBase64;
        }

        public String displayName() {
            final String type = isPs5 ? "PS5" : "PS4";
            if (!nickname.isEmpty()) return nickname + " (" + type + ")";
            return type + " (" + host + ")";
        }

        public byte[] registKeyBytes() {
            return ConsolePlatform.hexToBytes(registKeyHex);
        }

        public byte[] morningBytes() {
            return ConsolePlatform.hexToBytes(morningHex);
        }

        public long wakeCredential() {
            return ConsolePlatform.parseRegistKeyWakeCredential(registKeyBytes());
        }
    }

    private PsConsoleStore() {}

    public static String toJsonArrayString(List<PsConsole> list) {
        final JSONArray arr = new JSONArray();
        if (list != null) {
            for (PsConsole c : list) {
                if (c == null || c.host.isEmpty()) continue;
                try {
                    final JSONObject obj = new JSONObject();
                    obj.put("host", c.host);
                    obj.put("nickname", c.nickname);
                    obj.put("isPs5", c.isPs5);
                    obj.put("target", c.target);
                    obj.put("macHex", c.macHex);
                    obj.put("registKeyHex", c.registKeyHex);
                    obj.put("morningHex", c.morningHex);
                    obj.put("psnAccountIdBase64", c.psnAccountIdBase64);
                    arr.put(obj);
                } catch (Exception ignored) {
                }
            }
        }
        return arr.toString();
    }

    public static List<PsConsole> fromJsonArrayString(String raw) {
        final List<PsConsole> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            final JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                final String host = obj.optString("host", "");
                if (host.isEmpty()) continue;
                out.add(new PsConsole(
                        host,
                        obj.optString("nickname", ""),
                        obj.optBoolean("isPs5", true),
                        obj.optInt("target", 1000100),
                        obj.optString("macHex", ""),
                        obj.optString("registKeyHex", ""),
                        obj.optString("morningHex", ""),
                        obj.optString("psnAccountIdBase64", "")
                ));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<PsConsole> loadAll(Context context) {
        if (context == null) return new ArrayList<>();
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return fromJsonArrayString(prefs.getString(KEY_CONSOLES, ""));
    }

    public static void saveOrUpdate(Context context, PsConsole console) {
        if (context == null || console == null || console.host.isEmpty()) return;
        final List<PsConsole> list = loadAll(context);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            final PsConsole existing = list.get(i);
            final boolean sameMac = !console.macHex.isEmpty() && console.macHex.equalsIgnoreCase(existing.macHex);
            final boolean sameHost = console.host.equals(existing.host);
            if (sameMac || sameHost) {
                list.set(i, console);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(console);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CONSOLES, toJsonArrayString(list))
                .putString(KEY_LAST_HOST, console.host)
                .apply();
    }

    public static void remove(Context context, String host) {
        if (context == null || host == null) return;
        final List<PsConsole> list = loadAll(context);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (host.equals(list.get(i).host)) {
                list.remove(i);
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CONSOLES, toJsonArrayString(list))
                .apply();
    }

    public static String getLastSelectedHost(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_HOST, "");
    }

    public static void setLastSelectedHost(Context context, String host) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_HOST, host == null ? "" : host)
                .apply();
    }

    public static String getSavedPsnAccountIdBase64(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PSN_ACCOUNT_ID, "");
    }

    public static void setSavedPsnAccountIdBase64(Context context, String accountIdBase64) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PSN_ACCOUNT_ID, accountIdBase64 == null ? "" : accountIdBase64)
                .apply();
    }
}

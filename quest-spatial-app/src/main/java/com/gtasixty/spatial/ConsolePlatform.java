package com.gtasixty.spatial;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Locale;

/**
 * Console platform selector and pure-JVM PlayStation Remote Play credential/ID helpers.
 */
public final class ConsolePlatform {

    public static final int XBOX = 0;
    public static final int PLAYSTATION = 1;

    private static final String PREF_XBOX = "xbox";
    private static final String PREF_PS = "playstation";

    private ConsolePlatform() {}

    public static String prefValue(int platform) {
        return platform == PLAYSTATION ? PREF_PS : PREF_XBOX;
    }

    public static int fromPrefValue(String raw) {
        if (raw == null) return XBOX;
        final String v = raw.trim().toLowerCase(Locale.US);
        if (PREF_PS.equals(v) || "ps".equals(v) || "ps5".equals(v) || "ps4".equals(v)) {
            return PLAYSTATION;
        }
        return XBOX;
    }

    public static String label(int platform) {
        return platform == PLAYSTATION ? "PlayStation" : "Xbox";
    }

    public static String toggleButtonLabel(int platform) {
        return platform == PLAYSTATION ? "PS \u21C4 Xbox" : "Xbox \u21C4 PS";
    }

    public static String accentHex(int platform) {
        return platform == PLAYSTATION ? "#0070D1" : "#107C10";
    }

    /**
     * Parses a PSN Account ID entered as either:
     * <ul>
     *   <li>8-byte Base64 string (Chiaki standard format, e.g. {@code "AAAAAAAAAAA="}), or</li>
     *   <li>Unsigned 64-bit decimal user ID (converted to 8-byte little-endian).</li>
     * </ul>
     *
     * @return 8-byte account ID, or {@code null} if invalid.
     */
    public static byte[] parsePsnAccountId(String input) {
        if (input == null) return null;
        final String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        if (trimmed.matches("\\d{1,20}")) {
            try {
                final long userId = Long.parseUnsignedLong(trimmed);
                final ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                bb.putLong(userId);
                return bb.array();
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        try {
            final byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length == 8) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    public static String encodePsnAccountIdBase64(byte[] id8) {
        if (id8 == null || id8.length != 8) return "";
        return Base64.getEncoder().encodeToString(id8);
    }

    /**
     * Extracts the OAuth {@code code} query parameter from Sony's Remote Play redirect URL.
     */
    public static String extractOAuthCodeFromRedirectUrl(String url) {
        if (url == null) return null;
        final int q = url.indexOf('?');
        final String query = (q >= 0) ? url.substring(q + 1) : url;
        final int hash = query.indexOf('#');
        final String clean = (hash >= 0) ? query.substring(0, hash) : query;
        for (String part : clean.split("&")) {
            final int eq = part.indexOf('=');
            if (eq <= 0) continue;
            final String k = part.substring(0, eq).trim();
            if ("code".equals(k)) {
                final String val = part.substring(eq + 1).trim();
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    /**
     * Converts a 16-byte {@code rp_regist_key} (null-padded ASCII hex) into the unsigned 64-bit
     * {@code user_credential} expected by {@code chiaki_discovery_wakeup}.
     */
    public static long parseRegistKeyWakeCredential(byte[] registKey16) {
        if (registKey16 == null || registKey16.length == 0) return 0L;
        int len = 0;
        while (len < registKey16.length && registKey16[len] != 0) {
            len++;
        }
        final String hex = new String(registKey16, 0, len, java.nio.charset.StandardCharsets.US_ASCII).trim();
        if (hex.isEmpty()) return 0L;
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        final char[] hex = "0123456789abcdef".toCharArray();
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        final String clean = hex.trim();
        if ((clean.length() & 1) != 0) return new byte[0];
        final byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(clean.charAt(i * 2), 16);
            final int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return new byte[0];
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}

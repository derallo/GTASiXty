package com.gtasixty.spatial.voice;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Isolated preferences & presets for the experimental "Jimmy" voice assistant.
 * Stored in its own SharedPreferences file ("gta60_jimmy_voice") so removing this
 * feature leaves all core app preferences untouched.
 */
public final class JimmyConfig {

    private static final String PREFS_NAME = "gta60_jimmy_voice";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WAKE_WORD = "wakeWordEnabled";
    private static final String KEY_WEB_SEARCH = "webSearchEnabled";
    private static final String KEY_GEMINI_KEY = "geminiApiKey";
    private static final String KEY_ELEVENLABS_KEY = "elevenLabsApiKey";
    private static final String KEY_ELEVENLABS_VOICE = "elevenLabsVoiceId";
    private static final String KEY_GEMINI_MODEL = "geminiModel";
    private static final String KEY_GEMINI_FALLBACK_MODEL = "geminiFallbackModel";
    private static final String KEY_MODEL_EXPLICIT = "geminiModelExplicit";
    private static final String KEY_SENSITIVITY = "sensitivity";

    public static final int SENSITIVITY_QUIET = 0;
    public static final int SENSITIVITY_NORMAL = 1;
    public static final int SENSITIVITY_LOUD = 2;

    public static final String VOICE_ADAM = "pNInz6obpgDQGcFmaJgB";
    public static final String VOICE_LAURA = "FGY2WhTYpPnrIDTdsKH5";
    public static final String VOICE_GAME_SHOW_HOST = "dEc1rkm0ul0kDedEuETS";
    public static final String VOICE_RACHEL = "21m00Tcm4TlvDq8ikWAM";
    public static final String VOICE_JESSICA = "cgSgspJ2msm6clMCkdW9";

    public static final String DEFAULT_VOICE_ID = VOICE_ADAM;
    public static final String DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest";
    public static final String DEFAULT_GEMINI_FALLBACK_MODEL = "gemini-flash-latest";

    public static final String[] VOICE_LABELS = {
            "Adam (Default Male)",
            "Game Show Host",
            "Rachel (Sharp Female)",
            "Jessica (Upbeat Female)"
    };
    public static final String[] VOICE_IDS = {
            VOICE_ADAM,
            VOICE_GAME_SHOW_HOST,
            VOICE_RACHEL,
            VOICE_JESSICA
    };

    public static final String[] MODEL_OPTIONS = {
            "gemini-flash-lite-latest",
            "gemini-flash-latest"
    };

    public static final String[] SENSITIVITY_LABELS = {
            "Quiet Room",
            "Normal",
            "Loud Game"
    };

    private static final Set<String> FEMALE_VOICE_IDS = new HashSet<>(Arrays.asList(
            VOICE_RACHEL,
            VOICE_LAURA,
            VOICE_JESSICA,
            "EXAVITQu4vr4xnSDxMaL",
            "Xb7hH8MSUJpSbSDYk0k2",
            "XrExE9yKIg1WjnnlVkGX",
            "hpp4J3VqNfWAUOO0d1Us",
            "pFZP5JQG7iQjIQuC4Bku"
    ));

    public static final class Snapshot {
        public boolean enabled;
        public boolean wakeWordEnabled;
        public boolean webSearchEnabled;
        public String geminiApiKey;
        public String elevenLabsApiKey;
        public String elevenLabsVoiceId;
        public String geminiModel;
        public String geminiFallbackModel;
        public boolean modelExplicit;
        public int sensitivity;

        public Snapshot() {
            this.enabled = false;
            this.wakeWordEnabled = true;
            this.webSearchEnabled = true;
            this.geminiApiKey = "";
            this.elevenLabsApiKey = "";
            this.elevenLabsVoiceId = DEFAULT_VOICE_ID;
            this.geminiModel = DEFAULT_GEMINI_MODEL;
            this.geminiFallbackModel = DEFAULT_GEMINI_FALLBACK_MODEL;
            this.modelExplicit = false;
            this.sensitivity = SENSITIVITY_NORMAL;
        }

        public Snapshot(Snapshot other) {
            this.enabled = other.enabled;
            this.wakeWordEnabled = other.wakeWordEnabled;
            this.webSearchEnabled = other.webSearchEnabled;
            this.geminiApiKey = safeTrim(other.geminiApiKey);
            this.elevenLabsApiKey = safeTrim(other.elevenLabsApiKey);
            this.elevenLabsVoiceId = nonEmptyOr(other.elevenLabsVoiceId, DEFAULT_VOICE_ID);
            this.geminiModel = nonEmptyOr(other.geminiModel, DEFAULT_GEMINI_MODEL);
            this.geminiFallbackModel = nonEmptyOr(other.geminiFallbackModel, DEFAULT_GEMINI_FALLBACK_MODEL);
            this.modelExplicit = other.modelExplicit;
            this.sensitivity = clampSensitivity(other.sensitivity);
        }

        public boolean hasGeminiKey() {
            return geminiApiKey != null && !geminiApiKey.trim().isEmpty();
        }

        public boolean hasElevenLabsKey() {
            return elevenLabsApiKey != null && !elevenLabsApiKey.trim().isEmpty();
        }
    }

    private JimmyConfig() {}

    public static Snapshot load(Context context) {
        final Snapshot s = new Snapshot();
        if (context == null) return s;
        final SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        s.enabled = prefs.getBoolean(KEY_ENABLED, false);
        s.wakeWordEnabled = prefs.getBoolean(KEY_WAKE_WORD, true);
        s.webSearchEnabled = prefs.getBoolean(KEY_WEB_SEARCH, true);
        s.geminiApiKey = safeTrim(prefs.getString(KEY_GEMINI_KEY, ""));
        s.elevenLabsApiKey = safeTrim(prefs.getString(KEY_ELEVENLABS_KEY, ""));
        s.elevenLabsVoiceId = nonEmptyOr(prefs.getString(KEY_ELEVENLABS_VOICE, DEFAULT_VOICE_ID), DEFAULT_VOICE_ID);
        s.modelExplicit = prefs.getBoolean(KEY_MODEL_EXPLICIT, false);
        final String savedModel = nonEmptyOr(prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL), DEFAULT_GEMINI_MODEL);
        final String savedFallback = nonEmptyOr(
                prefs.getString(KEY_GEMINI_FALLBACK_MODEL, DEFAULT_GEMINI_FALLBACK_MODEL),
                DEFAULT_GEMINI_FALLBACK_MODEL);
        if (!s.modelExplicit && ("gemini-2.5-flash".equals(savedModel) || "gemini-flash-latest".equals(savedModel))) {
            s.geminiModel = DEFAULT_GEMINI_MODEL;
            s.geminiFallbackModel = DEFAULT_GEMINI_FALLBACK_MODEL;
        } else {
            s.geminiModel = savedModel;
            s.geminiFallbackModel = savedFallback;
        }
        s.sensitivity = clampSensitivity(prefs.getInt(KEY_SENSITIVITY, SENSITIVITY_NORMAL));
        return s;
    }

    public static void save(Context context, Snapshot s) {
        if (context == null || s == null) return;
        final Snapshot clean = new Snapshot(s);
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, clean.enabled)
                .putBoolean(KEY_WAKE_WORD, clean.wakeWordEnabled)
                .putBoolean(KEY_WEB_SEARCH, clean.webSearchEnabled)
                .putString(KEY_GEMINI_KEY, clean.geminiApiKey)
                .putString(KEY_ELEVENLABS_KEY, clean.elevenLabsApiKey)
                .putString(KEY_ELEVENLABS_VOICE, clean.elevenLabsVoiceId)
                .putString(KEY_GEMINI_MODEL, clean.geminiModel)
                .putString(KEY_GEMINI_FALLBACK_MODEL, clean.geminiFallbackModel)
                .putBoolean(KEY_MODEL_EXPLICIT, clean.modelExplicit)
                .putInt(KEY_SENSITIVITY, clean.sensitivity)
                .apply();
    }

    public static String maskKey(String key) {
        final String trimmed = safeTrim(key);
        if (trimmed.isEmpty()) return "(not set)";
        if (trimmed.length() <= 6) return "******";
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    public static String resolveFallbackVoiceId(String voiceId) {
        final String clean = safeTrim(voiceId);
        if (VOICE_RACHEL.equals(clean) || isFemaleVoice(clean)) {
            return VOICE_LAURA;
        }
        return VOICE_ADAM;
    }

    public static boolean isFemaleVoice(String voiceId) {
        return FEMALE_VOICE_IDS.contains(safeTrim(voiceId));
    }

    public static int clampSensitivity(int value) {
        return Math.max(SENSITIVITY_QUIET, Math.min(SENSITIVITY_LOUD, value));
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonEmptyOr(String value, String fallback) {
        final String trimmed = safeTrim(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}

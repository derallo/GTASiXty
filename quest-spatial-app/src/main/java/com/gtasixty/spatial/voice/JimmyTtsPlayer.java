package com.gtasixty.spatial.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Low-latency streaming TTS player for Jimmy.
 * Priority 1: ElevenLabs eleven_flash_v2_5 streaming raw 24 kHz 16-bit mono PCM.
 * Priority 2: Gemini Native TTS (gemini-2.5-flash-preview-tts) fallback when ElevenLabs key is absent.
 * Includes a stall-proof AudioTrack drain monitor so playback never freezes at end-of-utterance.
 */
final class JimmyTtsPlayer {

    private static final String TAG = "GTA60-JimmyTts";
    private static final String ELEVEN_BASE = "https://api.elevenlabs.io/v1/text-to-speech/";
    private static final String ELEVEN_MODEL = "eleven_flash_v2_5";
    private static final String GEMINI_TTS_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent";
    private static final int SAMPLE_RATE = JimmyAudioBuffer.SAMPLE_RATE_24K;
    private static final int PREBUFFER_BYTES = 7680; // ~160 ms at 24 kHz mono 16-bit
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    interface Listener {
        void onSpeakStarted(String providerLabel);
        void onSpeakFinished();
        void onSpeakError(String message);
    }

    private final Set<String> restrictedVoiceIds = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger speakGeneration = new AtomicInteger(0);
    private volatile HttpURLConnection activeConn;
    private volatile AudioTrack activeTrack;
    private final Object trackLock = new Object();

    void stopSpeaking() {
        speakGeneration.incrementAndGet();
        final HttpURLConnection c = activeConn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {}
        }
        releaseActiveTrack();
    }

    boolean isSpeaking() {
        synchronized (trackLock) {
            return activeTrack != null && activeTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
        }
    }

    void playChimeAsync(boolean ascending) {
        final Thread t = new Thread(() -> playChime(ascending), "Jimmy-Chime");
        t.setDaemon(true);
        t.start();
    }

    void playChime(boolean ascending) {
        final byte[] pcm = JimmyAudioBuffer.synthesizeChimePcm24k(ascending);
        AudioTrack track = null;
        try {
            track = buildPcmTrack(AudioAttributes.CONTENT_TYPE_SONIFICATION, Math.max(pcm.length, 4096));
            track.play();
            track.write(pcm, 0, pcm.length);
            Thread.sleep(165);
        } catch (Throwable ignored) {
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
        }
    }

    void speak(String text, JimmyConfig.Snapshot cfg, Listener listener) {
        final String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty() || cfg == null) return;

        final int gen = speakGeneration.incrementAndGet();
        boolean spoke = false;
        try {
            if (cfg.hasElevenLabsKey()) {
                try {
                    speakWithElevenLabs(cleanText, cfg, gen, listener);
                    spoke = true;
                    return;
                } catch (Throwable t) {
                    if (speakGeneration.get() != gen) return;
                    Log.w(TAG, "ElevenLabs TTS failed, checking Gemini fallback: " + t.getMessage());
                    if (!cfg.hasGeminiKey()) {
                        if (listener != null) listener.onSpeakError("ElevenLabs error: " + t.getMessage());
                        return;
                    }
                }
            }

            if (cfg.hasGeminiKey() && speakGeneration.get() == gen) {
                speakWithGeminiTts(cleanText, cfg, gen, listener);
                spoke = true;
            } else if (!spoke && listener != null && speakGeneration.get() == gen) {
                listener.onSpeakError("No TTS API key available.");
            }
        } catch (Throwable t) {
            if (speakGeneration.get() == gen && listener != null) {
                Log.w(TAG, "Voice playback failed: " + t.getMessage());
                listener.onSpeakError("Voice playback failed: " + t.getMessage());
            }
        } finally {
            releaseActiveTrack();
            if (speakGeneration.get() == gen && listener != null && spoke) {
                listener.onSpeakFinished();
            }
        }
    }

    private void speakWithElevenLabs(
            String text,
            JimmyConfig.Snapshot cfg,
            int gen,
            Listener listener) throws Exception {
        final long t0 = System.currentTimeMillis();
        final String requestedId = (cfg.elevenLabsVoiceId == null || cfg.elevenLabsVoiceId.trim().isEmpty())
                ? JimmyConfig.DEFAULT_VOICE_ID
                : cfg.elevenLabsVoiceId.trim();
        final String effectiveId = restrictedVoiceIds.contains(requestedId)
                ? JimmyConfig.resolveFallbackVoiceId(requestedId)
                : requestedId;

        HttpURLConnection conn = openElevenLabsStream(effectiveId, text, cfg.elevenLabsApiKey);
        int status = conn.getResponseCode();
        if ((status == 402 || status == 404) && speakGeneration.get() == gen) {
            final String fallbackId = JimmyConfig.resolveFallbackVoiceId(effectiveId);
            if (!fallbackId.equals(effectiveId)) {
                restrictedVoiceIds.add(effectiveId);
                conn.disconnect();
                Log.w(TAG, "ElevenLabs voice HTTP " + status + "; falling back to premade voice " + fallbackId);
                conn = openElevenLabsStream(fallbackId, text, cfg.elevenLabsApiKey);
                status = conn.getResponseCode();
            }
        }

        if (status < 200 || status >= 300) {
            final String errBody = readAll(conn.getErrorStream());
            conn.disconnect();
            throw new IllegalStateException("ElevenLabs HTTP " + status + (errBody.isEmpty() ? "" : ": " + errBody));
        }

        Log.i(TAG, "ElevenLabs stream opened HTTP " + status + " in " + (System.currentTimeMillis() - t0) + " ms");
        activeConn = conn;
        if (listener != null && speakGeneration.get() == gen) {
            listener.onSpeakStarted("ElevenLabs");
        }

        final AudioTrack track = createStreamingTrack();
        long totalBytesWritten = 0L;
        boolean startedPlay = false;

        try (InputStream is = conn.getInputStream()) {
            final byte[] buf = new byte[4096];
            byte[] carry = new byte[1];
            boolean hasCarry = false;
            byte[] preBuf = new byte[PREBUFFER_BYTES];
            int preLen = 0;

            int n;
            while (speakGeneration.get() == gen && (n = is.read(buf)) != -1) {
                if (n == 0) continue;

                final byte[] combined;
                final int combinedLen;
                if (hasCarry) {
                    combined = new byte[n + 1];
                    combined[0] = carry[0];
                    System.arraycopy(buf, 0, combined, 1, n);
                    combinedLen = n + 1;
                    hasCarry = false;
                } else {
                    combined = buf;
                    combinedLen = n;
                }

                final int evenLen = combinedLen - (combinedLen % 2);
                if ((combinedLen % 2) != 0) {
                    carry[0] = combined[combinedLen - 1];
                    hasCarry = true;
                }
                if (evenLen <= 0) continue;

                if (!startedPlay) {
                    final int space = PREBUFFER_BYTES - preLen;
                    final int copy = Math.min(space, evenLen);
                    System.arraycopy(combined, 0, preBuf, preLen, copy);
                    preLen += copy;
                    if (preLen >= PREBUFFER_BYTES) {
                        track.write(preBuf, 0, preLen);
                        track.play();
                        startedPlay = true;
                        totalBytesWritten += preLen;
                        if (evenLen > copy) {
                            track.write(combined, copy, evenLen - copy);
                            totalBytesWritten += (evenLen - copy);
                        }
                    }
                } else {
                    track.write(combined, 0, evenLen);
                    totalBytesWritten += evenLen;
                }
            }

            if (speakGeneration.get() == gen && !startedPlay && preLen > 0) {
                track.write(preBuf, 0, preLen);
                track.play();
                totalBytesWritten += preLen;
            }
            if (speakGeneration.get() == gen) {
                Log.i(TAG, "ElevenLabs finished bytes=" + totalBytesWritten + " totalMs=" + (System.currentTimeMillis() - t0));
                waitForTrackDrain(track, totalBytesWritten, gen);
            }
        } finally {
            if (activeConn == conn) activeConn = null;
            conn.disconnect();
        }
    }

    private HttpURLConnection openElevenLabsStream(String voiceId, String text, String apiKey) throws Exception {
        final URL url = new URL(buildElevenLabsUrl(voiceId));
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        activeConn = conn;
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setRequestProperty("xi-api-key", apiKey.trim());

        final JSONObject voiceSettings = new JSONObject()
                .put("stability", 0.55)
                .put("similarity_boost", 0.75)
                .put("style", 0.15)
                .put("use_speaker_boost", true)
                .put("speed", 1.05);

        final JSONObject body = new JSONObject()
                .put("text", text)
                .put("model_id", ELEVEN_MODEL)
                .put("voice_settings", voiceSettings);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private void speakWithGeminiTts(
            String text,
            JimmyConfig.Snapshot cfg,
            int gen,
            Listener listener) throws Exception {
        final long t0 = System.currentTimeMillis();
        final String cleanPrompt = buildGeminiTtsPrompt(text);
        if (cleanPrompt.isEmpty()) {
            throw new IllegalStateException("Nothing to speak.");
        }
        final String voiceName = JimmyConfig.isFemaleVoice(cfg.elevenLabsVoiceId) ? "Aoede" : "Puck";

        String responseJson;
        try {
            responseJson = postGeminiTts(cleanPrompt, voiceName, cfg.geminiApiKey.trim(), t0);
        } catch (GeminiTtsHttpError e) {
            if (e.status == 400 && speakGeneration.get() == gen) {
                final String quotedPrompt = "Say aloud: \"" + cleanPrompt.replace('"', '\'') + "\"";
                responseJson = postGeminiTts(quotedPrompt, voiceName, cfg.geminiApiKey.trim(), t0);
            } else {
                throw e;
            }
        }

        if (speakGeneration.get() != gen) return;
        final JSONObject envelope = new JSONObject(responseJson);
        final JSONArray candidates = envelope.optJSONArray("candidates");
        final JSONObject part = candidates != null && candidates.length() > 0
                ? candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                : null;
        final JSONObject inlineData = part != null ? part.optJSONObject("inlineData") : null;
        final String b64 = inlineData != null ? inlineData.optString("data", "") : "";
        if (b64.isEmpty()) {
            throw new IllegalStateException("Gemini TTS returned empty audio.");
        }

        final byte[] pcm = stripWavHeaderIfPresent(Base64.getDecoder().decode(b64));
        final int evenLen = pcm.length - (pcm.length % 2);
        if (evenLen <= 0 || speakGeneration.get() != gen) return;

        if (listener != null) listener.onSpeakStarted("Gemini Voice");
        final AudioTrack track = createStreamingTrack();
        track.play();
        int offset = 0;
        while (offset < evenLen && speakGeneration.get() == gen) {
            final int chunk = Math.min(4096, evenLen - offset);
            final int written = track.write(pcm, offset, chunk);
            if (written <= 0) break;
            offset += written;
        }
        if (speakGeneration.get() == gen) {
            Log.i(TAG, "Gemini TTS draining bytes=" + evenLen);
            waitForTrackDrain(track, evenLen, gen);
        }
    }

    private String postGeminiTts(String ttsText, String voiceName, String apiKey, long t0) throws Exception {
        final URL url = new URL(GEMINI_TTS_BASE);
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        activeConn = conn;
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-goog-api-key", apiKey);

            final JSONObject req = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject()
                            .put("parts", new JSONArray().put(new JSONObject().put("text", ttsText)))))
                    .put("generationConfig", new JSONObject()
                            .put("responseModalities", new JSONArray().put("AUDIO"))
                            .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                    .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voiceName)))));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }

            final int status = conn.getResponseCode();
            final InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            final String responseJson = readAll(is);
            Log.i(TAG, "Gemini TTS HTTP " + status + " in " + (System.currentTimeMillis() - t0) + " ms");
            if (status < 200 || status >= 300) {
                final String snippet = responseJson.replace('\n', ' ').trim();
                Log.w(TAG, "Gemini TTS error body: " + (snippet.length() > 200 ? snippet.substring(0, 200) : snippet));
                throw new GeminiTtsHttpError(status);
            }
            return responseJson;
        } finally {
            if (activeConn == conn) activeConn = null;
            conn.disconnect();
        }
    }

    private static final class GeminiTtsHttpError extends Exception {
        final int status;
        GeminiTtsHttpError(int status) {
            super("Gemini TTS HTTP " + status);
            this.status = status;
        }
    }

    private AudioTrack createStreamingTrack() {
        releaseActiveTrack();
        final int minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final int bufSize = Math.max(minBuf, SAMPLE_RATE * 2 / 4);
        final AudioTrack track = buildPcmTrack(AudioAttributes.CONTENT_TYPE_SPEECH, bufSize);
        synchronized (trackLock) {
            activeTrack = track;
        }
        return track;
    }

    private static AudioTrack buildPcmTrack(int contentType, int bufSizeBytes) {
        final AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(contentType)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) {
            b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        return b.build();
    }

    /**
     * Stall-proof AudioTrack drain wait:
     * MODE_STREAM playbackHeadPosition often stops slightly short of totalFrames, so we also exit
     * once the head stops advancing for 350 ms after playing or once expected duration + 600 ms passes.
     */
    private void waitForTrackDrain(AudioTrack track, long totalBytesWritten, int gen) {
        final long totalFrames = totalBytesWritten / 2L;
        if (totalFrames <= 0) return;

        final long expectedAudioMs = (totalFrames * 1000L) / SAMPLE_RATE;
        final long hardDeadline = System.currentTimeMillis() + Math.min(25_000L, expectedAudioMs + 1000L);
        int lastHead = -1;
        long lastAdvanceMs = System.currentTimeMillis();
        boolean sawAdvance = false;

        try {
            track.stop(); // Drain queued audio cleanly in MODE_STREAM
        } catch (Throwable ignored) {}

        while (speakGeneration.get() == gen && System.currentTimeMillis() < hardDeadline) {
            try {
                final int head = track.getPlaybackHeadPosition();
                final long now = System.currentTimeMillis();
                if (head >= totalFrames) break;
                if (head > lastHead) {
                    if (lastHead >= 0) sawAdvance = true;
                    lastHead = head;
                    lastAdvanceMs = now;
                } else if (sawAdvance && (now - lastAdvanceMs) >= 350L) {
                    break;
                }
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
                break;
            }
        }
    }

    private void releaseActiveTrack() {
        final AudioTrack t;
        synchronized (trackLock) {
            t = activeTrack;
            activeTrack = null;
        }
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
        }
    }

    static String buildGeminiTtsPrompt(String text) {
        if (text == null) return "";
        final String clean = text
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ")
                .replaceAll("[*`#_]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isEmpty()) return "";
        return clean.length() > 600 ? clean.substring(0, 600) : clean;
    }

    static String buildElevenLabsUrl(String voiceId) {
        final String clean = (voiceId == null || voiceId.trim().isEmpty())
                ? JimmyConfig.DEFAULT_VOICE_ID
                : voiceId.trim();
        try {
            final String encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
            return ELEVEN_BASE + encoded + "/stream?output_format=pcm_24000";
        } catch (Exception e) {
            return ELEVEN_BASE + clean + "/stream?output_format=pcm_24000";
        }
    }

    static byte[] stripWavHeaderIfPresent(byte[] raw) {
        if (raw == null) return new byte[0];
        if (raw.length >= 44
                && raw[0] == 'R'
                && raw[1] == 'I'
                && raw[2] == 'F'
                && raw[3] == 'F') {
            final byte[] out = new byte[raw.length - 44];
            System.arraycopy(raw, 44, out, 0, out.length);
            return out;
        }
        return raw;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}

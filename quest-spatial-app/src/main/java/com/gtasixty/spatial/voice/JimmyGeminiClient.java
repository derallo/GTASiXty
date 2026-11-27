package com.gtasixty.spatial.voice;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast SSE-streaming Gemini multimodal client replicating FAImily-gaime-night's low-latency pattern:
 * - Single user turn with formatted plain-text dialogue history (eliminates role-alternation 400s).
 * - Per-model cached thinkingConfig fallback (BUDGET_ZERO -> LEVEL_MINIMAL -> OMIT).
 * - `:streamGenerateContent?alt=sse` with early partial `"speech"` extraction so TTS begins
 *   before the full HTTP response finishes.
 */
final class JimmyGeminiClient {

    private static final String TAG = "GTA60-Jimmy";
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String LIST_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 22_000;
    private static final Pattern CITATION_BRACKET_PATTERN = Pattern.compile("\\s*\\[\\d+(?:\\s*,\\s*\\d+)*]");

    enum ThinkingMode {
        BUDGET_ZERO,
        LEVEL_MINIMAL,
        OMIT
    }

    enum SearchMode {
        SEARCH_STRUCTURED,
        SEARCH_PROMPT_JSON,
        NONE_STRUCTURED
    }

    private static final class ModelCompat {
        final ThinkingMode thinking;
        final SearchMode search;

        ModelCompat(ThinkingMode thinking, SearchMode search) {
            this.thinking = thinking;
            this.search = search;
        }
    }

    private static final class StreamOutcome {
        final String text;
        final List<String> webSearchQueries;

        StreamOutcome(String text, List<String> webSearchQueries) {
            this.text = text == null ? "" : text;
            this.webSearchQueries = webSearchQueries == null
                    ? Collections.emptyList()
                    : webSearchQueries;
        }
    }

    static final class ChatTurn {
        final String role; // "user" or "model"
        final String text;

        ChatTurn(String role, String text) {
            this.role = "model".equals(role) ? "model" : "user";
            this.text = text == null ? "" : text;
        }
    }

    static final class AssistantResult {
        final boolean wakeDetected;
        final boolean needsFollowUp;
        final String userTranscript;
        final String speech;
        final String modelUsed;
        final List<String> webSearchQueries;
        final boolean usedWebSearch;

        AssistantResult(
                boolean wakeDetected,
                boolean needsFollowUp,
                String userTranscript,
                String speech,
                String modelUsed) {
            this(wakeDetected, needsFollowUp, userTranscript, speech, modelUsed, Collections.emptyList());
        }

        AssistantResult(
                boolean wakeDetected,
                boolean needsFollowUp,
                String userTranscript,
                String speech,
                String modelUsed,
                List<String> webSearchQueries) {
            this.wakeDetected = wakeDetected;
            this.needsFollowUp = needsFollowUp;
            this.userTranscript = userTranscript == null ? "" : userTranscript.trim();
            this.speech = speech == null ? "" : speech.trim();
            this.modelUsed = modelUsed == null ? "" : modelUsed;
            this.webSearchQueries = webSearchQueries == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(webSearchQueries));
            this.usedWebSearch = !this.webSearchQueries.isEmpty();
        }
    }

    interface EarlySpeechListener {
        void onEarlySpeech(String speech);
    }

    private final ConcurrentHashMap<String, ModelCompat> modelCompatCache = new ConcurrentHashMap<>();
    private volatile HttpURLConnection activeConn;

    void cancelCurrent() {
        final HttpURLConnection c = activeConn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {}
        }
    }

    List<String> listAvailableModels(String apiKey) throws Exception {
        final String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            throw new IllegalStateException("Gemini API key is required to load models.");
        }
        final URL url = new URL(LIST_MODELS_URL);
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);
            conn.setRequestProperty("x-goog-api-key", key);
            final int status = conn.getResponseCode();
            final InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            final String body = readAll(is);
            if (status < 200 || status >= 300) {
                throw new HttpError(status, extractApiErrorMessage(body, status), body);
            }
            return parseAvailableModels(body);
        } finally {
            conn.disconnect();
        }
    }

    static List<String> parseAvailableModels(String responseJson) {
        final Set<String> ordered = new LinkedHashSet<>();
        ordered.add("gemini-flash-latest");
        ordered.add("gemini-flash-lite-latest");

        final List<String> flashModels = new ArrayList<>();
        final List<String> otherGeminiModels = new ArrayList<>();

        if (responseJson != null && !responseJson.trim().isEmpty()) {
            try {
                final JSONObject root = new JSONObject(responseJson);
                final JSONArray models = root.optJSONArray("models");
                if (models != null) {
                    for (int i = 0; i < models.length(); i++) {
                        final JSONObject m = models.optJSONObject(i);
                        if (m == null) continue;
                        if (!supportsGenerateContent(m.optJSONArray("supportedGenerationMethods"))) continue;

                        String id = m.optString("name", "").trim();
                        if (id.startsWith("models/")) {
                            id = id.substring("models/".length());
                        }
                        if (id.isEmpty()) continue;

                        final String lower = id.toLowerCase(Locale.US);
                        if (!lower.startsWith("gemini-")) continue;
                        if (lower.contains("embedding")
                                || lower.contains("aqa")
                                || lower.contains("imagen")
                                || lower.contains("veo")
                                || lower.contains("tts")
                                || lower.contains("robotics")
                                || lower.contains("computer-use")
                                || lower.contains("learnlm")
                                || lower.contains("gemma")
                                || lower.contains("native-audio")
                                || lower.contains("live")) {
                            continue;
                        }

                        if (lower.contains("flash")) {
                            flashModels.add(id);
                        } else {
                            otherGeminiModels.add(id);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        Collections.sort(flashModels, Collections.reverseOrder());
        Collections.sort(otherGeminiModels, Collections.reverseOrder());
        ordered.addAll(flashModels);
        ordered.addAll(otherGeminiModels);
        return new ArrayList<>(ordered);
    }

    private static boolean supportsGenerateContent(JSONArray methods) {
        if (methods == null) return false;
        for (int i = 0; i < methods.length(); i++) {
            if ("generateContent".equals(methods.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    AssistantResult ask(
            String apiKey,
            String primaryModel,
            String fallbackModel,
            byte[] wavAudio,
            byte[] jpegScreenshot,
            String textOverride,
            List<ChatTurn> history,
            boolean wakeRequired) throws Exception {
        return askStreaming(
                apiKey, primaryModel, fallbackModel, wavAudio, jpegScreenshot, textOverride, history, wakeRequired, null);
    }

    AssistantResult askStreaming(
            String apiKey,
            String primaryModel,
            String fallbackModel,
            byte[] wavAudio,
            byte[] jpegScreenshot,
            String textOverride,
            List<ChatTurn> history,
            boolean wakeRequired,
            EarlySpeechListener earlySpeechListener) throws Exception {
        return askStreaming(
                apiKey,
                primaryModel,
                fallbackModel,
                wavAudio,
                jpegScreenshot,
                textOverride,
                history,
                wakeRequired,
                true,
                earlySpeechListener);
    }

    AssistantResult askStreaming(
            String apiKey,
            String primaryModel,
            String fallbackModel,
            byte[] wavAudio,
            byte[] jpegScreenshot,
            String textOverride,
            List<ChatTurn> history,
            boolean wakeRequired,
            boolean webSearchEnabled,
            EarlySpeechListener earlySpeechListener) throws Exception {
        final String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
        final String model = (primaryModel == null || primaryModel.trim().isEmpty())
                ? JimmyConfig.DEFAULT_GEMINI_MODEL
                : primaryModel.trim();
        final String fallback = (fallbackModel == null || fallbackModel.trim().isEmpty())
                ? JimmyConfig.DEFAULT_GEMINI_FALLBACK_MODEL
                : fallbackModel.trim();

        try {
            return callWithThinkingFallback(
                    key, model, wavAudio, jpegScreenshot, textOverride, history, wakeRequired, webSearchEnabled, earlySpeechListener);
        } catch (HttpError e) {
            final boolean retryable = (e.status == 400 || e.status == 404 || e.status == 429 || e.status == 503);
            if (retryable && !fallback.equals(model)) {
                return callWithThinkingFallback(
                        key, fallback, wavAudio, jpegScreenshot, textOverride, history, wakeRequired, webSearchEnabled, earlySpeechListener);
            }
            throw e;
        }
    }

    private AssistantResult callWithThinkingFallback(
            String apiKey,
            String model,
            byte[] wavAudio,
            byte[] jpegScreenshot,
            String textOverride,
            List<ChatTurn> history,
            boolean wakeRequired,
            boolean webSearchEnabled,
            EarlySpeechListener earlySpeechListener) throws Exception {
        final String cacheKey = model + (webSearchEnabled ? "|search" : "|nosearch");
        final ModelCompat initial = modelCompatCache.getOrDefault(
                cacheKey,
                new ModelCompat(defaultThinkingMode(model), defaultSearchMode(model, webSearchEnabled)));
        final List<ModelCompat> attempts = orderedAttempts(initial.thinking, initial.search, webSearchEnabled);

        for (int attemptIdx = 0; attemptIdx < attempts.size(); attemptIdx++) {
            final ModelCompat attempt = attempts.get(attemptIdx);
            final String body = buildRequestBody(
                    model, wavAudio, jpegScreenshot, textOverride, history, wakeRequired, attempt.thinking, attempt.search);
            try {
                if (earlySpeechListener != null) {
                    try {
                        final StreamOutcome streamed = executeSseStream(
                                model, apiKey, body, wakeRequired, earlySpeechListener);
                        modelCompatCache.put(cacheKey, attempt);
                        return parseModelJsonText(streamed.text, wakeRequired, model, streamed.webSearchQueries);
                    } catch (HttpError httpErr) {
                        throw httpErr;
                    } catch (Exception streamErr) {
                        // Fall back cleanly to non-streaming generateContent on SSE parse/transport glitch
                        final String raw = executePost(model, apiKey, body);
                        modelCompatCache.put(cacheKey, attempt);
                        return parseGenerateContentResponse(raw, wakeRequired, model);
                    }
                } else {
                    final String raw = executePost(model, apiKey, body);
                    modelCompatCache.put(cacheKey, attempt);
                    return parseGenerateContentResponse(raw, wakeRequired, model);
                }
            } catch (HttpError e) {
                if (e.status == 400 && attemptIdx + 1 < attempts.size()) {
                    final ModelCompat next = attempts.get(attemptIdx + 1);
                    logWarn("Gemini " + model + " returned HTTP 400 under ("
                            + attempt.thinking + "," + attempt.search
                            + "); retrying with (" + next.thinking + "," + next.search + ")");
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("Gemini request failed after thinking/search compatibility fallbacks.");
    }

    static ThinkingMode defaultThinkingMode(String model) {
        final String lower = model == null ? "" : model.toLowerCase(Locale.US);
        if (lower.contains("flash-lite-latest")
                || (lower.contains("gemini-3") && !lower.contains("3.5") && !lower.contains("latest"))) {
            return ThinkingMode.LEVEL_MINIMAL;
        }
        return ThinkingMode.BUDGET_ZERO;
    }

    static SearchMode defaultSearchMode(String model, boolean webSearchEnabled) {
        return webSearchEnabled ? SearchMode.SEARCH_PROMPT_JSON : SearchMode.NONE_STRUCTURED;
    }

    static List<ModelCompat> orderedAttempts(
            ThinkingMode initialThinking, SearchMode initialSearch, boolean webSearchEnabled) {
        final List<ModelCompat> list = new ArrayList<>(5);
        if (webSearchEnabled) {
            addAttemptIfAbsent(list, initialThinking, initialSearch);
            addAttemptIfAbsent(list, initialThinking, SearchMode.SEARCH_PROMPT_JSON);
            addAttemptIfAbsent(list, ThinkingMode.OMIT, SearchMode.SEARCH_PROMPT_JSON);
            addAttemptIfAbsent(list, initialThinking, SearchMode.NONE_STRUCTURED);
            addAttemptIfAbsent(list, ThinkingMode.OMIT, SearchMode.NONE_STRUCTURED);
        } else {
            addAttemptIfAbsent(list, initialThinking, SearchMode.NONE_STRUCTURED);
            addAttemptIfAbsent(
                    list,
                    initialThinking == ThinkingMode.LEVEL_MINIMAL
                            ? ThinkingMode.BUDGET_ZERO
                            : ThinkingMode.LEVEL_MINIMAL,
                    SearchMode.NONE_STRUCTURED);
            addAttemptIfAbsent(list, ThinkingMode.OMIT, SearchMode.NONE_STRUCTURED);
        }
        return list;
    }

    private static void addAttemptIfAbsent(List<ModelCompat> list, ThinkingMode thinking, SearchMode search) {
        for (ModelCompat existing : list) {
            if (existing.thinking == thinking && existing.search == search) return;
        }
        list.add(new ModelCompat(thinking, search));
    }

    private StreamOutcome executeSseStream(
            String model,
            String apiKey,
            String jsonBody,
            boolean wakeRequired,
            EarlySpeechListener earlySpeechListener) throws Exception {
        final long t0 = System.currentTimeMillis();
        final URL url = new URL(BASE_URL + model + ":streamGenerateContent?alt=sse");
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        activeConn = conn;
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("x-goog-api-key", apiKey);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            final int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                final String errText = readAll(conn.getErrorStream());
                logWarn("Gemini SSE " + model + " -> HTTP " + status + " in " + (System.currentTimeMillis() - t0)
                        + " ms: " + trimError(errText));
                throw new HttpError(status, extractApiErrorMessage(errText, status), errText);
            }

            final StringBuilder acc = new StringBuilder();
            final Set<String> queries = new LinkedHashSet<>();
            boolean firedEarlySpeech = false;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    final String dataJson = line.substring(5).trim();
                    if (dataJson.isEmpty() || "[DONE]".equals(dataJson)) continue;
                    final List<String> chunkQueries = extractGroundingQueries(dataJson);
                    if (!chunkQueries.isEmpty()) queries.addAll(chunkQueries);
                    final String delta = extractCandidateText(dataJson);
                    if (delta.isEmpty()) continue;
                    acc.append(delta);

                    if (!firedEarlySpeech && earlySpeechListener != null) {
                        final String cur = acc.toString();
                        final Boolean wakeVal = extractPartialJsonBoolean(cur, "wakeDetected");
                        final Boolean followVal = extractPartialJsonBoolean(cur, "needsFollowUp");
                        final String speechVal = extractPartialJsonString(cur, "speech");
                        final boolean wakeOk = !wakeRequired || Boolean.TRUE.equals(wakeVal);
                        final boolean followOk = !wakeRequired || !Boolean.TRUE.equals(followVal);
                        if (wakeOk && followOk && speechVal != null && !speechVal.trim().isEmpty()) {
                            firedEarlySpeech = true;
                            final String cleanSpeech = cleanSpokenText(speechVal);
                            logInfo("Gemini SSE early speech ready in " + (System.currentTimeMillis() - t0) + " ms");
                            earlySpeechListener.onEarlySpeech(cleanSpeech);
                        }
                    }
                }
            }
            logInfo("Gemini SSE " + model + " complete in " + (System.currentTimeMillis() - t0)
                    + " ms" + (queries.isEmpty() ? "" : " webQueries=" + queries));
            return new StreamOutcome(acc.toString(), new ArrayList<>(queries));
        } finally {
            if (activeConn == conn) activeConn = null;
            conn.disconnect();
        }
    }

    private String executePost(String model, String apiKey, String jsonBody) throws Exception {
        final long t0 = System.currentTimeMillis();
        final URL url = new URL(BASE_URL + model + ":generateContent");
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        activeConn = conn;
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-goog-api-key", apiKey);

            final byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            final int status = conn.getResponseCode();
            final InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            final String responseText = readAll(is);
            final long elapsed = System.currentTimeMillis() - t0;
            if (status < 200 || status >= 300) {
                logWarn("Gemini " + model + " -> HTTP " + status + " in " + elapsed + " ms: " + trimError(responseText));
                throw new HttpError(status, extractApiErrorMessage(responseText, status), responseText);
            }
            logInfo("Gemini " + model + " -> HTTP " + status + " in " + elapsed + " ms");
            return responseText;
        } finally {
            if (activeConn == conn) activeConn = null;
            conn.disconnect();
        }
    }

    static String buildRequestBody(
            String model,
            byte[] wavAudio,
            byte[] jpegScreenshot,
            String textOverride,
            List<ChatTurn> history,
            boolean wakeRequired,
            boolean includeThinkingConfig) {
        return buildRequestBody(
                model,
                wavAudio,
                jpegScreenshot,
                textOverride,
                history,
                wakeRequired,
                includeThinkingConfig ? defaultThinkingMode(model) : ThinkingMode.OMIT,
                SearchMode.NONE_STRUCTURED);
    }

    static String buildRequestBody(
            String model,
            byte[] wavAudio,
            byte[] jpegScreenshot,
            String textOverride,
            List<ChatTurn> history,
            boolean wakeRequired,
            ThinkingMode thinkingMode) {
        return buildRequestBody(
                model,
                wavAudio,
                jpegScreenshot,
                textOverride,
                history,
                wakeRequired,
                thinkingMode,
                SearchMode.NONE_STRUCTURED);
    }

    static String buildRequestBody(
            String model,
            byte[] wavAudio,
            byte[] jpegScreenshot,
            String textOverride,
            List<ChatTurn> history,
            boolean wakeRequired,
            ThinkingMode thinkingMode,
            SearchMode searchMode) {
        try {
            final JSONObject root = new JSONObject();

            final String sysPrompt =
                    "You are Jimmy, a sharp, friendly, witty VR gaming co-pilot sitting in the room watching the player's TV screen.\n"
                    + "Rules:\n"
                    + "1. Respond ONLY with a single raw JSON object (no markdown fences, no extra prose) with exact keys: "
                    + "{\"wakeDetected\": boolean, \"needsFollowUp\": boolean, \"speech\": string, \"userTranscript\": string}.\n"
                    + "2. If wakeRequired=true: check if the user spoke the wake word \"Jimmy\" (accept \"Hey Jimmy\", \"Yo Jimmy\", \"Ok Jimmy\", or close phonetic \"Jim\" when addressing the assistant). "
                    + "If they did NOT say Jimmy, set wakeDetected=false, needsFollowUp=false, and speech=\"\".\n"
                    + "3. If wakeRequired=true and wakeDetected=true and the user ONLY called your name (e.g., \"Jimmy\", \"Hey Jimmy\") without asking a question or giving a command, "
                    + "set wakeDetected=true, needsFollowUp=true, and speech=\"Yeah?\" (or a short 1-2 word acknowledgement).\n"
                    + "4. If wakeRequired=false: ALWAYS set wakeDetected=true and needsFollowUp=false, and put your full spoken answer in speech.\n"
                    + "5. Answer directly using the attached game screenshot and Google Search when helpful. Use Google Search to look up game walkthroughs, puzzle solutions, boss weaknesses, item locations, quest steps, patch notes, or any up-to-date info you are not sure of. Skip searching for simple banter or when the answer is already clear on screen. Keep speech concise for fast spoken voice: 1 to 3 short sentences, plain spoken English, NO markdown, NO bullets, NO asterisks, NO citation brackets like [1].\n"
                    + "6. Fill userTranscript with what the player said (or empty string if inaudible).";

            root.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", sysPrompt))));

            // Format history as plain text inside a single role="user" turn (same as FAImily-gaime-night)
            // so role-alternation HTTP 400 errors can never happen.
            final StringBuilder prompt = new StringBuilder();
            if (history != null && !history.isEmpty()) {
                final int start = Math.max(0, history.size() - 6);
                final StringBuilder histBuf = new StringBuilder();
                for (int i = start; i < history.size(); i++) {
                    final ChatTurn turn = history.get(i);
                    if (turn == null || turn.text.isEmpty()) continue;
                    histBuf.append("model".equals(turn.role) ? "Jimmy: " : "Player: ")
                           .append(turn.text.trim())
                           .append('\n');
                }
                if (histBuf.length() > 0) {
                    prompt.append("Recent Dialogue History:\n").append(histBuf).append("---\n");
                }
            }

            prompt.append("wakeRequired=").append(wakeRequired).append(". ");
            if (jpegScreenshot != null && jpegScreenshot.length > 0) {
                prompt.append("A live screenshot of the player's game is attached. ");
            }
            if (textOverride != null && !textOverride.trim().isEmpty()) {
                prompt.append("Player typed/asked: \"").append(textOverride.trim()).append("\".");
            } else {
                prompt.append("Listen to the player's attached audio clip and respond.");
            }

            final JSONArray userParts = new JSONArray();
            userParts.put(new JSONObject().put("text", prompt.toString()));

            if (wavAudio != null && wavAudio.length > 0) {
                userParts.put(new JSONObject().put("inlineData", new JSONObject()
                        .put("mimeType", "audio/wav")
                        .put("data", Base64.getEncoder().encodeToString(wavAudio))));
            }
            if (jpegScreenshot != null && jpegScreenshot.length > 0) {
                userParts.put(new JSONObject().put("inlineData", new JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.getEncoder().encodeToString(jpegScreenshot))));
            }

            final JSONArray contents = new JSONArray();
            contents.put(new JSONObject().put("role", "user").put("parts", userParts));
            root.put("contents", contents);

            final JSONObject genConfig = new JSONObject();
            genConfig.put("maxOutputTokens", 380);
            genConfig.put("temperature", 0.6);
            if (searchMode != SearchMode.SEARCH_PROMPT_JSON) {
                genConfig.put("responseMimeType", "application/json");
                genConfig.put("responseSchema", buildResponseSchema());
            }

            if (thinkingMode == ThinkingMode.LEVEL_MINIMAL) {
                genConfig.put("thinkingConfig", new JSONObject().put("thinkingLevel", "MINIMAL"));
            } else if (thinkingMode == ThinkingMode.BUDGET_ZERO) {
                genConfig.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
            }

            if (searchMode == SearchMode.SEARCH_STRUCTURED || searchMode == SearchMode.SEARCH_PROMPT_JSON) {
                root.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
            }

            root.put("generationConfig", genConfig);
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Gemini request JSON", e);
        }
    }

    private static JSONObject buildResponseSchema() throws Exception {
        final JSONObject props = new JSONObject();
        // Order speech ahead of userTranscript so SSE streams the spoken reply as early as possible.
        props.put("wakeDetected", new JSONObject().put("type", "BOOLEAN"));
        props.put("needsFollowUp", new JSONObject().put("type", "BOOLEAN"));
        props.put("speech", new JSONObject().put("type", "STRING"));
        props.put("userTranscript", new JSONObject().put("type", "STRING"));

        final JSONArray required = new JSONArray()
                .put("wakeDetected")
                .put("needsFollowUp")
                .put("speech")
                .put("userTranscript");

        return new JSONObject()
                .put("type", "OBJECT")
                .put("properties", props)
                .put("required", required);
    }

    static String extractPartialJsonString(String buf, String key) {
        if (buf == null || buf.isEmpty() || key == null || key.isEmpty()) return null;
        final Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        final Matcher m = p.matcher(buf);
        if (!m.find()) return null;
        try {
            final JSONObject tmp = new JSONObject("{\"v\":\"" + m.group(1) + "\"}");
            return tmp.getString("v");
        } catch (Exception e) {
            return m.group(1).replace("\\n", " ").trim();
        }
    }

    static Boolean extractPartialJsonBoolean(String buf, String key) {
        if (buf == null || buf.isEmpty() || key == null || key.isEmpty()) return null;
        final Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        final Matcher m = p.matcher(buf);
        if (!m.find()) return null;
        return Boolean.parseBoolean(m.group(1).toLowerCase(Locale.US));
    }

    private static String extractCandidateText(String responseJson) {
        if (responseJson == null || responseJson.trim().isEmpty()) return "";
        try {
            final JSONObject envelope = new JSONObject(responseJson);
            final JSONArray candidates = envelope.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return "";
            final JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            final JSONArray parts = content != null ? content.optJSONArray("parts") : null;
            if (parts == null || parts.length() == 0) return "";
            final StringBuilder rawText = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                final String t = parts.getJSONObject(i).optString("text", "");
                if (!t.isEmpty()) rawText.append(t);
            }
            return rawText.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static List<String> extractGroundingQueries(String responseJson) {
        if (responseJson == null || responseJson.trim().isEmpty()) return Collections.emptyList();
        try {
            final JSONObject envelope = new JSONObject(responseJson);
            final JSONArray candidates = envelope.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return Collections.emptyList();
            final JSONObject gm = candidates.getJSONObject(0).optJSONObject("groundingMetadata");
            if (gm == null) return Collections.emptyList();
            final JSONArray gmKeys = gm.names();
            if (gmKeys != null && gmKeys.length() > 0) {
                logInfo("Grounding metadata keys=" + gmKeys.toString());
            }
            final Set<String> dedup = new LinkedHashSet<>();
            final JSONArray arr = gm.optJSONArray("webSearchQueries");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    final String q = arr.optString(i, "").trim();
                    if (!q.isEmpty()) dedup.add(q);
                }
            }
            if (dedup.isEmpty()) {
                final JSONArray chunks = gm.optJSONArray("groundingChunks");
                if (chunks != null) {
                    for (int i = 0; i < chunks.length(); i++) {
                        final JSONObject c = chunks.optJSONObject(i);
                        final JSONObject web = c != null ? c.optJSONObject("web") : null;
                        if (web == null) continue;
                        final String label = web.optString("title", "").trim();
                        final String uri = web.optString("uri", "").trim();
                        final String marker = !label.isEmpty() ? label : uri;
                        if (!marker.isEmpty()) dedup.add(marker);
                    }
                }
            }
            return dedup.isEmpty() ? Collections.emptyList() : new ArrayList<>(dedup);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static String cleanSpokenText(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\*+", "").replace("`", "");
        cleaned = CITATION_BRACKET_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        return cleaned;
    }

    static AssistantResult parseGenerateContentResponse(String responseJson, boolean wakeRequired, String modelUsed) {
        final String rawText = extractCandidateText(responseJson);
        final List<String> queries = extractGroundingQueries(responseJson);
        if (rawText.isEmpty()) {
            return new AssistantResult(!wakeRequired, false, "", "", modelUsed, queries);
        }
        return parseModelJsonText(rawText, wakeRequired, modelUsed, queries);
    }

    static AssistantResult parseModelJsonText(String rawText, boolean wakeRequired, String modelUsed) {
        return parseModelJsonText(rawText, wakeRequired, modelUsed, Collections.emptyList());
    }

    static AssistantResult parseModelJsonText(
            String rawText, boolean wakeRequired, String modelUsed, List<String> webSearchQueries) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return new AssistantResult(!wakeRequired, false, "", "", modelUsed, webSearchQueries);
        }
        String cleaned = rawText.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        final int start = cleaned.indexOf('{');
        final int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        try {
            final JSONObject obj = new JSONObject(cleaned);
            final boolean wake = !wakeRequired || obj.optBoolean("wakeDetected", false);
            final boolean followUp = wakeRequired && wake && obj.optBoolean("needsFollowUp", false);
            final String transcript = obj.optString("userTranscript", "");
            final String speech = wake ? cleanSpokenText(obj.optString("speech", "")) : "";
            return new AssistantResult(wake, followUp, transcript, speech, modelUsed, webSearchQueries);
        } catch (Exception e) {
            final String fallbackSpeech = wakeRequired ? "" : cleanSpokenText(rawText);
            return new AssistantResult(!wakeRequired, false, "", fallbackSpeech, modelUsed, webSearchQueries);
        }
    }

    private static String extractApiErrorMessage(String body, int status) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                final JSONObject obj = new JSONObject(body);
                final JSONObject err = obj.optJSONObject("error");
                if (err != null) {
                    final String msg = err.optString("message", "");
                    if (!msg.isEmpty()) return "Gemini HTTP " + status + ": " + msg;
                }
            } catch (Exception ignored) {}
        }
        return "Gemini HTTP " + status;
    }

    private static String trimError(String s) {
        if (s == null) return "";
        final String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 240 ? oneLine.substring(0, 240) + "..." : oneLine;
    }

    private static void logInfo(String msg) {
        try { Log.i(TAG, msg); } catch (Throwable ignored) {}
    }

    private static void logWarn(String msg) {
        try { Log.w(TAG, msg); } catch (Throwable ignored) {}
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

    static final class HttpError extends Exception {
        final int status;
        final String body;

        HttpError(int status, String message, String body) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }
}

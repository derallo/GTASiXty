package com.gtasixty.spatial.voice;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Pure JVM unit tests for the modular Jimmy AI voice assistant helpers.
 */
public class JimmyVoiceTest {

    @Test
    public void ringBuffer_preservesPreRollAcrossWrapAndOverflow() {
        final JimmyAudioBuffer.Pcm16RingBuffer ring = new JimmyAudioBuffer.Pcm16RingBuffer(5);
        ring.write(new short[]{1, 2, 3}, 0, 3);
        assertEquals(3, ring.length());
        assertArrayEquals(new short[]{1, 2, 3}, ring.snapshot());

        // Wrap around capacity=5: writes [4, 5, 6] -> buffer holds [2, 3, 4, 5, 6]
        ring.write(new short[]{4, 5, 6}, 0, 3);
        assertEquals(5, ring.length());
        assertArrayEquals(new short[]{2, 3, 4, 5, 6}, ring.snapshot());

        // Write larger than capacity=5 -> retains last 5 samples [12, 13, 14, 15, 16]
        ring.write(new short[]{10, 11, 12, 13, 14, 15, 16}, 0, 7);
        assertEquals(5, ring.length());
        assertArrayEquals(new short[]{12, 13, 14, 15, 16}, ring.snapshot());

        ring.clear();
        assertEquals(0, ring.length());
        assertArrayEquals(new short[0], ring.snapshot());
    }

    @Test
    public void encodePcm16ToWav_writesValidHeaderAndSamples() {
        final short[] samples = new short[]{1000, -1000, 32767, -32768};
        final byte[] wav = JimmyAudioBuffer.encodePcm16ToWav(samples, 16000);
        assertEquals(44 + samples.length * 2, wav.length);

        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));

        final ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(36 + 8, bb.getInt(4));
        assertEquals(16, bb.getInt(16));
        assertEquals(1, bb.getShort(20));
        assertEquals(1, bb.getShort(22));
        assertEquals(16000, bb.getInt(24));
        assertEquals(32000, bb.getInt(28));
        assertEquals(2, bb.getShort(32));
        assertEquals(16, bb.getShort(34));
        assertEquals(8, bb.getInt(40));
        assertEquals((short) 1000, bb.getShort(44));
        assertEquals((short) -1000, bb.getShort(46));
        assertEquals((short) 32767, bb.getShort(48));
        assertEquals((short) -32768, bb.getShort(50));
    }

    @Test
    public void trimTrailingSilence_cutsDeadTailWhileHonoringKeepTailAndMinDuration() {
        final int rate = 16000;
        final short[] samples = new short[rate * 2]; // 2.0 s
        // Active speech in first 0.7 s
        for (int i = 0; i < (int) (rate * 0.7); i++) {
            samples[i] = (short) (Math.sin(i * 0.2) * 8000);
        }
        // Remaining 1.3 s is silence
        final short[] trimmed = JimmyAudioBuffer.trimTrailingSilence(samples, rate, 0.012f, 200, 450);
        // Should end around 0.7s + 0.2s = 0.9s (14,400 samples) -> well below 2.0s
        assertTrue(trimmed.length >= (int) (rate * 0.85) && trimmed.length <= (int) (rate * 1.0));

        // Pure silence respects minDurationMs (450 ms = 7200 samples)
        final short[] silent = new short[rate];
        final short[] silentTrimmed = JimmyAudioBuffer.trimTrailingSilence(silent, rate, 0.012f, 200, 450);
        assertEquals((int) (rate * 0.45), silentTrimmed.length);
    }

    @Test
    public void synthesizeChimePcm24k_producesValidNonSilentAudio() {
        final byte[] up = JimmyAudioBuffer.synthesizeChimePcm24k(true);
        final byte[] down = JimmyAudioBuffer.synthesizeChimePcm24k(false);
        assertTrue(up.length > 4000);
        assertEquals(0, up.length % 2);
        assertEquals(up.length, down.length);

        boolean hasNonZero = false;
        for (byte b : up) {
            if (b != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero);
    }

    @Test
    public void geminiBuildRequestBody_includesSystemHistoryAudioAndOptionalScreenshot() throws Exception {
        final byte[] wav = new byte[]{1, 2, 3, 4};
        final byte[] jpg = new byte[]{5, 6, 7};
        final String withShot = JimmyGeminiClient.buildRequestBody(
                "gemini-2.5-flash",
                wav,
                jpg,
                null,
                Arrays.asList(
                        new JimmyGeminiClient.ChatTurn("user", "Hey Jimmy, where am I?"),
                        new JimmyGeminiClient.ChatTurn("model", "You are near Vespucci Beach.")
                ),
                true,
                true);

        final JSONObject req = new JSONObject(withShot);
        assertTrue(req.has("systemInstruction"));
        final JSONArray contents = req.getJSONArray("contents");
        // History is formatted inside the single role=user turn to prevent role-alternation 400s
        assertEquals(1, contents.length());
        final JSONArray userParts = contents.getJSONObject(0).getJSONArray("parts");
        assertEquals(3, userParts.length());
        final String promptText = userParts.getJSONObject(0).getString("text");
        assertTrue(promptText.contains("Recent Dialogue History:"));
        assertTrue(promptText.contains("Player: Hey Jimmy, where am I?"));
        assertTrue(promptText.contains("Jimmy: You are near Vespucci Beach."));
        assertEquals("audio/wav", userParts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"));
        assertEquals("image/jpeg", userParts.getJSONObject(2).getJSONObject("inlineData").getString("mimeType"));

        final JSONObject genCfg = req.getJSONObject("generationConfig");
        assertEquals("application/json", genCfg.getString("responseMimeType"));
        assertTrue(genCfg.has("responseSchema"));
        assertEquals(0, genCfg.getJSONObject("thinkingConfig").getInt("thinkingBudget"));

        // Without screenshot, only text + audio parts are included
        final String audioOnly = JimmyGeminiClient.buildRequestBody(
                "gemini-3-flash-preview",
                wav,
                null,
                null,
                Collections.emptyList(),
                true,
                true);
        final JSONObject req2 = new JSONObject(audioOnly);
        assertEquals(2, req2.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").length());
        assertEquals("MINIMAL", req2.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"));
    }

    @Test
    public void geminiParseResponse_handlesWakeFollowUpNonWakeMarkdownAndMalformed() throws Exception {
        final String normalEnvelope = new JSONObject()
                .put("candidates", new JSONArray().put(new JSONObject()
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                                .put("text", "{\"wakeDetected\":true,\"needsFollowUp\":false,\"userTranscript\":\"Jimmy what car is this\",\"speech\":\"That's a **Sentinel** XS!\"}"))))))
                .toString();

        final JimmyGeminiClient.AssistantResult r1 = JimmyGeminiClient.parseGenerateContentResponse(normalEnvelope, true, "gemini-2.5-flash");
        assertTrue(r1.wakeDetected);
        assertFalse(r1.needsFollowUp);
        assertEquals("Jimmy what car is this", r1.userTranscript);
        assertEquals("That's a Sentinel XS!", r1.speech);

        final String wakeOnlyEnvelope = new JSONObject()
                .put("candidates", new JSONArray().put(new JSONObject()
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                                .put("text", "```json\n{\"wakeDetected\":true,\"needsFollowUp\":true,\"userTranscript\":\"Jimmy\",\"speech\":\"Yeah?\"}\n```"))))))
                .toString();
        final JimmyGeminiClient.AssistantResult r2 = JimmyGeminiClient.parseGenerateContentResponse(wakeOnlyEnvelope, true, "gemini-2.5-flash-lite");
        assertTrue(r2.wakeDetected);
        assertTrue(r2.needsFollowUp);
        assertEquals("Yeah?", r2.speech);

        // When wakeRequired=false, needsFollowUp must be ignored so direct questions always speak
        final JimmyGeminiClient.AssistantResult r2Direct = JimmyGeminiClient.parseGenerateContentResponse(wakeOnlyEnvelope, false, "gemini-flash-latest");
        assertTrue(r2Direct.wakeDetected);
        assertFalse(r2Direct.needsFollowUp);
        assertEquals("Yeah?", r2Direct.speech);

        final String noWakeEnvelope = new JSONObject()
                .put("candidates", new JSONArray().put(new JSONObject()
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                                .put("text", "{\"wakeDetected\":false,\"needsFollowUp\":false,\"userTranscript\":\"pass me the controller\",\"speech\":\"Should be cleared\"}"))))))
                .toString();
        final JimmyGeminiClient.AssistantResult r3 = JimmyGeminiClient.parseGenerateContentResponse(noWakeEnvelope, true, "gemini-2.5-flash-lite");
        assertFalse(r3.wakeDetected);
        assertEquals("", r3.speech);

        final JimmyGeminiClient.AssistantResult malformed = JimmyGeminiClient.parseGenerateContentResponse("not json", true, "gemini-2.5-flash");
        assertFalse(malformed.wakeDetected);
        assertEquals("", malformed.speech);
    }

    @Test
    public void ttsAndConfigHelpers_handleWavStrippingFallbacksAndMasking() {
        final byte[] withHeader = new byte[50];
        withHeader[0] = 'R';
        withHeader[1] = 'I';
        withHeader[2] = 'F';
        withHeader[3] = 'F';
        withHeader[44] = 42;
        withHeader[49] = 99;
        final byte[] stripped = JimmyTtsPlayer.stripWavHeaderIfPresent(withHeader);
        assertEquals(6, stripped.length);
        assertEquals(42, stripped[0]);
        assertEquals(99, stripped[5]);

        final byte[] rawPcm = new byte[]{1, 2, 3, 4};
        assertArrayEquals(rawPcm, JimmyTtsPlayer.stripWavHeaderIfPresent(rawPcm));

        assertEquals(
                "https://api.elevenlabs.io/v1/text-to-speech/pNInz6obpgDQGcFmaJgB/stream?output_format=pcm_24000",
                JimmyTtsPlayer.buildElevenLabsUrl(JimmyConfig.VOICE_ADAM));
        assertEquals(JimmyConfig.VOICE_LAURA, JimmyConfig.resolveFallbackVoiceId(JimmyConfig.VOICE_RACHEL));
        assertEquals(JimmyConfig.VOICE_ADAM, JimmyConfig.resolveFallbackVoiceId(JimmyConfig.VOICE_GAME_SHOW_HOST));

        assertEquals("(not set)", JimmyConfig.maskKey(""));
        assertEquals("******", JimmyConfig.maskKey("abc"));
        assertTrue(JimmyConfig.maskKey("AIza1234567890WXYZ").equals("AIza...WXYZ"));
    }

    @Test
    public void geminiModelDiscovery_filtersAndPrioritizesLatestAliases() throws Exception {
        final String json = new JSONObject()
                .put("models", new JSONArray()
                        .put(new JSONObject()
                                .put("name", "models/gemini-2.5-flash")
                                .put("supportedGenerationMethods", new JSONArray().put("generateContent")))
                        .put(new JSONObject()
                                .put("name", "models/gemini-2.5-flash-preview-tts")
                                .put("supportedGenerationMethods", new JSONArray().put("generateContent")))
                        .put(new JSONObject()
                                .put("name", "models/text-embedding-004")
                                .put("supportedGenerationMethods", new JSONArray().put("embedContent")))
                        .put(new JSONObject()
                                .put("name", "models/gemini-3-flash-preview")
                                .put("supportedGenerationMethods", new JSONArray().put("generateContent")))
                        .put(new JSONObject()
                                .put("name", "models/gemini-2.5-pro")
                                .put("supportedGenerationMethods", new JSONArray().put("generateContent"))))
                .toString();

        final List<String> parsed = JimmyGeminiClient.parseAvailableModels(json);
        assertEquals("gemini-flash-latest", parsed.get(0));
        assertEquals("gemini-flash-lite-latest", parsed.get(1));
        assertTrue(parsed.contains("gemini-3-flash-preview"));
        assertTrue(parsed.contains("gemini-2.5-flash"));
        assertTrue(parsed.contains("gemini-2.5-pro"));
        assertFalse(parsed.contains("gemini-2.5-flash-preview-tts"));
        assertFalse(parsed.contains("text-embedding-004"));
        assertTrue(parsed.indexOf("gemini-3-flash-preview") < parsed.indexOf("gemini-2.5-pro"));
    }

    @Test
    public void audioLevel_detectsQuietAndNormalSpeechClearly() {
        final short[] silence = new short[320];
        assertEquals(0, JimmyAudioBuffer.computeLevel0To100(silence, 0, silence.length));

        final short[] speech = new short[320];
        for (int i = 0; i < speech.length; i++) {
            speech[i] = (short) (Math.sin(i * 0.3) * 1800); // ~0.055 peak
        }
        final int lvl = JimmyAudioBuffer.computeLevel0To100(speech, 0, speech.length);
        assertTrue("Expected audible speech level >= 15, got " + lvl, lvl >= 15);
    }

    @Test
    public void lanKeyServer_extractsKeysAndParsesFormBody() throws Exception {
        final String sampleGemini = "AIzaSyD1234567890abcdefghijklmnopqrstuv";
        assertEquals(sampleGemini, JimmyLanKeyServer.extractGeminiKey("  key: " + sampleGemini + " ! "));
        assertEquals("custom-nonstandard", JimmyLanKeyServer.extractGeminiKey("  custom-nonstandard  "));

        final String sampleEleven = "sk_1234567890abcdef1234567890abcdef";
        assertEquals(sampleEleven, JimmyLanKeyServer.extractElevenLabsKey("pasted " + sampleEleven + " here"));

        assertEquals("Short answer.", JimmyGeminiClient.extractPartialJsonString(
                "{\"wakeDetected\":true,\"needsFollowUp\":false,\"speech\":\"Short answer.\"", "speech"));
        assertNull(JimmyGeminiClient.extractPartialJsonString(
                "{\"wakeDetected\":true,\"speech\":\"incomp", "speech"));

        assertEquals("Hello world!", JimmyTtsPlayer.buildGeminiTtsPrompt("Hello **world**!"));
        assertEquals("", JimmyTtsPlayer.buildGeminiTtsPrompt("   ***  "));

        final String encoded = "geminiKey=" + URLEncoder.encode(sampleGemini, "UTF-8")
                + "&elevenLabsKey=" + URLEncoder.encode(sampleEleven, "UTF-8")
                + "&voiceId=" + URLEncoder.encode(JimmyConfig.VOICE_ADAM, "UTF-8")
                + "&enableNow=on";
        final Map<String, String> parsed = JimmyLanKeyServer.parseFormUrlEncoded(encoded);
        assertEquals(sampleGemini, parsed.get("geminiKey"));
        assertEquals(sampleEleven, parsed.get("elevenLabsKey"));
        assertEquals(JimmyConfig.VOICE_ADAM, parsed.get("voiceId"));
        assertEquals("on", parsed.get("enableNow"));
    }

    @Test
    public void geminiWebSearch_buildsToolsExtractsGroundingAndStripsCitations() throws Exception {
        final String structuredWithSearch = JimmyGeminiClient.buildRequestBody(
                "gemini-flash-lite-latest",
                null,
                null,
                "How do I solve the piano puzzle?",
                Collections.emptyList(),
                false,
                JimmyGeminiClient.ThinkingMode.LEVEL_MINIMAL,
                JimmyGeminiClient.SearchMode.SEARCH_STRUCTURED);
        final JSONObject reqStructured = new JSONObject(structuredWithSearch);
        assertTrue(reqStructured.has("tools"));
        assertTrue(reqStructured.getJSONArray("tools").getJSONObject(0).has("google_search"));
        assertEquals("application/json", reqStructured.getJSONObject("generationConfig").getString("responseMimeType"));
        assertTrue(reqStructured.getJSONObject("generationConfig").has("responseSchema"));

        final String promptJsonWithSearch = JimmyGeminiClient.buildRequestBody(
                "gemini-2.5-flash",
                null,
                null,
                "How do I solve the piano puzzle?",
                Collections.emptyList(),
                false,
                JimmyGeminiClient.ThinkingMode.BUDGET_ZERO,
                JimmyGeminiClient.SearchMode.SEARCH_PROMPT_JSON);
        final JSONObject reqPromptJson = new JSONObject(promptJsonWithSearch);
        assertTrue(reqPromptJson.has("tools"));
        assertTrue(reqPromptJson.getJSONArray("tools").getJSONObject(0).has("google_search"));
        assertFalse(reqPromptJson.getJSONObject("generationConfig").has("responseMimeType"));
        assertFalse(reqPromptJson.getJSONObject("generationConfig").has("responseSchema"));
        final String sysText = reqPromptJson.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text");
        assertTrue(sysText.contains("\"wakeDetected\""));
        assertTrue(sysText.contains("\"speech\""));

        final String noSearch = JimmyGeminiClient.buildRequestBody(
                "gemini-flash-lite-latest",
                null,
                null,
                "Hi Jimmy",
                Collections.emptyList(),
                false,
                JimmyGeminiClient.ThinkingMode.LEVEL_MINIMAL,
                JimmyGeminiClient.SearchMode.NONE_STRUCTURED);
        assertFalse(new JSONObject(noSearch).has("tools"));

        assertEquals(
                JimmyGeminiClient.SearchMode.SEARCH_PROMPT_JSON,
                JimmyGeminiClient.defaultSearchMode("gemini-2.5-flash", true));
        assertEquals(
                JimmyGeminiClient.SearchMode.SEARCH_PROMPT_JSON,
                JimmyGeminiClient.defaultSearchMode("gemini-flash-lite-latest", true));
        assertEquals(
                JimmyGeminiClient.SearchMode.NONE_STRUCTURED,
                JimmyGeminiClient.defaultSearchMode("gemini-flash-lite-latest", false));

        final String chunkOnlyEnvelope = new JSONObject()
                .put("candidates", new JSONArray().put(new JSONObject()
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                                .put("text", "{\"wakeDetected\":true,\"needsFollowUp\":false,\"userTranscript\":\"boss\",\"speech\":\"Use fire spells.\"}"))))
                        .put("groundingMetadata", new JSONObject()
                                .put("groundingChunks", new JSONArray().put(new JSONObject()
                                        .put("web", new JSONObject()
                                                .put("title", "ign.com")
                                                .put("uri", "https://example.com/guide")))))))
                .toString();
        final JimmyGeminiClient.AssistantResult chunkGrounded = JimmyGeminiClient.parseGenerateContentResponse(
                chunkOnlyEnvelope, false, "gemini-flash-lite-latest");
        assertTrue(chunkGrounded.usedWebSearch);
        assertEquals("ign.com", chunkGrounded.webSearchQueries.get(0));

        assertEquals(
                "Press the silent keys in order from left to right.",
                JimmyGeminiClient.cleanSpokenText("Press the **silent** keys [1] in order [2, 3] from left to right."));

        final String groundedEnvelope = new JSONObject()
                .put("candidates", new JSONArray().put(new JSONObject()
                        .put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                                .put("text", "{\"wakeDetected\":true,\"needsFollowUp\":false,\"userTranscript\":\"piano puzzle\",\"speech\":\"Press the five silent keys [1, 2].\"}"))))
                        .put("groundingMetadata", new JSONObject()
                                .put("webSearchQueries", new JSONArray()
                                        .put("Silent Hill 1 piano puzzle solution")
                                        .put("Silent Hill 1 piano puzzle solution")))))
                .toString();
        final JimmyGeminiClient.AssistantResult grounded = JimmyGeminiClient.parseGenerateContentResponse(
                groundedEnvelope, false, "gemini-flash-lite-latest");
        assertTrue(grounded.usedWebSearch);
        assertEquals(1, grounded.webSearchQueries.size());
        assertEquals("Silent Hill 1 piano puzzle solution", grounded.webSearchQueries.get(0));
        assertEquals("Press the five silent keys.", grounded.speech);
    }

    @Test
    public void lanKeyServer_servesFormAcceptsPostAndAutoCloses() throws Exception {
        final JimmyLanKeyServer server = new JimmyLanKeyServer();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<JimmyLanKeyServer.ReceivedKeys> receivedRef = new AtomicReference<>();

        final String urlStr = server.startOnPortForTest(
                0, false, false, JimmyConfig.VOICE_ADAM, keys -> {
                    receivedRef.set(keys);
                    latch.countDown();
                });
        try {
            assertTrue(server.isRunning());
            assertTrue(urlStr.startsWith("http://127.0.0.1:"));

            // 1. GET /key serves the HTML form
            final HttpURLConnection getConn = (HttpURLConnection) new URL(urlStr).openConnection();
            getConn.setRequestMethod("GET");
            assertEquals(200, getConn.getResponseCode());
            final String getBody = readResponseText(getConn);
            assertTrue(getBody.contains("<form method=\"POST\" action=\"/key\">"));
            assertTrue(getBody.contains("https://aistudio.google.com/apikey"));
            getConn.disconnect();

            // 2. POST /key delivers keys and automatically shuts down the server
            final String fakeGemini = "AIzaSyTest1234567890123456789012345678";
            final String fakeEleven = "sk_test12345678901234567890123456";
            final String postPayload = "geminiKey=" + URLEncoder.encode("here: " + fakeGemini, "UTF-8")
                    + "&elevenLabsKey=" + URLEncoder.encode(fakeEleven, "UTF-8")
                    + "&voiceId=" + URLEncoder.encode(JimmyConfig.VOICE_RACHEL, "UTF-8")
                    + "&enableNow=on";
            final HttpURLConnection postConn = (HttpURLConnection) new URL(urlStr).openConnection();
            postConn.setRequestMethod("POST");
            postConn.setDoOutput(true);
            postConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = postConn.getOutputStream()) {
                os.write(postPayload.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, postConn.getResponseCode());
            final String postBody = readResponseText(postConn);
            assertTrue(postBody.contains("Saved to Quest 3"));
            postConn.disconnect();

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            final JimmyLanKeyServer.ReceivedKeys keys = receivedRef.get();
            assertNotNull(keys);
            assertEquals(fakeGemini, keys.geminiKey);
            assertEquals(fakeEleven, keys.elevenLabsKey);
            assertEquals(JimmyConfig.VOICE_RACHEL, keys.voiceId);
            assertTrue(keys.enableNow);
            assertFalse(server.isRunning());
        } finally {
            server.stop();
        }
    }

    private static String readResponseText(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }
}

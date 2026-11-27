# Experimental "Jimmy" AI Voice Assistant (`com.gtasixty.spatial.voice`)

Self-contained, modular hands-free AI gaming co-pilot for GTA-Sixty on Meta Quest 3.

## How It Works
- **Wake Word & VAD (`JimmyAudioEngine`, `JimmyAudioBuffer`)**: Continuously captures 16 kHz mono PCM from the Quest mic with `AcousticEchoCanceler` + `NoiseSuppressor`, an adaptive ambient noise-floor gate, 1.0 s circular pre-roll buffer, and trailing silence trimming.
- **Game Screenshot (`GameScreenshotCapturer`)**: Captures a 768×432 JPEG frame asynchronously via `PixelCopy` (`SurfaceView` for Capture/PC/PS Remote Play, `Window`/`WebView` fallback for Xbox Wi-Fi Direct) with a 600 ms timeout so rendering never stalls.
- **Multimodal Reasoning & Live Web Search (`JimmyGeminiClient`)**: Uses the user's Gemini API key (`x-goog-api-key`) with SSE streaming, cached per-model `ThinkingMode` / `SearchMode` compatibility, and live Google Search grounding (`tools: [{google_search: {}}]`, toggleable in Settings) for game walkthroughs, puzzle solutions, and up-to-date info. Short utterances (`< 1.7 s`) run a fast audio-only wake check without web search before capturing a screenshot; longer phrases snap the screenshot and send `(audio + JPEG)` in a single round-trip.
- **Voice Playback (`JimmyTtsPlayer`)**: Streams raw 24 kHz mono PCM from ElevenLabs (`eleven_flash_v2_5`, 160 ms pre-buffer, automatic 402/404 premade voice fallback to Adam/Laura), or falls back to Gemini Native TTS (`gemini-2.5-flash-preview-tts`) when no ElevenLabs key is provided.
- **Isolated Storage**: All keys and toggles live in `SharedPreferences("gta60_jimmy_voice")`.
- **Phone Setup (`JimmyLanKeyServer`)**: Opens an ephemeral `java.net.ServerSocket` on `http://<quest-ip>:8091/key` (with automatic port fallback `[8091, 8092, 8093, 3001]`) from the **Jimmy AI** settings dialog so you can paste keys from your phone on the same Wi-Fi, then shuts down immediately after saving or closing the dialog.

## Quick ADB Configuration & Testing
Configure keys without typing on the VR keyboard:
```powershell
adb shell am broadcast -a com.gtasixty.spatial.JIMMY_VOICE --ez enabled true --es geminiKey "YOUR_GEMINI_KEY" --es elevenLabsKey "YOUR_ELEVENLABS_KEY"
```
Trigger an immediate screenshot + question + voice reply test (with optional `--ez webSearch true|false`):
```powershell
adb shell am broadcast -a com.gtasixty.spatial.JIMMY_VOICE --ez webSearch true --es ask "What game am I playing right now?"
```

## Rip-Out Checklist (4 Steps)
1. Delete `quest-spatial-app/src/main/java/com/gtasixty/spatial/voice/` and `quest-spatial-app/src/test/java/com/gtasixty/spatial/voice/`.
2. Remove `<item name="jimmy_voice_panel" type="id" />` from `quest-spatial-app/src/main/res/values/ids.xml` and the `jimmyPanel` hooks in `SpatialMainActivity.kt`.
3. Remove the `jimmyVoice` lifecycle hooks + `JimmyVoiceModule.createTopBarButton(...)` in `MainActivity.java`.
4. Remove the 1-line `JimmyVoiceModule.createTopBarButton(...)` calls in `DirectController.java` and `PsRemoteController.java`.

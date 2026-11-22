# Direct mode: Quest 3 -> Xbox Series X, no PC in the path

> **Current status (2026-11-21): WORKING end-to-end on Quest 3 (`2560x1440 @ 60.0 fps`, `18-22.4 Mbps`, `dec ~5.6 ms`, `jb ~8.9-12.1 ms`, `rtt = 1.0 ms`, 48 kHz stereo Opus audio).**
> Sign-in, console discovery, session provisioning, SDP/ICE exchange, 4-channel handshake, hardware H.264 decoding (`c2.qti.avc.decoder`), low-latency AAudio output, and gamepad forwarding all run directly on the headset via the Android system WebView (`TriChromeWebView` 156) without an NDK/libdatachannel port.

Direct mode makes the headset a first-class xhome client: it signs in to the Microsoft account,
provisions a session on the console over the LAN, and receives the console's own WebRTC video
stream. Nothing is routed through the PC, so there is no PC decode/upscale/re-encode hop.

```
PC mode (default)                     Direct mode (working)
Xbox --WebRTC--> PC --TCP--> Quest    Xbox --WebRTC(ICE/SRTP, LAN)--> Quest
        decode/upscale/encode                  Java: auth + REST signaling
                                               WebView/Chromium: WebRTC + H.264 HW decode
```

## Architecture

The xhome protocol is ordinary WebRTC plus a REST signaling service:

* `POST /v2/login/user`, `GET /v6/servers/home`, `POST /v5/sessions/home/play`,
  `/connect`, `/sdp`, `/ice`, `/configuration`, `/keepalive` on the region host returned by the login.
* A peer connection with audio `sendrecv` (m-line 0), video `recvonly` (m-line 1), and four data
  channels: `chat/chatV1`, `control/controlV1`, `input/1.0`, `message/messageV1`.

| layer | implementation |
|---|---|
| MSA device-code sign-in, XBL/XSTS, gssv login, transfer token | `XboxDirect.java` (`HttpsURLConnection`) |
| Session provisioning, SDP/ICE exchange, `/configuration` candidate synthesis, keepalive | `XboxDirect.java`, driven by `DirectController.java` |
| ICE, DTLS-SRTP, H.264 hardware depacketize/decode (`c2.qti.avc.decoder`), Opus AAudio output, data channels, presentation feedback | `assets/direct/direct.js` + `index.html` in the system WebView |
| UI, console picker, gamepad capture | `DirectController.java` + the existing `MainActivity` input code |

## Assumptions Challenged & Root Causes Fixed (2026-11-21)

The previous handoff concluded that the console rejected the Quest 3's Android system WebView at the
WebRTC transport layer (`PerformSdpExchangeV1Command failed` after 30 s) and proposed porting C++
`libdatachannel` + `mbedTLS` via the Android NDK.

Challenging that assumption against **Better xCloud** (`com.redphx.betterxc`, which is sideloaded on
this exact Quest 3, contains zero native `.so` libraries, and streams from this exact console using
the same system `TriChromeWebView` 156) and tracing the wire exchange exposed two concrete bugs:

1. **At the moment `POST /sdp` fails or succeeds, no WebRTC packets or ICE candidates have been sent.**
   During `exchangeSdp()`, only HTTPS JSON has been sent to `gssv-play-prod.xboxlive.com`. In the
   earlier attempts:
   * `direct.js` had defined `raiseH264Level()` and `conformOffer()`, but neither function was being
     called (`var wire = clean;`), AND `new RTCPeerConnection({})` was created without
     `bundlePolicy: 'max-bundle'` (causing Chromium to gather 3 separate UDP ports across `m=audio`,
     `m=video`, and `m=application` and offer H.264 Level 3.1 `4d001f`).
   * `XboxDirect.java` had diverged from `StreamApi.cpp` by sending `BROWSER_DEVICE_INFO` on every
     request, `User-Agent: GTA-Sixty-Direct/1.0 (Android)`, and `reliableinput`/`unreliableinput`
     version 9 in `/sdp` without opening V2 input channels.
   * Restoring `StreamApi.cpp`'s exact headers/configuration (`NATIVE_DEVICE_INFO` on `/play`,
     `input` maxVersion 8, `messageType: "iceCandidate"` on `/ice`), enabling `bundlePolicy: 'max-bundle'`,
     raising H.264 to Level 4.2 (`4d002a`), and emitting the exact 51-line PC-shaped SDP in
     `conformOffer()` caused the console to **accept the Quest's SDP offer in ~350 ms**.

2. **`XboxDirect.pollExchange()` threw an exception on every HTTP 200 success response.**
   While `/sdp` is pending, `GET /sdp` returns HTTP 204. When the console finishes and accepts the
   offer, `GET /sdp` returns HTTP 200 with:
   `{"exchangeResponse":"...","errorDetails":{"code":null,"message":null}}`.
   `pollExchange()` checked `if (j.optJSONObject("errorDetails") != null)` without checking whether
   `code` and `message` were JSON `null` (`org.json.JSONObject.optString("code", "")` returns the
   4-character string `"null"` for a JSON `null` value), throwing `exchange failed: null null`
   the instant the console accepted the offer.

## Techniques Borrowed from Better xCloud & PC Mode

* **Decoupled Audio/Video MediaElements & RTCP `CNAME` (`conformAnswer`)**:
  The console's answer assigns `a=msid:0` to audio and `a=msid:1` to video, both sharing the same
  RTCP `cname`. Attaching both to one `<video>` element caused the video `ontrack` event to overwrite
  the audio stream (triggering `NetEq` packet buffer flushes every 4 s), while sharing a `cname`
  caused Chromium's `RtpStreamsSynchronizer` to lock the video jitter buffer floor to ~32 ms.
  Separating `<video id="v" muted>` and `<audio id="a">` and giving `m=video` a distinct `cname`
  in `conformAnswer()` dropped the video jitter buffer delay from **32.4 ms down to 8.9-12.1 ms**
  and enabled 48 kHz low-latency `AAudio` (`AUDIO_OUTPUT_FLAG_FAST`) audio playback.
* **High-bitrate remote description hints & `requestVideoFrameCallback` presentation feedback**:
  Appending `b=AS:50000` and `x-google-min-bitrate=20000;x-google-start-bitrate=25000;x-google-max-bitrate=50000`
  to the answer SDP plus sending 43-byte `ReportMetadata` (type 1) presentation feedback frames from
  `video.requestVideoFrameCallback` (clamping reported decode latency <= 3 ms like Better xCloud's
  `patchStreamMetadata`) raised the console's stream bitrate from **~14.8 Mbps (`REMB 0`) to 18.0-22.4 Mbps**
  at **2560x1440 @ 60.0 fps**.
* **`GET /configuration` `serverDetails` candidate synthesis (`appendConfigServerCandidates`)**:
  Borrowed from Better xCloud's `XhomeInterceptor.handleConfiguration`: queries
  `/v5/sessions/home/<id>/configuration` and synthesizes direct host candidates on port `9002` and
  the reported port for `ipAddress`/`ipV4Address`/`ipV6Address` alongside Teredo expansion.
* **`WarningForBeingIdle` keep-alive handling**:
  Borrowed from Better xCloud's `remotePlayKeepAlive`: when the console sends
  `/streaming/sessionLifetimeManagement/serverInitiatedDisconnect` with `reason: "WarningForBeingIdle"`,
  `direct.js` replies with `clientKeepAlive` and a gamepad report instead of treating the warning as
  a fatal disconnect.
* **`end-of-candidates` filtering**:
  Fixed `rewriteRemoteCandidates()` so `"a=end-of-candidates"` is skipped after stripping `"a="`
  rather than turning into `"candidate:end-of-candidates"` (`9/9` remote candidates accepted).
* **120 Hz Display Refresh Rate (`modeId 90`, `8.333 ms` vsync) vs. 72 Hz Pulldown Judder**:
  A 3.8-minute live driving capture (`build/direct-drive-live.log` + `SurfaceFlinger --latency`) showed
  that Horizon OS defaults 2D Android panels to **72.0 Hz (`modeId 137`, `13.889 ms` vsync)**, forcing
  60 FPS streams into a **4:1 pulldown (`13.89 ms` x4, then `27.78 ms` double-hold 12 times/sec)** and
  30 FPS games into **2.4 vsyncs/frame (`27.78 ms` vs `41.67 ms` holds)**. `MainActivity` and
  `scripts/install-quest-apk.ps1` now request **120.0 Hz (`modeId 90`, `vsync_period = 8333333 ns`)**
  (with a `120 Hz / 90 Hz` button in the Direct bar), dividing both 60 FPS (`2:2` vsyncs) and 30 FPS
  (`4:4` vsyncs) evenly with zero pulldown judder.
* **On-Quest Adreno 740 WebGL2 GPU Pipeline (30 FPS Duplicate Detector + AMD FidelityFX RCAS + 30->60 Optical-Flow Frame Generation)**:
  Because hardware H.264 decoding runs on the Snapdragon XR2 Gen 2's dedicated Video DSP
  (`c2.qti.avc.decoder`), `VrApi` telemetry during live driving showed the Adreno 740 GPU sitting at
  only **29% utilization at 492 MHz**. `direct.js` now includes a zero-copy 3-texture WebGL2 pipeline
  (`~2.2 ms` total GPU+CPU time per frame at `2560x1440`):
  * **Live GPU Duplicate Detector (`uniq` FPS counter)**: Downsamples incoming frames into a `160x90`
    luma+diff FBO and `4x4` regional summary (`MAD` + `maxBlock`) to distinguish real 30 FPS game
    frames from the Xbox Series X's 60 Hz duplicate RTP packets (`uniq = 30.0 fps, dup = 50%`).
  * **Adaptive Cadence FG Gate**: Engages 30->60 Frame Generation when `dup >= 20%` and `uniq <= 46.5`,
    and automatically switches to 1:1 RCAS sharpening when the source outputs native 60 FPS.
  * **Coarse-to-Fine Bidirectional Patch-SAD Optical Flow (`160x90`)**: Estimates 2D motion vectors up
    to `+-96 px` (`2560x1440` equivalent) with 1-texel and 0.5-texel bilinear refinement plus
    photometric confidence.
  * **2-Pass Explained-Warp + GTA HUD Protection + Standalone AMD FidelityFX RCAS / SuperRes (`2560x1440` or `3200x1800`)**:
    Evaluates smoothed flow with continuous `smoothstep` disocclusion weighting into a full-res
    `fboMid`, protects the bottom-left minimap, top-right HUD, and center reticle from warping, and
    applies standalone 1-pixel AMD FidelityFX RCAS sharpening (with optional `3200x1800` edge-directed
    SuperRes reconstruction) on the final pass. Exposed via the **`Enhance`** button in the Direct
    bar (`Off`, `RCAS`, `SuperRes`, `FG 60`, `FG60+SR`).
* **Choreographer Vsync-Locked Zero-Allocation Gamepad Forwarding & Idle Anti-Kick**:
  Button presses/releases (`ACTION_DOWN`/`ACTION_UP`) now flush immediately (`0 ms` delay), while
  analog stick/trigger axes are coalesced on Android's `Choreographer.FrameCallback` (`120/90 Hz`)
  into a pre-allocated 37-byte `ArrayBuffer` (`GTA60Direct.p(...)`), eliminating the old
  `postDelayed(8)` / `16 ms` quantization jitter (`41.6 Hz` dips during camera pans). The 20 s idle
  keep-alive now pairs `clientKeepAlive` with a 1-LSB (`+-1/32767`) sub-deadzone pulse so the console
  does not end idle sessions with `KickForBeingIdle` after 12 minutes.

## Measured On-Device Telemetry (2026-11-21)

Verified live on Quest 3 (`192.168.68.80`) -> Xbox Series X (`Great room xbox`):

```
session state: Provisioned
signaling complete: 9 remote candidates
js: ontrack audio: playoutDelayHint=0 jitterBufferTarget=0
js: ontrack video: playoutDelayHint=0 jitterBufferTarget=0
js: audio.play ok (paused=false, muted=false)
js: answer applied (9/9 candidates)
js: data channels open; handshake
cr_MediaCodecBridge: create MediaCodec video decoder, mime video/avc, decoder name c2.qti.avc.decoder
js: direct: streaming
js: video.play ok
js: perf: 2560x1440 60.0fps 22.4Mbps dec=6.17ms jb=15.6ms rtt=1.0ms lost=0
js: perf: 2560x1440 60.0fps 18.0Mbps dec=5.92ms jb=8.9ms rtt=1.0ms lost=0 aud=10103/711360
```

## Using it

1. Rebuild and install the APK (`scripts\build-quest-apk.ps1`, then
   `scripts\install-quest-apk.ps1 -Launch`).
2. In the headset app press **Mode: PC** to switch to **Mode: Direct**.
3. **Sign in**: the app shows a Microsoft device code. Open `https://www.microsoft.com/link` on a
   phone/PC and enter it. The refresh token is stored AES-GCM-encrypted with an Android Keystore key.
4. **Console** picks the console (auto-selects when the account has one).
5. **Start direct** provisions the session and attaches. The page overlays fps / Mbps / jitter
   buffer / packet-loss stats; the top HUD auto-hides **5 s** after streaming begins (and re-hides
   **8 s** after tapping back open or pressing `Enhance` / `Look` / `Hz`). The controller is
   forwarded over the `input` data channel (including `ReportVibration` rumble to paired gamepads),
   and the current pad state is re-sent every 20 s so the console does not idle the session out.
6. Use the unified **Enhance** button to cycle `Off` -> `RCAS` -> `SuperRes` -> `FG 60` ->
   `FG60+SR`, and **Look** to cycle `Normal` -> `Vivid` -> `Night` color grading on the final pass.
7. **PC mode** returns to the relay path (and stops the direct session).

### Controller reconnect after the console sleeps (2026-11-21)

Symptom: after the Xbox sleeps and the game is resumed, the pad opens the Xbox guide and navigates
the menus but does nothing inside the running game - and restarting the game is not acceptable.

The console assigns *player 1* to the pad that is present when the session attaches. The headset's
pad is not a physical console pad: it is a **virtual pad at index 0** created by the `input` data
channel. Two things go wrong around a sleep:

1. The console can keep the headset's virtual pad on *player 1* after the session is re-created,
   while a controller that reconnected to the console itself is player 2: the system UI answers any
   pad, a game binds to player 1.
2. A game resumed by Quick Resume can keep the *previous* virtual pad's device binding; the new
   session's pad is a new device and the game ignores it.

**Do not remove pad 0 to fix either case.** Two 2026-11-21 builds that skipped or released the
virtual pad when no controller was attached were kicked by the console with
`KickForNoClientConnection` **~57 s after streaming started, every time**
(`build/controller-test.log`). The pad reports double as the client's liveness signal.

The client now does this:

* **Pad 0 stays announced for the whole session**, and the 20 s anti-idle keep-alive always sends
  the current (neutral) pad report - even with no controller attached. `DirectController` watches
  `InputManager` for a real `SOURCE_GAMEPAD`; a controller *appearing* (Bluetooth reconnect after
  sleep, headset resume) triggers a `gamepadChanged add/remove/add` re-announce, giving a resumed
  game a fresh pad-arrival event.
* **Fix pad** (HUD button, next to `Stop`) replays that `gamepadChanged add/remove/add` plus a
  neutral report on demand - the escape hatch when a game still ignores the pad, with no need to
  close it.
* **Clean teardown only.** `Stop`, a failed stream, `PC mode` and app exit neutralize the pad and
  release it, then wait 350 ms before closing the peer connection and the console session. Closing
  in the same JS turn can drop the release message, which is what left a stale pad (and a held
  button) behind in earlier reports. This mirrors the PC path's `sendNeutralGamepadBlocking()`.
* **HUD access.** The Direct bar auto-hides 5 s into a stream; a small always-visible **Controls**
  chip in the top-left brings it back, so Stop/Start no longer requires switching to PC mode (the
  2026-11-21 report: the operator had to switch modes to restart Direct).
* **Stuck-start watchdog.** If the console accepts the SDP offer but no media starts within 35 s,
  the session is released and the bar says so, instead of sitting on "Starting WebRTC" for ever.
* **Stale session cleanup.** The provisioned session id is persisted and `play` releases an
  orphaned one first, so a process killed mid-session does not hold the console's single slot.
* **Standby / wake audio & GPU recovery.** Horizon OS can leave WebView's `AAudio` stream stuck in
  `state:stopped` / `mutedState:appOps` and pause `requestVideoFrameCallback` (`gpu=0.00ms`) after
  headset standby. `MainActivity` (`onPause`, `onResume`, `onWindowFocusChanged`) and WebView
  `visibilitychange` call `GTA60Direct.onResume()`, which detaches and re-attaches `<audio>`
  `srcObject` (80 ms bounce + `audio.play()`), resets any lost WebGL2 context / `rVFC` loop, and
  requests a fresh video keyframe.
* A `disconnected` WebRTC state that does not recover within 20 s is reported as a console sleep /
  network drop and the session is released instead of silently holding the pad for ever.

Log lines to confirm the behaviour: `local gamepad present/absent (...)`,
`no gamepad on the headset; pad 0 stays announced`, `gamepad connected on the headset; pad 0
re-announced`, `pad re-synced (gamepadChanged add/remove/add)`, and `released virtual pad before
stopping`.

## WebGL2 Adreno v3 Pipeline & Step 1-2 Polish (2026-11-21)

* **2-Level Optical Flow Pyramid + Occlusion Check (`direct.js`)**: Motion estimation upgrades
  from single-level to a coarse-to-fine **`80x45 -> 320x180`** pyramid (`MAX_UV = 0.08`) with
  bidirectional consistency checking to suppress disocclusion halos around HUD and foreground cars.
* **Async WebGL2 PBO Duplicate Detection (`direct.js`)**: Replaces the synchronous `readPixels`
  stall with a 2-slot WebGL2 `PIXEL_PACK_BUFFER` ring (`pboRing[0..1]`), `fenceSync`, and
  `getBufferSubData` feeding phase-locked cadence classification (`classifyDupeCadence`), cutting
  `FG60+SR` frame-processing time from `~2.95 ms` (v2 sync) down to **`1.16 ms`** (`100%` PBO).
* **1:1 2560x1440 Panel Sizing & Quest 3 Clock Pinning**:
  * `quest-app/src/main/AndroidManifest.xml` sets `<layout android:defaultWidth="2048dp" android:defaultHeight="1152dp" />`
    (verified in Horizon OS logs as `requestedSurfaceSize = 2560x1440`, matching WebRTC decode 1:1).
  * `scripts/install-quest-apk.ps1` and `scripts/bench-direct.ps1` set `debug.oculus.gpuLevel 4` and
    `debug.oculus.cpuLevel 4`.
* **Direct Mode Polish (`DirectController.java` / `direct.js` / `AndroidManifest.xml`)**:
  * **Look presets**: `Normal`, `Vivid`, and `Night` applied inside the final `FRAG_RCAS` shader pass.
  * **Gamepad rumble**: Xbox `ReportVibration` (`type 128`) packets forward from `direct.js` via
    `GTA60Bridge.onRumble` to Android `VibratorManager`/`CombinedVibration` (API 31+, legacy fallback).
  * **Auto-hide HUD**: Collapses automatically 5 s after stream start and 8 s after toggle/button presses.
* **Automated 5-Mode Benchmark (`scripts/bench-direct.ps1`)**:
  Cycles modes `0..4`, captures `GTA60Direct` telemetry, `VrApi` stats (when active), `SurfaceFlinger --latency`
  p50/p95 frame intervals, and `gfxinfo` into `build/bench/*.{csv,md,log}`:

| Mode | n | In FPS | Uniq FPS | Out FPS | FG FPS | Dup% | PBO% | GPU (ms) | Dec (ms) | JB (ms) | Mbps | SF FPS | SF p50/p95 (ms) | Drop/Lost |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|
| Off | 9 | 59.9 | 59.9 | 59.9 | 0 | 0% | 0% | 0 | 5.38 | 26.9 | 19.6 | 60 | 16.67 / 16.67 | 0/0 |
| RCAS | 9 | 59.9 | 59.8 | 59.8 | 0 | 0% | 0% | 0.44 | 5.63 | 22.8 | 20.2 | 62 | 16.67 / 16.67 | 0/0 |
| SuperRes | 9 | 59.9 | 59.7 | 59.7 | 0 | 0% | 0% | 0.37 | 5.5 | 21.5 | 19.8 | 63 | 16.67 / 16.67 | 0/0 |
| FG60 | 9 | 59.9 | 29.7 | 59.1 | 29.7 | 50% | 100% | 1.13 | 5.55 | 27.8 | 19.5 | 60.5 | 16.67 / 16.67 | 0/0 |
| FG60+SR | 9 | 59.9 | 30 | 59.6 | 30 | 50% | 100% | 1.16 | 5.51 | 29 | 18 | 90.5 | 8.33 / 16.67 | 0/0 |

*(Captured via `scripts/bench-direct.ps1 -Serial 192.168.68.80:5555 -SecondsPerMode 10 -Modes 0,1,2,3,4 -Look 0 -Label v3-verified`, `build/bench/20261121-114004-v3-verified.md`.)*

## Details that are easy to get wrong

* **mDNS candidates.** Chromium signals host candidates as `<uuid>.local`, which the console cannot
  resolve. The offer's candidates are stripped and rewritten to this device's real LAN address
  (same port, same priority) before the `/ice` exchange. Without that rewrite ICE stays in
  `checking` forever.
* **Teredo candidates.** Out-of-home candidates encoded as `2001:0::/32` are expanded into synthetic
  IPv4 host candidates on port 9002, exactly as the PC path does.
* **Codec order.** Chromium's default H.264 preference is Baseline; the console's encoder is Main
  4.2. The page asks for `4d002a`, `42e02a`, `42002a` first (and falls back silently if the WebView
  rejects the preference).
* **Device info.** The REST calls claim `os.name = windows`. Declaring `android` caps the stream at
  720p. This is a protocol detail, not a claim about the hardware.
* **Handshake.** The console expects `Handshake` on the *message* channel, then
  `authorizationRequest` + `gamepadChanged` add/remove/add on *control*, a 15-byte client-metadata
  report on *input*, then the `/streaming/...` configuration messages. The page mirrors
  `src/xbox/WebRtcSession.cpp` exactly.
* **Binary reports.** Gamepad reports are 37 bytes, little endian (type 2, sequence, time, pad count,
  index, buttons, four axes, two triggers, virtual-physicality flags). `tests/direct-js-test.js`
  asserts every offset.

## What has been verified (2026-11-21)

* PC and APK both build; the APK contains `assets/direct/{index.html,direct.js}` with `/` separators.
* `node tests/direct-js-test.js` passes all 45 checks (4 channels, m-line/mid mapping, PC-shaped
  candidate-free offer, handshake order, handshake-ack control sequence, 15-byte metadata, 37-byte
  gamepad layout, `padPresent: false` handshake/release behavior, `onResume()` audio bounce and
  keyframe request, `setEnhance` and `setLook` cycling, `ReportVibration` -> `GTA60Bridge.onRumble`,
  `classifyDupeCadence` phase-lock + 60 fps pass-through, `WarningForBeingIdle` keep-alive handling,
  and clean channel close).
* **Live on Quest 3 (`192.168.68.80`) -> Xbox Series X (`Great room xbox`)**:
  * Console accepts the WebView's conformed 51-line SDP offer in ~350 ms; Horizon OS allocates a 1:1
    `2560x1440` surface (`requestedSurfaceSize = 2560x1440`).
  * ICE connects over the LAN (`rtt = 1.0 ms`, `9-10` remote candidates applied).
  * All 4 data channels open and complete `Handshake` -> `HandshakeAck`.
  * Hardware H.264 decode (`c2.qti.avc.decoder`) runs at **2560x1440 @ 60.0 fps**, **18.0-22.4 Mbps**,
    **`dec = 5.4-6.2 ms`**, **`lost = 0`**, with WebGL2 v3 PBO active (`100%`) and **`gpu = 1.13-1.16 ms`** in `FG60`/`FG60+SR`.
  * 48 kHz stereo Opus audio plays out through low-latency `AAudio` (`AUDIO_OUTPUT_FLAG_FAST`, 48,000 samples/s).

Useful debug hook while testing:
`adb shell am start -n com.gtasixty.quest/.MainActivity --ez direct true --ez directStart true --ei enhance 4 --ei look 0`
opens the app straight into Direct mode with chosen `Enhance` (`0..4`) and `Look` (`0..2`) presets.

## Known gaps

* No microphone/chat (`sendrecv` audio is offered because the console's answer expects it, but no
  local track is sent).
* Physical headset rumble fidelity depends on paired Android gamepad vibrator support over Bluetooth/USB.
* No HEVC/HDR selection: the console picks from what Chromium offers, normally H.264.
* No resolution preference; the console's `dimensionschanged` handling decides.
* If the PC streamer is running, starting a direct session takes the console's single session away
  from it. That is a console limitation, not a bug here.

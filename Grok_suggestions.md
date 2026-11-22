# Grok suggestions

Status 2026-11-22: implemented in source. The PC offer cap is 80 Mbps (`x-google-max-bitrate=80000` on all three H.264 lines; level stays 4.2). Direct mode still asks `x-google-max-bitrate=500000` and now logs `bwe=` (`availableIncomingBitrate`) beside Mbps. A still or menu scene dropping bitrate is the console's rate control, so compare Mbps to bwe during motion. Entering Direct mode broadcasts `GTA60_STOP` (streamer UDP 7661 and agent UDP 7662) and `DirectController.start()` waits for the PC session to drop before `play()`. Frame generation uses an 8×8 diff grid, allows consecutive dupes, predicts only a verified alternating cadence, holds the warp for two vsyncs, sharpens at 0.10 (half on generated frames), skips SuperRes when the source is already 1440p, and blends the flow field against the previous one. The headless REMB sweep has not been re-run.

Reviewed 2026-11-22. The Quest 3 answered ADB at `192.168.68.80:5555` (`product:eureka`). Bitrate numbers below are the 2026-11-21 measurements in `BENCHMARK_2026-11-21.md` and `DIRECT_MODE.md`, read against the source as it was at review time.

## 1. The Xbox bitrate cap

Direct mode sits around 18–22.4 Mbps at 2560×1440. That is not the headset asking for too little in the SDP. The offer already says `b=AS:500000` and `x-google-max-bitrate=500000` (`direct.js` `conformOffer` / `conformAnswer`). The console still will not go higher. The signal it actually obeys is RTCP REMB, and direct mode never sends a forced one.

What is already measured, console to PC, same Series X:

| What the client told the console | Source bitrate |
|---|---|
| No REMB | 14.1 Mbps |
| REMB 18 Mbps | 16.7 Mbps |
| REMB 50 Mbps | 24.2 Mbps |
| REMB 80 Mbps | 23.2 Mbps |
| Direct mode, SDP hints + presentation feedback, no forced REMB | 18–22.4 Mbps |

REMB is sent only on the PC path: `WebRtcSession::pumpBitrateRequest` calls `video->requestBitrate()` about twice a second. libdatachannel otherwise sends receiver reports only, and the console then stays near 15 Mbps. Direct mode has no equivalent. Chromium may emit its own goog-remb from the Wi-Fi bandwidth estimate, which settles near the rate it is already receiving. That is a closed loop: the console sends ~22 because the browser's estimate is ~22 because that is what the console is sending.

### The 24 Mbps "ceiling" was measured with an offer that says 25

The PC offer hard-codes the cap the sweep was trying to find:

```text
profile-level-id=4d002a;x-google-max-bitrate=25000
```

`src/xbox/WebRtcSession.cpp`, all three H.264 payload types. In Chromium's fmtp, `x-google-max-bitrate` is kilobits per second, so this line is 25 Mbps. REMB 80 and REMB 150 were both sent on top of that offer. If the console takes the minimum of REMB and the offer cap, those runs could not have exceeded ~25 Mbps, and 24.2 is exactly what that looks like. The write-up treated 24 as an encoder wall. The experiment never asked for more than 25.

Direct mode already asks for 500 Mbps in the SDP and still gets ~22, so raising the SDP number alone does nothing. The missing piece on the headset is a REMB that leads the receive rate, the same way `--xbox-bitrate 50` does on the PC.

### What to measure before changing the pipeline

One headless hour answers this. Do not start a Quest session for it.

1. On the PC offer, set `x-google-max-bitrate` to `80000` (80 Mbps) on all three H.264 lines, or delete the `x-google-*-bitrate` parameters. Keep H.264 level where it is for the first run so only one thing changes.
2. Re-run the existing sweep: `--xbox-bitrate 0`, `25`, `50`, `80`. `scripts/bench.ps1 -Headless` already records source bitrate.
3. Read the result as follows.
   - Stays at ~24 Mbps at REMB 50 and 80: the encoder on this console really tops out there. Direct mode's job is then to close 22 → 24, and past that the picture only improves by spending those bits better (below).
   - Climbs with the new offer cap: the November ceiling was the fmtp line. Put the same cap in the direct-mode offer (already higher) and spend the work on making the WebView emit REMB at that rate.

On the next direct session, log one extra `getStats` field that `updateStats` currently throws away: the selected candidate pair's `availableIncomingBitrate`.

- If that value sits near 22 Mbps while the video is at 22, the browser is the thing telling the console to stop. SDP edits will not move it.
- If it sits far above the video bitrate, the console is ignoring REMB and is on its own cap.

There is no JavaScript API to set receive-side REMB. `RTCRtpSender.setParameters().encodings[].maxBitrate` limits what the page sends, and this page only sends audio and data. A forced REMB has to come from the WebRTC stack. Two ways, in order:

1. Confirm with the stat above that Chromium's estimate is the clamp. If it is, the smallest fix that matches the PC is a REMB paced at a configured bits-per-second (50 Mbps is the value that already saturated this console), sent about twice a second, independent of the measured receive rate. That is what `requestBitrate` does. The system WebView will not do it from the page. It means a peer connection this process controls, which is the libdatachannel path that was dropped once the WebView offer was accepted. It is worth doing only for this packet. The rest of the session (decode, audio, input, the GPU pipe) can stay where it is if the stack can be taught to emit REMB; if not, the whole receive path moves.
2. Do not keep inflating `b=AS` and `x-google-max-bitrate`. They are already 10–20× past the rate the console sends. The presentation-feedback trick (decode time reported as ~2 ms) is already what moved direct mode off the 15 Mbps default. Sending it more often will not open a new band.

### If the encoder really stops at ~24

Then 22 vs 24 is about 10 percent, and it will not turn 1440p H.264 into a clean picture. 22 Mbps at 2560×1440×60 is about 0.10 bits per pixel per scanned frame. GTA 6's unique rate is ~30, so the real frames get ~0.20. That is the soft, blocky look. Frame generation and SuperRes do not add texture the encoder threw away. RCAS at 0.22 (`rcasSharpness` in `direct.js`) sharpens the blocks.

Worth doing at a fixed ~24 Mbps, in this order:

- Drop direct-mode RCAS toward the range the PC notes already suggest for this source, about 0.06–0.12. Use the lower end on generated frames. Sharpening is making the cap look worse.
- Treat SuperRes as a 1080p→1440p tool, not a 1440p→1800p tool. The panel is already 1:1 with the decode (`requestedSurfaceSize` 2560×1440). A 3200×1800 canvas is a second resample of a source that has no spare detail, and the 2026-11-21 FG60+SR row is the one that broke cadence (SurfaceFlinger p50 8.33 ms / p95 16.67 ms, ~90 fps). FG60 without SuperRes held 16.67/16.67.
- One experiment, separate from the REMB sweep: ask the console for 1080p while holding the high rate, then SuperRes up to the panel. Only useful if bitrate stays ~24 while resolution drops. If the console ties the rate to the resolution and 1080p falls back to ~15, skip it. There is no resolution field in `play()`; the console picked 2560×1440 because the client offered level 4.2 and a huge `b=AS`. Level 4.2 is not a legal bucket for 1440p60 (that is level 5.1, `profile-level-id` `4d0033`, 864k macroblocks/s). The console is already encoding above the advertised level, so raising the offer to 5.1 or 5.2 is a reasonable companion to the REMB test, not a substitute for it.
- HEVC from the console would change the picture more than any of the above, at the same Mbps. The live decoder is `c2.qti.avc.decoder`, so today the console is sending H.264. Adding an H.265 payload type behind the H.264 ones is a single-session experiment. If the answer stays H.264, stop. Do not put HEVC first; a rejected offer costs the console's only session.

The September 2026 Alpha Skip-Ahead "1440p and higher bitrate" preview is the feature this client is already inside: 1440p and a rate above the old ~15 Mbps default. It is not, on the numbers from this console, a 40 Mbps mode that the official app has and this client does not. The check, if you want it closed: same game, same console, official remote play or the Better xCloud build already sideloaded on this headset, and read its bitrate. If that client is also at ~22–24, there is no hidden request left.

## 2. Direct mode does not drop the PC's Xbox session

Switching to Direct closes the headset's TCP socket and nothing else. The console allows one streaming session. The PC streamer keeps that session, and it keepalives it.

`MainActivity.enterDirectMode()` calls `disconnect()` only when `streaming` is already true. `disconnect()` closes the Quest TCP socket, releases MediaCodec, and writes a neutral pad. On the PC, `QuestStreamServer` sees the drop, `closeClientLocked()` sets `activeGen` to 0, and the frame loop skips GPU work (`FG-IDLE` / `SHARP-IDLE`). The Xbox WebRTC session stays up. `api.keepalive(sessionId)` still runs every 30 seconds on `controlThread`, and `api.stop()` runs only when the streamer process exits.

There is no stop command. The agent on UDP 7662 accepts `GTA60_START` only. While TCP 7660 is open it reports the streamer as running and will not touch it. A headset that cold-starts already in Direct (the persisted `directMode` flag) never even calls `disconnect()`, because `streaming` is false.

`XboxDirect.play()` then retries HTTP 409 for 20 × 3 seconds and tells you to stop the PC streamer by hand. That retry is what collided with a live streamer before and left the console in `ServerStartStreamingV2CommandSent` until a reboot.

What "kills the PC connection" needs to mean:

1. Headset, on entering Direct and on a cold start that restores Direct, broadcasts a stop (new `GTA60_STOP` on UDP 7662, same targets as `GTA60_START`).
2. The streamer, not the agent, handles it. On stop, or when the Quest client has been gone for a couple of seconds, it should do the normal shutdown: neutral pad, `rtc.close()`, `api.stop(sessionId)`, then exit. `Stop-Process` from the agent skips that and is how the stale pad and the stuck `ServerStartStreamingV2CommandSent` state get left behind. The agent should only forward the request, or signal the running process, and it should answer so the headset can wait.
3. Direct `play()` waits until UDP 7661 stops answering, or until a short ack, and only then provisions. The 60 second 409 loop is a backstop, not the mechanism. Do not start Direct while discovery still returns `GTA60_OFFER`.

Leaving Direct already stops the direct session. `exitDirectMode()` → `DirectController.hide()` → `stop()`, which releases pad 0, waits 350 ms, then closes the peer connection and `DELETE`s the console session. That direction is fine. The gap is only PC → Direct, and a PC streamer that outlives the headset.

## 3. Direct-mode frame generation

The pipe in `direct.js` `processVideoFrameGpu`, for FG60 and FG60+SR:

1. Upload the `<video>` frame into a 3-deep RGBA ring (`texSubImage2D` of the full 1440p frame).
2. Luma + abs diff at 320×180 against the last unique frame.
3. A 4×4 regional reduce, read back through a 2-slot PBO.
4. Classify duplicate vs unique. On a unique frame, downsample luma to 80×45.
5. Coarse patch-SAD flow at 80×45, fine search at 320×180, 3×3 bilateral smooth.
6. Full-res midpoint warp into `fboMid`, with three hardcoded HUD rects.
7. RCAS, and in FG60+SR an extra edge-directed reconstruction, into the canvas.
8. `setTimeout(17)` then draws the real frame.

RCAS and SuperRes skip steps 3–8 and draw the uploaded frame straight through RCAS. Cost on the Adreno 740 is about 0.4 ms for those and 1.1–1.2 ms for FG, which is fine. The problems are cadence, which pair of frames gets warped, and sharpening a 22 Mbps source.

### Phase lock will mis-pair frames that are not a perfect 2:2

`classifyDupeCadence` returns "not a duplicate" as soon as `curConsecDupes >= 1`, so it can never see two duplicates in a row. Once the PBO path is warm, the current frame is not classified from its own pixels. It is predicted:

```text
pboExpectedDupe = !isDupe && (fgActiveGate || consecUniqueRun < 2)
```

That is correct only while the console holds exactly one 60 Hz duplicate between unique frames. GTA 6 at a locked 30 usually does that, and the bench's `dup=50%` rows show it. It is wrong for a 40 fps stretch, a triple hold, or a loading card, and the 4×4 probe is sparse enough to invent those (next section). On a mismatch the code sets `pboSyncFrames = 2` and falls back to a synchronous `readPixels` for two frames. The warp for the bad pair has already been shown.

The PC gate does not do this. It measures every frame (`ContentDedup`, luma MAD, threshold 1.0, with a 34–46 fps dead zone and a streak before it flips FG on or off). The headset gate (`dup >= 20%` and `uniq <= 46.5` over the last second, release at `dup < 10%` and `uniq >= 50`) is reasonable. The per-frame decision underneath it is not.

Use the PBO as a late label for the frame that was uploaded, not as a prediction of the next one. Show the real frame immediately. When the label for the previous frame arrives and says it was a duplicate, the slot you are in is the duplicate slot: draw the warp that was computed from the last two unique frames, if those two were actually unique. When the label disagrees with what you displayed, drop FG for that pair instead of warping them. That also removes the extra latency below.

### The 4×4 diff does not look at the frame

`FRAG_DIFF` takes 9 taps, stepped by 16 and 10 texels, around the center of each 4×4 cell. A cell covers an 80×45 region of the 320×180 luma. The taps cover a small patch in the middle of that region. Motion in the rest of the quadrant never moves `mad` or `maxBlock`. Combined with `maxBlock <= 9` (out of 255) and `mad < 1.9`, foliage and night driving at 22 Mbps will read as unique (quantization noise in the center tap) and a car entering from the edge will read as a duplicate.

Replace it with a real reduce: 320×180 → 40×22 → 4×4, box or max, every texel. The PBO payload stays 64 bytes. The PC's full-frame MAD is the behavior to copy; the bimodal split on this source was repeats at 0.0–0.4 and new frames at 1.5 and up, which is why 1.0 works there. Re-tune the headset thresholds against that reduce, on a known 30 fps scene and a known 60 fps scene, before trusting `uniq`.

### The generated frame is inserted by delaying the real one, on a timer

A midpoint between frame A and frame B cannot be drawn until B has arrived, so showing it between them means holding B. The code holds B for 17 ms via `setTimeout`. That is ~17 ms added to every real frame, on top of a jitter buffer that the same bench measured at 9–29 ms. `setTimeout(17)` is also not a vsync. At 120 Hz the two presents want to be two vsyncs apart (16.67 ms). The FG60+SR capture landed on adjacent vsyncs often enough to read as ~90 fps with an 8.33 ms median. The 90 Hz display option makes a fixed 17 ms split the wrong ratio entirely (3 vsyncs per 30 fps frame).

Schedule the second present from the `requestVideoFrameCallback` timestamp, two vsyncs later at 120 Hz (three at 90, split to match the duplicate hold). Or, if the late PBO label is adopted, draw B immediately and draw the warp only when the following callback is the duplicate. Real frames then cost nothing over passthrough, and the generated frame occupies the slot the console was going to waste on a repeat. That is the latency-sensitive version and it matches how the duplicate stream is actually shaped.

Extrapolation (warp forward from the previous pair into the duplicate slot, before B exists) is the zero-delay option if you do not want to wait for B. It smears disocclusions. Offer it as a mode, do not make it the default for a driving camera.

### Flow

Coarse search is ±5 × ±3 texels at 80×45, which is about ±160 × ±96 pixels at 1440p, then a small refine. A fast GTA yaw between two unique frames (33 ms) exceeds that. The warp's confidence test then fades that region back to the current frame, so the "generated" frame is a hold in the places that were moving most. Raising the coarse window costs taps; a better trade is to seed the search with the previous frame's vector, and to blend the new vector with the previous one unless they disagree. Flow is recomputed from zero every unique frame, which is why the interpolated frame shimmers even when the camera motion is smooth.

The "bidirectional" check is one reverse SAD plus the coarse vector sampled at the source pixel. It is not a reverse search. The PC shader (`FrameGenWarp.hlsl`) keeps a real reverse field and a disagreement term and uses those for the explained-warp test. The headset shader's version of that test (`Es`, `Em`, `fit`) is the right idea and is already cheaper than it looks. Feeding it a real reverse field, even at 80×45, would cut the halo around cars and the HUD more than another refine pass at 320×180.

`MAX_UV = 0.08` quantizes into an 8-bit UNORM. Subpixel refine at 0.25 texel is partly thrown away by that packing. `RGBA16F` or a two-channel 16-bit target for the flow, if the Adreno WebGL2 context allows it, keeps the refine you already paid for. Check it; do not assume.

### HUD masks

`FRAG_WARP` protects three rectangles: bottom-left 24%×28%, top-right 23%×22%, and a small center reticle. UV is right for the unflipped video upload (video bottom is high `v`), so the minimap corner is the corner you think it is. The rects are a GTA prior, not a measurement. Phone, subtitles, and mission text sit outside them and will warp. A static-region mask (low flow and low luma diff for ~0.5 s, high spatial contrast) covers those without another title-specific rect. Keep the three rects as a prior added on top of that mask.

### Sharpen and SuperRes

RCAS runs on the warp and on the real frame with the same strength (0.22, or 0.18 when SuperRes is on). On this bitrate it amplifies ringing. Run RCAS only on the real frame, or at roughly half strength on the warp. The warp is already a blend; sharpening it invents edges that pop when the real frame arrives 17 ms later.

SuperRes inside `FRAG_RCAS` mixes an edge-directed 5-tap reconstruction by up to 0.65, clamped to the local min/max. At 1:1 with a 22 Mbps source the edge it finds is often a DCT block. Leave it off unless the source is below panel resolution. The benchmark already says the plain FG60 path presents evenly and FG60+SR does not.

### Upload

`texSubImage2D` of every 1440p frame is the large copy. At a measured ~1.2 ms for the whole FG path it is not the thing to rewrite. An external video texture would skip it, and the system WebView does not hand one out. Leave it.

## 4. Order to do this

1. PC offer: raise or remove `x-google-max-bitrate=25000`, repeat the REMB sweep headless. This is the only test that can still find more bits.
2. On the following direct session, log `availableIncomingBitrate` next to the Mbps line. That says whether the WebView estimate or the console is holding 22.
3. Stop the PC Xbox session when Direct starts (section 2). Until that exists, Direct against a running streamer is a 409 and can stick the console.
4. Only if the sweep shows a real encoder wall: lower RCAS, stop SuperRes at 1440p, fix the duplicate detector and the present timing. Those change how 22 Mbps looks. They do not raise it.

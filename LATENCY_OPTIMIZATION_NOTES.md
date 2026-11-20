# GTA-Sixty Latency Analysis & Optimization Notes

> Last updated: 2026-11-20
> Target setup: Xbox Series X (local LAN, wired Ethernet) -> PC (Ampere-class NVIDIA GPU, wired Ethernet) -> Meta Quest 3 (wired / USB-C Ethernet).
>
> **Start at section 6 (HANDOFF)** if you are picking this up fresh - it has the current numbers,
> what is already maxed out, what is worth doing next, and the measurement traps that cost hours.
> **Section 9 (HEVC)** is the newest work, 9.7 is a live stress test, and section 10 lists what the
> rename broke. Anything marked "corrected" or "measured" was verified on this hardware; earlier
> guesses that turned out wrong are kept visible rather than silently deleted, so do the same.

---

## 1. Baseline Forensic Findings (Before Phase 1 Fixes)

Live telemetry from `build/perf-telemetry.log` with Quest 3 connected (`2560x1440 @ 60fps 120 Mbps` requested):

```text
in=59.9 (13.8M 2560x1440) uniq=59.9 out=90.3..111.4 fps -> 2560x1440@60
  | dec=2.55-3.11ms gpu=5.85ms(up=0.31,flow=0.21,fg+up=0.25) nvenc=5.80ms net=0.05ms
  | out=183-224 Mbps qDrop=0 rtt=17-19ms
```

At `3840x2160 @ 60fps`:
```text
in=59.9 (14.4M 2560x1440) uniq=59.9 out=68.5 fps -> 3840x2160@60
  | dec=2.65ms gpu=6.60ms nvenc=11.14ms net=0.03ms | out=57.5 Mbps
```

### Root Causes Identified

1. **Unconditional Frame Generation on 60 FPS input (~60–120 ms lag + 220 Mbps flood)**
   - `EnhancementPipeline::processUniqueFrame()` unconditionally generated 2 frames (60fps target) or 3 frames (90fps target) for every unique input frame, even when Xbox was already sending ~60 unique FPS (`uniq=59.9`).
   - Result: PC tried to push **110–120 FPS at 220 Mbps** into a 60 FPS / 120 Mbps Quest stream.
   - Because `gpu (5.85ms) + 2x nvenc (11.6ms) = 17.5ms > 16.67ms`, the PC main loop was 100% saturated, and the Quest MediaCodec/NIC backpressured TCP around ~90 fps / ~185 Mbps, keeping the entire 1 MB TCP buffer (`SO_SNDBUF=512KB + SO_RCVBUF=512KB`) + MediaCodec input queue permanently full.
   - Furthermore, sending a generated midpoint frame before the real frame added an unnecessary 16.67 ms cadence delay on 60 FPS content.

2. **NVENC misconfigured as 2-pass `P4` with 250 ms VBV & no zero-reorder VUI (~4–8 ms encode + ~33–50 ms Quest MediaCodec DPB buffering + 2s IDR spikes)**
   - `src/sink/NvencD3D12Encoder.cpp` logged `P1 ULL` but actually passed `NV_ENC_PRESET_P4_GUID` (which defaults to `NV_ENC_TWO_PASS_QUARTER_RESOLUTION` two-pass encoding!), `enableAQ = 1`, `vbvBufferSize = 250 ms`, and `gopLength = idrPeriod = 120` (forcing giant IDR bursts every 2 seconds over reliable TCP).
   - **Critical MediaCodec trap**: `rcParams.zeroReorderDelay = 1`, `h264Config.maxNumRefFrames = 1`, and `h264VUIParameters.bitstreamRestrictionFlag = 1` were NOT set. Without explicit `max_num_reorder_frames = 0` in the High Profile SPS VUI, Qualcomm Snapdragon MediaCodec often buffers 2–4 frames in the DPB (33–66 ms of hidden lag).

3. **Hidden NVOFA latency + D3D12 single-allocator reset hazard (~5.1 ms hidden inside `gpu=5.9ms`)**
   - `EnhancementPipeline.cpp` hardcoded `opticalFlowMs = 0.0` when `usingNvof_` was true, hiding ~4.5 ms of full-res 2560x1440 bidirectional NVOFA + cost map generation + 14.7 MB `CopyResource` inside `totalGpuMs`.
   - `D3D12Context` had only **one** `ID3D12CommandAllocator`, yet `EnhancementPipeline` reset it immediately after submitting Stage A while Stage A was still executing on the GPU (D3D12 UB / driver sync stall).
   - When `inW == outW && inH == outH` (2560x1440 -> 2560x1440), it still ran `Nv12ToRgb -> EasuUpscale (copy) -> RcasSharpen -> RgbToNv12 -> CopyTextureRegion`.

4. **CPU H.264 decode allocations & 22 MB/frame memory churn (~1.3+ ms wasted)**
   - `MfH264Decoder` allocated fresh COM `IMFSample`/`IMFMediaBuffer` every frame, and `main.cpp` allocated a fresh 5.5 MB `DecodedNv12Frame` every frame (~330 MB/s OS page faults at 60 fps), followed by a 5.5 MB synchronous `PictureReference::copyReference` on the critical path before waking the GPU thread.
   - **Important empirical finding**: Setting `MF_LOW_LATENCY = TRUE` on `CLSID_CMSH264DecoderMFT`'s `IMFAttributes` caused software CPU decode time to **regress from ~2.5 ms -> 7.06 ms** because it forces single-threaded decoding! Keep `CODECAPI_AVLowLatencyMode = VARIANT_TRUE` ONLY (which keeps multi-threaded CPU decoding with 0-frame output delay).

5. **Windows 15.625 ms timer resolution & large queues (~8–15 ms pacing/input jitter)**
   - `timeBeginPeriod(1)` was never called, so `wait_until(16.67ms)` and `sleep_for(8ms)` quantized to 15.625 ms Windows timer ticks.
   - `videoQueue` allowed up to 32 frames (533 ms!) before flushing; `QuestStreamServer::sendQueue` had no video cap and `SO_SNDBUF` was 512 KB.
   - `net=0.05ms` in the old telemetry only measured `memcpy` into `sendQueue`, hiding real TCP `send()` stalls.

6. **Xbox ICE active route verified**
   - Added ICE route logging in `WebRtcSession.cpp`: confirmed Xbox connects over **direct local IPv6 ULA LAN** (`fd56:e205:... -> fd56:e205:...:9002`, port 9002), NOT cloud relay.
   - Still observed `rtt = 14–19 ms` on `pc_->rtt()` (likely Xbox Wi-Fi in the room and/or `libdatachannel` SCTP/STUN RTT reporting). Also raised unpacked private-LAN Teredo IPv4 candidate priority from `1` -> `2130706431` in `StreamApi.cpp`.

---

## 2. Phase 1 Optimizations Implemented & Verified

All 6 unit/GPU integration tests (`build/GTA-Sixty_tests.exe`) pass, `build/GTA-Sixty.exe` and `build/GTA-Sixty-Quest3.apk` are rebuilt, and the upgraded server is live.

### PC Server (`C++`)

| File | Changes |
|---|---|
| `CMakeLists.txt` | Linked `winmm` for `timeBeginPeriod(1)`. |
| `include/gta60/pipeline/D3D12Context.h`, `src/pipeline/D3D12Context.cpp` | Replaced single `ID3D12CommandAllocator` with a **4-allocator ring** + per-slot fence tracking to eliminate in-flight allocator reset stalls. |
| `include/gta60/nvof/NvOfSession.h`, `src/nvof/NvOfSession.cpp` | Added `swapInputs` ping-pong support (eliminates 14.7 MB GPU-to-GPU copy per frame) and made `costOut`/`reverseCost` optional (`enableOutputCost = NV_OF_FALSE` since shaders don't use cost maps). |
| `include/gta60/pipeline/EnhancementPipeline.h`, `src/pipeline/EnhancementPipeline.cpp` | Persistently mapped `nv12UploadBuf_` + bulk `memcpy`; ping-pong `colorSlots_[0/1]`; skipped `EasuUpscale` when `inW == outW && inH == outH`; added `frameGenMultiplier`, `waitForCompletion` (GPU-chaining to NVENC), `resetTemporalHistory()`, and real NVOFA timestamp reporting via `readLastGpuTimings()`. |
| `include/gta60/sink/NvencD3D12Encoder.h`, `src/sink/NvencD3D12Encoder.cpp` | Kept **`NV_ENC_PRESET_P4_GUID`** + **`enableAQ = 1 (strength 8)`** + **`250 ms VBV`** + **`gopLength = idrPeriod = 120`** for crisp image quality (empirically, `P1` + `1-frame VBV` + `no AQ` + `lowDelayKeyFrameScale = 1` + `INFINITE_GOPLENGTH` only saved ~1.3 ms while starving IDR/P-frames to ~75 Mbps and making the stream visibly pixelated!), while adding **`multiPass = NV_ENC_MULTI_PASS_DISABLED`** (single-pass ULL), **`zeroReorderDelay = 1`**, and **`h264VUIParameters.bitstreamRestrictionFlag = 1`** (+ BT.709 VUI; SPS/PPS 41B) for instant 0-frame MediaCodec DPB decoding. Also added `encodeNv12Frame()` helper. |
| `include/gta60/sink/QuestStreamServer.h`, `src/sink/QuestStreamServer.cpp` | Added `setSendBufferBytes(128 * 1024)` (from 512 KB), `IP_TOS = 0xB8` (DSCP EF QoS), `THREAD_PRIORITY_ABOVE_NORMAL` on `sendThread`, bounded video backlog (`setMaxVideoBacklog(3)`) with **Drop-to-IDR recovery** (purges stale queued video frames and requests fresh IDR cleanly), 4-packet audio cap, and real TCP `sendAll()` avg/max ms telemetry (`consumeStats()`). |
| `src/media/MfH264Decoder.cpp` | Reused `inSample`/`inBuffer` across frames and used contiguous bulk `memcpy` when `srcPitch == width`. Kept `CODECAPI_AVLowLatencyMode = VARIANT_TRUE` without `MF_LOW_LATENCY` (see regression note above). |
| `src/xbox/StreamApi.cpp`, `src/xbox/WebRtcSession.cpp` | Upgraded private-LAN Teredo-unpacked IPv4 ICE priority (`1` -> `2130706431`) and logged active ICE `local <-> remote` route on connect. |
| `src/main.cpp` | Added `timeBeginPeriod(1)`, `HIGH_PRIORITY_CLASS`, `THREAD_PRIORITY_ABOVE_NORMAL` on decode/render threads; reduced `videoQueue` cap `32 -> 6` and XInput poll `8ms -> 4ms`; added 4-slot pre-allocated `DecodedNv12Frame` pool + deferred `picRef.commit()` (overlaps reference copy with GPU/NVENC); added **Adaptive Cadence FG Gate** (bypasses FG when input is ~60fps, enters 2x/3x FG only on ~30fps content, plus `--no-fg` CLI switch); wired fast GPU-chained **RCAS-only path** (`[RCAS]`, skips NVOFA + EASU, ~0.45 ms GPU) when 1:1 & no FG so the 14 Mbps Xbox stream stays sharp; added **pre-encoded pacing** in FG mode; added true end-to-end PC latency (`pcLat`) & TCP send (`tcp=avg/max`) telemetry. |

### Quest 3 APK (`Java` — rebuilt at `build/GTA-Sixty-Quest3.apk`, needs USB install when convenient)

| File | Changes |
|---|---|
| `quest-app/src/main/java/com/gtasixty/quest/MainActivity.java` | Wrapped TCP input in `BufferedInputStream(64KB)`; reduced `SO_RCVBUF` `512KB -> 128KB`; set `setTrafficClass(0xB8)`; elevated `GTA60-Net` and `GTA60-DecOut` to `THREAD_PRIORITY_URGENT_DISPLAY`; added `vendor.qti-ext-dec-picture-order.enable = 1` + `KEY_OPERATING_RATE = 240` (including fallback); changed `releaseOutputBuffer(outIdx, true)` -> `releaseOutputBuffer(outIdx, System.nanoTime())`; tightened `AudioTrack` buffer `40ms -> 20ms`; deduplicated unchanged joystick `ACTION_MOVE` events. |

### Observed Live Measurements (Post-Phase 1)
- `dec`: **2.55–3.11 ms -> 1.15–1.30 ms** (~2x faster CPU decode + zero page faults).
- `out`: **90–111 fps @ 183–224 Mbps (backpressured flood) -> locked 59.9 fps** matching `targetFps`.
- `tcp` send latency: **`0.04–0.06 ms` avg (`qMax = 1`, `tcpDrop = 0`)** — zero TCP queue buildup!
- `pcLat` (Xbox RTP arrival on PC -> encoded H.264 AU sent to TCP socket): **`5.5–6.0 ms` total** (down from ~30–130+ ms)!
- **Important quality lesson**: Do NOT drop NVENC to `P1` + `enableAQ = 0` + `1-frame VBV` + `lowDelayKeyFrameScale = 1` + `INFINITE_GOPLENGTH` — that only saved ~1.3 ms while starving IDR/P-frames (`72–96 Mbps` out of `120 Mbps`) and causing visible pixelation. Keeping `P4` + single-pass + `AQ=8` + `250 ms VBV` + `gop=120` + `zeroReorderDelay=1` + `bitstreamRestrictionFlag=1` + GPU-chained `[RCAS]` sharpening (`~0.45 ms`, skipping NVOFA & EASU) keeps both razor-sharp quality and ~6 ms PC latency.

---

## 3. Phase 2 Roadmap for Next Agent (How to Cut Lag Even Further)

Inspect `build/perf-telemetry.log` first once the Quest 3 connects to the upgraded server! Then work through these highest-impact opportunities in order:

### Priority 1: Verify Live Telemetry & Diagnose Remaining End-to-End Bottlenecks
1. Check the new telemetry line format in `build/perf-telemetry.log`:
   ```text
   in=... uniq=... out=... fps [NV12|FG|UPSCALE] -> ... | pcLat=... dec=... gpu=... nvenc=... tcp=avg/max | out=... Mbps qDrop=... tcpDrop=... qMax=... rtt=...
   ```
2. **Check whether the game on Xbox is truly 60 FPS or a 30 FPS game failing `PictureCompare`!**
   - Notice earlier in `perf-telemetry.log` (lines 0–345) `uniq=30–32`, while later (lines 353+) `uniq=52–60` with `14 Mbps 2560x1440` H.264.
   - If the user is actually playing a **30 FPS game** (e.g., GTA Fidelity mode) and H.264 compression noise at 14 Mbps 1440p exceeds `PictureCompare.h`'s ultra-strict HDMI-capture thresholds (`kBand8 = 3`, `tolerance = 5.5M / 196608 = 28 bytes = 0.0005%`!), then `uniq` will wrongly report ~55–60 and Adaptive FG will stay bypassed!
   - **Action**: Log `pic.over` / `pic.max8` histogram on consecutive frames when testing a known 30 FPS game. If compressed H.264 duplicates exceed `kBand8=3, tol=28`, tune a dedicated WebRTC/H.264 dedup band (e.g. `band=6..8` and/or `tolerance = total / 4096` (~0.025%) or mean-abs-diff on Y plane only). Keep `Test2` passing.

### Priority 2: Install Updated Quest APK & Measure Quest-Side Latency
1. When Quest 3 is connected via USB-C (or wireless ADB on `<quest-ip>`), run `scripts/install-quest-apk.ps1 -Launch` to deploy the already-rebuilt `build/GTA-Sixty-Quest3.apk`.
2. Add **RTT / glass-to-glass timestamp echo** between PC and Quest:
   - Have Quest echo back the `timestampMs` of each rendered frame (or periodic 1 Hz ping/pong + `dequeue->render` decode time in `GTA60-Perf` logcat via `adb logcat -s GTA60 GTA60-Perf`) so you can measure **exact Quest MediaCodec decode + SurfaceFlinger queue latency** separately from PC and Xbox latency!
3. Switch Quest `MainActivity.java` MediaCodec from synchronous polling (`dequeueInputBuffer` / `dequeueOutputBuffer` on two threads) to **Asynchronous `MediaCodec.Callback` mode** on a dedicated high-priority `HandlerThread` (`onInputBufferAvailable` / `onOutputBufferAvailable`) to eliminate polling wakeups and thread handoffs.

### Priority 3: Move Video/Audio Transport from TCP to UDP (with TCP Control Backchannel)
- Even on <1 ms LAN Ethernet, TCP suffers from **head-of-line blocking** and kernel pacing/ACK delays whenever a single packet drops or jitters (especially over Wi-Fi!).
- Design: Keep TCP port 7660 for reliable `VIDEO_CONFIG`, `CLIENT_CONFIG`, `CLIENT_IDR`, and `CLIENT_GAMEPAD` (or also send gamepad on UDP!). Stream `VIDEO_AU` (fragmented into <=1400-byte MTU chunks with frameId + fragIdx + fragCount + flags) and **raw Opus packets** (64 kbps instead of 1.54 Mbps uncompressed PCM!) over UDP!
- If any fragment of a video frame is lost, skip incomplete frame and immediately send `CLIENT_IDR` (or enable NVENC **Intra-Refresh** `enableIntraRefresh = 1` + `intraRefreshPeriod` for zero-spike recovery!).
- Also consider **HEVC (H.265)** (`NV_ENC_CODEC_HEVC_GUID` / `"video/hevc"` on Quest 3 Snapdragon XR2 Gen 2): halves bitrate for identical visual clarity, reducing network serialization and router/NIC latency.

### Priority 4: Zero-Copy GPU Hardware Decode (D3D11 DXVA / NVDEC -> D3D12 Shared Handle)
- Currently `MfH264Decoder` decodes on CPU (`~1.3 ms`) and copies 5.5 MB NV12 -> CPU RAM -> D3D12 Upload Heap (`~0.5 ms`).
- Ampere-class NVIDIA GPU has a dedicated **NVDEC** silicon block that decodes 2560x1440 H.264 in **~0.4–0.6 ms** directly in VRAM.
- **Warning / Pitfall to test carefully**:
  - `CLSID_CMSH264DecoderMFT` with `IMFDXGIDeviceManager` (D3D11 DXVA) OR `ID3D12VideoDecoder` can silently buffer 1–4 frames in DPB if not configured properly!
  - **ALWAYS write a 0-frame-delay test first**: feed 1 IDR + 1 P-frame and assert that `ProcessOutput` returns a frame IMMEDIATELY after EACH single `ProcessInput` (0 buffered frames!).
  - If 0-frame delay is confirmed, share the D3D11 NV12 texture into D3D12 via `IDXGIResource1::CreateSharedHandle` + `ID3D12Device::OpenSharedHandle`, copy directly into NVENC's registered D3D12 NV12 input surface (literally **0 CPU bytes touched**!), and run frame-change dedup via a tiny D3D12 compute shader (16x16 downsampled luma SAD) instead of CPU `PictureReference`.

### Priority 5: Minimize Latency When 30 -> 60 FPS Frame Generation IS Active
- Right now, in true 30->60 FG mode, **interpolation** (`interp(N-1, N)` then `real(N)` 16.67 ms later) inherently adds ~16.67 ms of temporal cadence latency because `real(N)` waits behind `interp(N-1, N)`.
- Ways to shrink FG latency further:
  1. **Downscale NVOFA input to half-res (`1280x720` when input is `2560x1440`)**: `FlowRefine` ALREADY runs at half-res `flowW_ x flowH_`! Running NVOFA on 1280x720 instead of 2560x1440 cuts pixel area by **4x** (~4.5 ms -> **~1.1 ms** on Ampere OFA!).
  2. **Experimental Extrapolation Mode (0 ms cadence latency!)**: Instead of waiting to interpolate `N-1 -> N` and holding `real(N)` for 16.67 ms, send `real(N)` **IMMEDIATELY** at `t = 0`, then extrapolate `t = 1.5` forward using the optical flow `(N-1 -> N)` at `t = +16.67 ms`! This trades slight disocclusion artifacts at fast screen edges for **-16.67 ms lower input lag** (ideal for competitive / driving feel in GTA!). Could be exposed as a HUD toggle on Quest: `FG: Off | Extrap (Low Lag) | Interp (Smooth)`.
  3. **Direct ABGR/RGBA NVENC input** (or fused `Rcas + RgbToNv12` shader): When upscaling/FG is active, fusing `RcasSharpen` + `RgbToNv12` into one compute dispatch eliminates an entire full-frame 1440p/4K read+write + barrier + command list submission (`~0.5–1.0 ms`).

### Priority 6: Xbox-Side & Network-Side Latency (`rtt = 14–19 ms`)
- Verify whether the Xbox Series X (<your console>) is on **Wi-Fi** or **wired Ethernet**. Direct Gigabit Ethernet on the Xbox itself typically drops `rtt` from `14–19 ms` down to **`1–2 ms`**!
- Check if `WebRtcSession.cpp`'s `input` DataChannel can safely use unordered/partial-reliability (`maxRetransmits = 0`) for gamepad frames (verify against Xbox Home Streaming protocol expectations first — do NOT break handshake!).
- Verify Xbox Remote Play resolution/encoding overhead: `WebRtcSession::sendClientConfig()` requests 1920x1080, yet Xbox streams 2560x1440 (`14 Mbps`). Investigate whether Xbox Series X encodes 1080p with lower console-side encode latency than 1440p (worth benchmarking if user wants an ultra-low-lag 1080p mode).

---

## 4. Phase 3: The 618 ms Decoder Buffer (ROOT CAUSE OF "half a second lag")

**Symptom**: user reported ~500 ms lag vs the TV, and confirmed the *official Xbox app in the Quest is smooth* on the same console/network/headset. That proved the delay was ours, not the console's.

**Root cause**: `CLSID_CMSH264DecoderMFT` buffers ~37 frames of output by default.
`CODECAPI_AVLowLatencyMode = TRUE` alone is **not sufficient**. You must also set the
attribute on the MFT itself:

```cpp
ComPtr<IMFAttributes> attrs;
if (SUCCEEDED(mft->GetAttributes(&attrs)) && attrs) attrs->SetUINT32(MF_LOW_LATENCY, TRUE);
```

Measured `decLag` (age of the frame that comes out of the decoder vs the AU just submitted):
`617.7 ms -> 0.0 ms`.

**Why it was invisible**: `ProcessInput` returns in ~1.2 ms, and `pcLat` tags each frame with the
arrival time of the *submitted* unit, so the buffering silently subtracted itself from every
reported number. **Always measure frame *identity* (PTS), not just call duration.**

**Cost of the fix**: `MF_LOW_LATENCY` implies single-threaded decode, so `dec` rose 1.2 -> 6.2 ms
(1440p) / 8.4 ms (4K). Do NOT also pin `CODECAPI_AVDecNumWorkerThreads = 1` (measured ~0.5 ms worse).
Trade of 6.2 ms for 618 ms is obviously correct.

**Side effect**: Xbox `rtt` dropped 17-19 ms -> 13-14 ms, because presentation feedback
(`rtc.videoPresented`) is no longer 600 ms late, so console congestion control stops seeing a
stalled receiver.

### Telemetry added (keep these, they are how the above was found)
- `decLag=avg/max` - decoder buffering depth in ms. **Must stay ~0.**
- `enc[s= p= w= l= c=]` - encoder phases: submit / `nvEncEncodePicture` / **fence wait** / lock / callback.
- `quest=min/avg` - per-frame present echo from the Quest (`kTypeClientRendered = 0x84`); measures
  socket-write -> Quest decoded+presented -> echo received.
- `--max-bitrate <Mbps>` - cap what the Quest client may request.

### Measured baseline after Phase 3 (2560x1440@60 source, RCAS 1:1)
| Stage | ms |
|---|---|
| MF H.264 software decode (single-threaded, low latency) | 6.2 |
| GPU pipeline (Nv12ToRgb + RCAS) | 0.3 |
| NVENC phases: submit 0.08 / call 0.41 / **fence wait 4.78** / lock 0.02 / copy 0.03 | 5.4 |
| **PC total (arrival -> socket)** | **~13** |
| PC -> Quest -> presented -> echo back | 6-9 |

### Calibration facts (measured, do not re-litigate)
- NVENC time is **fixed cost**, not bitrate-bound: 4.94-5.31 ms across 42/80/118 Mbps.
- NVENC **is** pixel-bound at 4K: 5.4 ms (1440p) vs **10.8 ms** (2160p). 4K costs ~5.4 ms of latency
  purely to upscale away from a 1440p source.
- Xbox source bitrate is **~13 Mbps @ 2560x1440** and is chosen by the console; our
  `x-google-*bitrate` fmtp hints are stripped from its answer SDP. No client knob in `play()`.
- **Unplugging the Xbox HDMI cable fixed a real bug**: with HDMI attached the console's capture
  produced near-identical consecutive frames, so `ContentDedup` scored them as duplicates
  (`uniq` fell to 32.9 on true 60 fps content) and the adaptive FG gate flipped to frame
  generation, which doubled GPU work and spiked `pcLat` to 11.6-15.5 ms with output collapsing to
  41-53 fps. With HDMI removed `uniq` sits at 56-60, output is a steady 59-60 fps and `pcLat` max
  fell to 8.6 ms.

### Next: hardware decode (this was Phase 3's follow-up)
Remaining largest PC-side term is the 6.2 ms software decode. Target: DXVA2/NVDEC via
`IMFDXGIDeviceManager` (`MFT_MESSAGE_SET_D3D_MANAGER`) -> D3D11 NV12 texture -> shared handle ->
D3D12, expecting <1 ms decode + ~1 ms readback.
**Critical guard**: the notes above warn DXVA can silently add DPB buffering. Verify with the
`decLag` metric - it must remain ~0.0. Do not ship it without that check.

### Phase 3b result: DXVA implemented and verified
Hardware decode is live (`mf-h264: DXVA hardware decode enabled (D3D11 + IMFDXGIDeviceManager)`):
- D3D11 device with `D3D11_CREATE_DEVICE_VIDEO_SUPPORT` + `ID3D10Multithread::SetMultithreadProtected`,
  `MFCreateDXGIDeviceManager`, `MFT_MESSAGE_SET_D3D_MANAGER` before setting types.
- Output samples are `IMFDXGIBuffer` -> `ID3D11Texture2D` -> `CopySubresourceRegion` into a
  `D3D11_USAGE_STAGING` NV12 texture -> `Map` -> CPU NV12 (existing pipeline path unchanged).
  For NV12, UV starts at `pData + RowPitch * height`.
- **`decLag` stayed 0.0/0.0 ms** - DXVA did not reintroduce buffering. Image verified visually.
- `dec` **6.2 ms -> 3.7 ms**. Remaining cost is the staging readback (`Map` sync + 5.5 MB memcpy),
  not the decode itself; ~2-3 ms of the 3.7 is readback.
- Include note: `<d3d10_1.h>` must precede `<d3d11.h>` (plain `d3d10.h` errors out).

### Current measured baseline (2560x1440@60, RCAS 1:1, 60 fps source)
| Stage | ms |
|---|---|
| DXVA decode + readback | 3.7 |
| GPU pipeline | 0.3 |
| NVENC (fence wait dominates) | 5.1-5.4 |
| **PC total (arrival -> socket)** | **~9.9-10.4** |
| PC -> Quest -> presented -> echo | 4.8-5.4 min / 11.5-13 avg |

### Test1 token round-trip in a confined shell (was: "Test1 DPAPI failure")
`Test1` used to fail at `saveTokens DPAPI failed` when run from a sandboxed shell. Root cause
measured during Phase 7: **`CryptProtectData` was fine** - the write to `%TEMP%` was denied
(`errno=13`, `EACCES`), because a confined shell can allow writes next to the binaries while denying
the temp directory. `Test1` now probes `%TEMP%` and falls back to the working directory, and skips
the round-trip (printing the Win32 error) only when DPAPI itself is unavailable. The suite is
**`ALL 6 TESTS PASSED`**, exit code 0.

### 4K output is over the per-frame budget (measured end of Phase 3)
A long 4K run showed the pipeline cannot hold 60 fps at 2160p:

```
[UPSCALE] -> 3840x2160@60 | pcLat=14.8-16.2ms dec=3.6-4.0ms gpu=0.6ms nvenc=10.3-10.7ms
out=44.8-58.9 fps  uniq=44.8-58.9  qDrop=4  tcpDrop=8
```

Per-frame cost ~= dec 3.8 + gpu 0.6 + nvenc 10.6 = **~15 ms of a 16.67 ms budget (~90% duty)**, so
any jitter drops frames and output falls to 44-59 fps. At 1440p the same sum is ~9.9-10.4 ms
(~62% duty) and holds a steady 59-60 fps.
**Recommendation: run 1440p unless visual sharpness matters more than a stable 60 fps.** 4K buys
nothing in detail anyway - the source is 2560x1440.

---

## 5. Phase 4: Lifting the Xbox source bitrate with REMB (the ~15 Mbps "cap" is self-inflicted)

**Symptom**: the console appeared hard-capped near **14-15 Mbps** at 2560x1440, so fast motion looked
grainy (about 0.068 bits/pixel - the encoder simply runs out of bits in motion, and no PC-side
processing can restore detail that was never sent).

**Root cause**: not a console cap. `libdatachannel`'s `RtcpReceivingSession` sends **RTCP Receiver
Reports only** unless someone calls `requestBitrate()`:

```cpp
// rtcpreceivingsession.cpp
if (unsigned int bitrate = mRequestedBitrate.load(); bitrate > 0)
    pushREMB(send, bitrate);
```

`mRequestedBitrate` defaults to **0**, so we were advertising *no* bandwidth estimate at all. With no
REMB the console's encoder falls back to its own conservative default of ~14-15 Mbps.

**Fix**: `Track::requestBitrate(bps)` (public API) once connected, repeated about twice a second
(`WebRtcSession::pumpBitrateRequest`, called from the frame loop). New flag `--xbox-bitrate <Mbps>`,
default **50**.

**Measured results (same console, same LAN, 1440x1440):
| Advertised REMB | Xbox source bitrate |
|---|---|
| none (old behaviour) | **13.1-15.7 Mbps** |
| 60 Mbps | **23-24 Mbps** |
| 150 Mbps | **22-24 Mbps** (no further gain) |

So the console's own ceiling is **~24 Mbps**; anything above ~25 advertised is wasted but harmless.
That is a **~60% increase in source bits at zero latency cost** - pure quality, and it is the only
client-side lever on Xbox->PC video quality. `x-google-max-bitrate` fmtp hints are stripped from the
console's answer SDP, so those do nothing; REMB is RTCP and *is* honoured.

**Also relevant to grain**: the PC used to re-encode at ~8x the source (~118 Mbps), faithfully
preserving the console's quantization noise, and RCAS at 0.25 amplified it further. `--sharpness`
is now configurable (default 0.25, try 0.05-0.12 for a low-bitrate source).

---

## 6. HANDOFF: current state, what is maxxed, what is left

> State as of the end of Phase 4, measured during live play at 2560x1440 source.

### 6.1 Steady-state numbers (2 minute sample, active play)

| Metric | Value |
|---|---|
| Output frame rate | 57.6-60.5 fps (avg 59.5) |
| **PC latency (RTP arrival -> socket)** | **9.33-10.64 ms, avg 9.96** |
| Decode (DXVA) | 3.81 ms |
| GPU pipeline (RCAS 1:1) | 0.31 ms |
| NVENC | 5.09 ms (fence wait ~4.8 of that) |
| Xbox source bitrate | 21.6-24.9 Mbps (avg 23.4) - at the console's ceiling |
| PC -> headset | 79.2 Mbps |
| `decLag` | **0** |
| Queue depth / new drops | qMax 1 / none |
| Headset round-trip (`quest=`) | 10.08 ms avg |
| Video mode | RCAS 100%, no frame-gen flapping |

Journey this session: **~630 ms -> ~10 ms** on the PC side. The 618 ms decoder buffer was the
dominant cause and is gone.

### 6.2 The 10 ms budget, decomposed
```
decode (DXVA + staging readback)   3.81
GPU pipeline (Nv12ToRgb + RCAS)    0.31
NVENC (fence wait dominates)       5.09
thread handoff / loop overhead    ~0.75
--------------------------------------
                                   ~9.96
```

### 6.3 Genuinely maxxed - do not spend time here
1. **Decoder buffering**: `decLag` is 0. The MFT emits as it decodes.
2. **Queueing**: qMax is 1, no new drops. Nothing to drain.
3. **Frame-gen gate**: stable (`uniq` 59-60, RCAS 100%). The old flapping came from per-frame
   samples; it now uses an EMA plus a 34-46 fps dead band and 12-frame streaks.
4. **Xbox source bitrate**: at the console's own ~24 Mbps ceiling. Advertising 150 instead of 50
   changes nothing, so the console is the limiter.
5. **Output resolution**: capped at 1440p by default and matched to the source. 4K was measured
   over-budget (10.8 ms NVENC, ~90% duty, fps collapsed to 41-53).
6. **NVENC bitrate**: proven flat - 4.94-5.31 ms across 42/80/118 Mbps. Do not trade quality for
   bitrate believing it buys latency; it does not.

### 6.4 Not maxxed - ranked by expected value
1. **Occasional TCP send stall (17.3 ms max, avg <1 ms).** With `SO_SNDBUF = 128 KB` at 80 Mbps
   that is only ~13 ms of buffer, so a brief headset-side hiccup back-pressures the encoder.
   Raising the send buffer (256-512 KB) should absorb it. Low risk, improves consistency.
2. **DXVA staging readback (~2-3 ms of the 3.81).** True zero-copy: share the D3D11 NV12 surface
   into D3D12 and feed the pipeline directly. This also requires moving frame-change dedup to a GPU
   shader (it currently reads CPU planes). Expected ~2 ms (~20% of PC latency); medium effort.
3. **NVENC preset.** P4 ULL is used. P3/P2/P1 were only ever tested alongside other simultaneous
   changes (wrong VBV/AQ/GOP), so the result is not trustworthy. Worth one clean A/B measuring
   `enc[p..w]` and visual quality at the current bitrate. Possible 1-2 ms; quality risk.
4. **HEVC (H.265).** Quest 3 is Snapdragon XR2 Gen 2 and decodes HEVC fine. Roughly halves the
   bitrate for equal quality, so the same 80 Mbps headset cap would carry visibly more detail - or
   the same quality at lower NVENC cost. **Implemented in Phase 7 (`--codec hevc`) - see section 9.**
5. **UDP video transport.** TCP head-of-line blocking is the root cause of (1) and of every
   retransmit stall. Tracked in Phase 2 Priority 3. Larger project: fragmented AUs + IDR recovery.
6. **HDR / colour range mismatch.** The Quest decoder reports `hdr-static-info` present and
   `color-range=2` (full range), while NVENC is configured limited-range BT.709. That is a
   colour/contrast problem, not latency, but it is probably visibly wrong and worth checking.
7. **Audio + true glass-to-glass.** Audio is PCM over the same TCP stream (untested for latency),
   and nobody has yet measured real glass-to-glass. A clock burned into a corner of each encoded
   frame plus a screenshot would settle it (see 6.5).

### 6.5 Measurement methodology - do not relearn this
- **`decLag`** = age of the frame that comes out of the decoder vs the AU just submitted. This is
  the metric that exposed the 618 ms bug. **It must stay ~0.** Never trust per-call duration alone:
  the old decoder returned from `ProcessInput` in 1.2 ms while holding 37 frames.
- **`enc[s= p= w= l= c=]`** = submit / `nvEncEncodePicture` / fence wait / lock / callback, per frame.
- **`quest=min/avg`** = per-frame present echo from the headset (`kTypeClientRendered = 0x84`), which
  measures socket-write -> decoded -> presented -> echo received for that specific AU.
- `pcLat` alone is misleading if any stage buffers: it tags the frame with the arrival time of the
  *submitted* unit. Always cross-check with `decLag` and `quest=`.
- Useful commands:
  - `adb -s <quest-ip>:5555 logcat -d | Select-String 'GTA60'` (headset-side fps/Mbps/decode)
  - `dumpsys SurfaceFlinger --list` to find the video layer
  - `Get-Content build\perf-telemetry.log -Tail N` for the PC side

### 6.6 Pitfalls that already cost hours - read before changing anything
- `CODECAPI_AVLowLatencyMode` alone does nothing useful; **`MF_LOW_LATENCY` on the MFT attributes**
  is what removes the ~37-frame queue.
- `MF_LOW_LATENCY` implies single-threaded decode. Do **not** also pin
  `CODECAPI_AVDecNumWorkerThreads = 1` (measured ~0.5 ms worse).
- DXVA can silently reintroduce DPB buffering - always verify `decLag` after touching it.
- Include `<d3d10_1.h>` before `<d3d11.h>` (plain `d3d10.h` hard-errors).
- NVENC D3D12 needs the fence point honoured; the `enc[w]` phase should be the dominant cost.
- `x-google-*bitrate` fmtp params are stripped from the console's answer SDP - they do nothing.
  **REMB is the working lever** for source bitrate.
- Frame-gen gating off raw per-frame fps samples causes mode flapping; use smoothed + hysteresis.
- With HDMI attached at 4K the console can emit near-duplicate consecutive frames, which previously
  tripped dedup into spurious frame generation.

### 6.7 Reproducing the current configuration
```powershell
scripts\build.ps1                                    # PC
scripts\build-quest-apk.ps1                          # headset
scripts\install-autostart.ps1 -StartNow              # agent at sign-in (WOL -> start app)
GTA-Sixty.exe --connect auto --quest-server --bitrate 120 --sharpness 0.12
```
Defaults already include `--xbox-bitrate 50` (REMB) and `--max-output-height 1440`.
Recommended headset settings: 2560x1440 output, 80 Mbps, 60 FPS.

### 6.8 Bottom line for the next agent
The PC adds ~10 ms and is no longer the bottleneck. What remains upstream/downstream - the console's
~24 Mbps encoder, the LAN round trip (rtt 16-21 ms), and headset decode + a 72 Hz panel scanout -
dominates what the player feels. Squeezing another 2-3 ms out of the PC (items 1-2 above) is
worthwhile but will not transform the experience; **the highest-value remaining work is the TCP
stall (1) and, if quality is the complaint, HEVC (4) - now implemented, see section 9.**

---

## 7. Phase 5: TCP send stall, DXVA readback, and the NVENC preset A/B

### 7.1 TCP send stall - fixed
`setSendBufferBytes` was still the Phase-1 value of 128 KB, which at 80 Mbps is only ~13 ms of
kernel buffer, so a brief headset-side hiccup blocked `send()` and back-pressured the encoder.
Measured `tcp=<avg>/<max>`: **17.27 ms max -> 0.20-0.26 ms** after raising it to **384 KB**.
This was the last visible jitter source on the PC side.

### 7.2 DXVA readback - cheap part done, zero-copy still open
The staging readback copied NV12 scanline by scanline (1440 + 720 memcpy calls per frame). When
`RowPitch == width` (the normal case) it now copies each plane in a single memcpy. `dec` went
3.81 -> ~3.50 ms.
**Still open**: true zero-copy (share the D3D11 NV12 surface into D3D12 and feed the pipeline
directly) would remove the `Map` sync as well, but it requires moving frame-change dedup to a GPU
shader because dedup reads CPU planes. ~2 ms available, medium-sized refactor.

### 7.3 NVENC preset A/B (the biggest remaining PC win)
Measured at 2560x1440, ~24 Mbps source, 80 Mbps output, 10 samples each:

| preset | nvenc ms | dec ms | **pcLat ms** | fps |
|---|---|---|---|---|
| 4 (old default) | 5.18 | 3.56 | **9.80** | 59.5 |
| **2 (new default)** | **3.93** | 3.55 | **8.50** | 59.0 |
| 1 | 3.88 | 3.52 | 8.48 | 58.0 |

**P2 saves ~1.25 ms of encode and ~1.3 ms of PC latency**; P1 buys only another 0.05 ms, so P1 is
not worth its quality cost. Default is now `--preset 2` (`--preset 4` reverts).
Note this is P2 with AQ=8, 250 ms VBV and gop=120 intact - it is NOT the old P1/low-VBV experiment
that looked pixelated (that regression came from the combination of 1-frame VBV + no AQ +
infinite GOP, not from the preset alone).

Live result after the change: **pcLat 9.96 -> 8.43 ms**, decode 3.50, still 59-60 fps, decLag 0.

### 7.4 Frame generation is a 2x/3x multiplier - and 90 fps DOES work
> **Corrected 2026-11-20 after a live A/B.** An earlier version of this section claimed the output
> "cannot reach 90 fps" and that the headset's 90 fps button was "counter-productive". The first
> claim was reasoning about a *60 fps source* and was wrong as a general statement: on 30 fps content
> (the normal case here) the 3x path has always worked. The measurements below replace the guesses.

What the code actually does (verified by reading it, not by trusting this document):
- `main.cpp` computes `fgMult = round(targetFps / contentFps)`, clamped 1..4, per frame, from the
  instantaneous interval between *forwarded* frames.
- `EnhancementPipeline::initialize` allocates `outputSlots_` with
  `multiplier = targetFps >= 90 ? 3 : 2`, and `processUniqueFrame` clamps the requested multiplier to
  `outputSlots_.size()`. So the **effective** maximum is 2x at a 60 fps target, 3x at 90.

That clamp is why output fps is content rate x multiplier:

| headset setting | content (uniq) | out fps | NVENC ms | pcLat |
|---|---|---|---|---|
| 60 fps @ 50 Mbps | 21.7 | 42.2 | 6.62 | **21.8** |
| 90 fps @ 50 Mbps | 28.0 | **83.8** | 6.13 | **34.0** |
| 90 fps @ 80 Mbps | 27.7 | **81.4** | 6.20 | **33.8** |
| 90 fps @ 120 Mbps | 29.9 | **79.1** | 6.46 | **33.6** |
| 60 fps @ 120 Mbps | 27.6 | 55.1 | 6.98 | 26.7 |

(Sample counts for those windows: 121, 10, 81, 36 and 239 two-second samples. The 90 fps @ 50 Mbps
window is short because the setting was changed quickly, so treat its numbers as indicative; the
90 fps @ 80 and @ 120 windows agree with each other to within 0.2 ms.)

- **90 fps is reachable** from ~30 fps content via 3x interpolation: 79-84 fps measured.
- It costs **~12 ms** of PC latency (33.8 vs 21.8 ms). That - not impossibility - is the reason to
  keep the headset at 60. The panel caveat in 8.3 (72 Hz render rate) still applies.
- With a genuine **60 fps source** nothing engages: the gate needs the *smoothed* content rate below
  34 fps, so `fgActive` stays false and output stays 60. That part of the old text was correct.

**Known inconsistency (open, not fixed):** `main.cpp`'s own comment says the multiplier is chosen "so
the OUTPUT lands on targetFps, whatever rate the content happens to be changing at", but the 2-slot
clamp makes that false below a 90 fps target: a ~15 fps content rate yields 2x = 30 fps, not 60. This
is visible in 9.7. Fixing it means sizing `outputSlots_` for 4x (about +30 MB of 1440p textures) and
accepting 4x interpolation artefacts on slow content - a design decision, not a one-line change.

### 7.5 HEVC (item 4 of the previous handoff) - done in Phase 7
Implemented as `--codec h264|hevc`, a codec byte in the `VIDEO_CONFIG` packet, and `video/hevc` plus
`VPS/SPS/PPS -> csd-0/1/2` on the headset. See **section 9** for what changed, the one trap that
matters (HEVC VUI timing has no factor of 2), and what still needs measuring on real content.

---

## 8. Phase 6: Frame generation was silently dead (dedup was blind to compression noise)

**This was the biggest functional bug left**: the project exists to interpolate frames, and frame
generation never once engaged on real content.

### 8.1 Root cause
`PictureReference` uses a +/-3 level band and `toleranceFor(total) = total / 196608`, i.e. on a
2560x1440 luma plane only **~18 pixels out of 3.69 million** may deviate by more than 3 levels for
two frames to count as identical. That is an HDMI-capture-noise threshold. A duplicated frame from
the Xbox is *not* identical after decode - it carries different quantization noise - so it always
failed the test.

Measured with `--dump-pic` (sampled luma difference vs the previous frame), on a known 30 fps game:

| | luma MAD | frames |
|---|---|---|
| repeated frames (same picture) | **~0.19** | ~74-78% |
| genuine new frames | **~2.19** | ~22-26% |

A **>10x separation**, and dedup was keeping **97%** of frames (`uniq` reported 59-60 on a 30 fps
game). Because `uniq` looked like 60, the adaptive gate never saw a 30 fps source and frame
generation stayed off. The notes had predicted this exact failure in "Phase 2 Priority 1" but the
threshold was never retuned.

### 8.2 Fix
- Sampled luma MAD (every 8th column, 4th row - ~0.1 ms) replaces the strict band test as the
  repeat signal: a frame is a repeat when `madY < --dedup-mad` (default **0.5**, 0 disables and
  restores the old behaviour). Dropping a frame that is visually identical is invisible regardless
  of whether it is a true duplicate or a static scene, so this is safe.
- The content rate fed to the adaptive gate is now derived from the **interval between frames we
  actually forwarded** (`1e7 / interval`), not from the broken dedup output. That is what makes the
  gate see ~30 fps.
- `--dump-pic <file>` writes the per-frame histogram used to derive the threshold.

### 8.3 Result
```
Adaptive FG: ENABLED (input ~30.3 fps -> 60 fps)
uniq=30.0  out=88.5-91.2 fps  [FG]  -> 2560x1440@90
pcLat=28.4-28.8ms  gpu=5.22ms(flow=4.63)  nvenc=5.23ms  decLag=0
```

**90 fps output is now achieved** (30 fps source, 3x interpolation), which was the original goal.
But the trade is explicit and large:

| mode | output fps | pcLat | gpu (NVOFA) |
|---|---|---|---|
| passthrough (dedup disabled) | 60 (30 duplicated) | **8.4 ms** | 0.3 ms |
| frame generation 2x | content x2 | **~22.9 ms** (measured 2026-11-20, 7.4/9.7) | ~4.7 ms |
| frame generation 3x | content x3, up to ~84 | **~33.8 ms** (measured 2026-11-20, 7.4) | 4.6 ms |

The "est." is gone: both modes have since been measured live. The 3x figure belongs to a 90 fps
target, and note that the multiplier is capped by the number of output slots (7.4), so "2x"/"3x" is a
ceiling, not a promise - slow content lands below the target rate.

This is the fundamental FG tradeoff the project always had: interpolation waits for the next real
frame, so output frame rate goes up while input-to-photon latency goes up with it.

**Panel caveat**: the headset reports a **72 Hz** render rate (a 60 Hz mode also exists). A 90 fps
stream into a 72 Hz panel means part of the output is never shown, so 60 fps (2x) is the better fit
unless a 90 Hz mode can be forced.

### 8.4 Choosing
- Latency priority: `--dedup-mad 0` (or `--no-fg`), 60 fps passthrough, ~8.4 ms pcLat.
- Smoothness priority: leave it on; 60 fps (2x) at **~22-23 ms measured** is the usual compromise.
- 90 fps: works (79-84 fps measured, 7.4) but costs **~34 ms** and a 72 Hz panel will not display all
  of it.

---

## 9. Phase 7: HEVC (H.265)

Item 4 of the previous handoff, and the last unclaimed quality lever. Implemented end to end on both
sides; **correctness is verified by the test suite, but no live latency/bitrate A/B has been run yet**
(see 9.5).

### 9.1 What changed

| Layer | Change |
|---|---|
| `include/gta60/media/VideoCodec.h` | New `media::VideoCodec { H264, HEVC }`, plus `videoCodecName()` / `videoCodecMime()`. The values are **wire values** - append, never renumber. |
| `include/gta60/sink/NvencD3D12Encoder.h` | `NvencConfig::codec` (default `H264`) and a `codec()` accessor. |
| `src/sink/NvencD3D12Encoder.cpp` | Selects `NV_ENC_CODEC_HEVC_GUID` + `NV_ENC_HEVC_PROFILE_MAIN_GUID`, configures `encodeCodecConfig.hevcConfig`, and gets `VPS+SPS+PPS` from the same `nvEncGetSequenceParams` call. |
| `include/gta60/sink/QuestStreamServer.h`, `src/sink/QuestStreamServer.cpp` | `VIDEO_CONFIG` payload grew a codec byte: `u32 width | u32 height | u8 codec | Annex-B csd`. The LAN discovery offer's codec field is now `H264`/`HEVC` instead of a hardcoded `H264`. |
| `quest-app/.../MainActivity.java` | `video/hevc` when the config says HEVC; the old 2-way `splitSpsPps` became `splitNalUnits`, which yields SPS/PPS for AVC and VPS/SPS/PPS for HEVC, each mapped to `csd-0..2`. |
| `src/main.cpp` | `--codec h264|hevc` (`h265`/`avc` accepted as aliases), plumbed to all three `NvencConfig` construction sites and both `QuestStreamServer::start` calls. |
| `tests/PipelineAndXboxTests.cpp` | Test6 now runs the full NVENC -> GT60 -> loopback round trip **once per codec** and asserts the NAL order (H.264 SPS/PPS, HEVC VPS/SPS/PPS), the codec byte in `VIDEO_CONFIG`, and the csd length. |

Rate control is deliberately **identical** to H.264: CBR, single pass, `zeroReorderDelay = 1`,
`enableAQ = 1` / `aqStrength = 8`, `gopLength = idrPeriod = 120`, `repeatSPSPPS = 1`, no AUD/SEI, and
the 250 ms VBV. Switching codec changes nothing else in the pipeline, so an A/B isolates the codec.

### 9.2 The trap: HEVC VUI timing has no factor of 2
This is the same class of bug as the 30-fps pacing problem in section 6, and it is easy to get
backwards:

- **H.264**: frame rate = `time_scale / (2 * num_units_in_tick)` -> emit `numUnitInTicks = 1`,
  `timeScale = 2 * fps`.
- **HEVC**: frame rate = `time_scale / num_units_in_tick` (with `field_seq_flag = 0`) -> emit
  `numUnitInTicks = 1`, **`timeScale = fps`**.

Copying the H.264 formula into the HEVC VUI would advertise a 60 fps stream as **120 fps**. Both
codecs still set `timingInfoPresentFlag = 1` and `bitstreamRestrictionFlag = 1`; with
`zeroReorderDelay = 1` the latter makes NVENC write `max_num_reorder_pics = 0`, which is what keeps
MediaCodec from queueing frames in the DPB. **If HEVC ever looks like it has ~33-66 ms of extra lag,
check `decLag` on the headset first** - it should be ~0 exactly as for H.264.

### 9.3 Parameter sets are one blob on the wire, three buffers at the decoder
`nvEncGetSequenceParams` returns `VPS+SPS+PPS` concatenated with start codes for HEVC (`SPS+PPS` for
H.264). Android wants one `csd-N` buffer per parameter set, and for HEVC the documented mapping is
`csd-0 = VPS`, `csd-1 = SPS`, `csd-2 = PPS`. The headset therefore splits the blob itself rather than
the PC sending three length-prefixed pieces - the wire format stays codec-agnostic apart from the
codec byte, and the whole csd blob is still fed once more as `BUFFER_FLAG_CODEC_CONFIG` before the
first AU.

### 9.4 Protocol compatibility
The `VIDEO_CONFIG` layout changed, so **PC and headset must be updated together**. An APK built
before Phase 7 reads the codec byte as the first byte of the parameter sets and will fail to
configure its decoder. There is no version negotiation yet; if that becomes a problem, add a bit to
the packet `flags`/`meta` and fall back to the 8-byte layout.

### 9.5 What still needs measuring (do this before claiming a win)
The expectation is ~half the bitrate for the same quality, or the same quality at a lower NVENC
cost. That is a hypothesis until measured on this hardware.

**First data point (Test6, synthetic 1080p->1440p source, 9 AUs, identical NVENC settings):**

| codec | bytes | csd |
|---|---|---|
| H.264 | 818,298 | 41 B (SPS+PPS) |
| **HEVC** | **457,918** | 94 B (VPS+SPS+PPS) |

That is **-44% bytes for the same frames and the same encoder settings** on synthetic content -
in the expected direction, but *not* a substitute for real gameplay measurement, which has more
motion and texture.

**Measured on the real headset (2026-11-20, 2560x1440 @ 60, P2 ULL, same settings both sides):**

```
[headset] Received VIDEO_CONFIG 2560x1440 @ 60fps codec=video/hevc csd=94B
[headset] MediaCodec started (c2.qti.hevc.decoder) 2560x1440 video/hevc csdUnits=3 csd0=28B
[headset] First video frame rendered (2560x1440)
```

`c2.qti.hevc.decoder` is Qualcomm's hardware decoder, `csdUnits=3` proves VPS/SPS/PPS were split
correctly, and the `quest=` present-echo (~6.4 ms) proves it is presenting rather than merely
parsing. **HEVC works end to end on Quest 3.**

The numbers do not flatter HEVC on the encoder side, though:

| codec | NVENC ms (fence wait in brackets) | headset request |
|---|---|---|
| H.264 | 5.70-5.79 (5.38-5.48) | 50 Mbps |
| HEVC | 6.65-6.69 (6.29-6.37) | 50 Mbps |

At 1440p/P2 **HEVC costs ~0.9 ms more per frame, not less**. "Same quality for fewer bits" is a
rate-distortion claim, not an encoder-throughput one, and with CBR the telemetry's `out=Mbps` only
reflects the cap, so it says nothing about efficiency. Whether HEVC looks better at the same 50 Mbps
is a visual judgement on real gameplay - the one thing still not measured.

On real content:

1. Rebuild + reinstall the APK, then run with `--codec hevc`. Confirm in logcat:
   `adb -s <quest-ip>:5555 logcat -d | Select-String 'GTA60'` -> `MediaCodec started (...) video/hevc
   ... csdUnits=3`.
2. Check `decLag` stays ~0 and the output still holds 59-60 fps - a buffering HEVC decoder shows up
   as extra headset latency, not as a frame-rate drop.
3. A/B at the **same** headset settings (2560x1440, 80 Mbps, 60 FPS) against `--codec h264`:
   - `nvenc=` from the telemetry line (is HEVC actually cheaper at equal quality?),
   - `pcLat`,
   - and visual quality in fast motion. With HEVC set the headset bitrate *lower* (e.g. 50 Mbps) and
     compare against H.264 at 80 Mbps for the real "same quality, half the bits" test.
4. Watch for encoder-session failures on a driver update: HEVC Main 4:2:0 8-bit is the only profile
   configured here (no Main10), so 10-bit/HDR content is out of scope until someone adds it.

### 9.6 Known gaps
- No Main10 / HDR (`outputBitDepth` stays 8-bit). The colour-range mismatch from 6.4 item 6 is
  untouched and applies equally to both codecs.
- No B-frames for either codec (`frameIntervalP = 1`), so HEVC's B-frame compression advantage is
  not being used - correct for latency, and consistent with the H.264 baseline.
- The headset's bitrate/FPS selectors are unchanged; HEVC does not add a codec toggle to the HUD, so
  the codec is chosen on the PC with `--codec`.

### 9.7 Stress test: fast driving (2026-11-20, HEVC, 1440p60, headset 120 Mbps)
124 s of active driving in a demanding open-world game, 62 telemetry samples:

| metric | min | avg | max |
|---|---|---|---|
| output fps | 29.9 | 54.3 | 60.2 |
| **pcLat** | **22.07** | 22.9 | **23.51** |
| uniq (kept content fps) | 14.9 | 27.2 | 30.1 |
| NVENC | 6.78 | 7.10 | 7.40 |
| decode | 3.44 | 3.53 | 3.70 |
| GPU (NVOFA flow) | 4.72 (4.34) | 5.15 (4.68) | 5.44 (4.96) |
| PC -> headset | 49.2 Mbps | 106.9 | 127.2 |
| `rtt` | 15 | 17.4 | 21 |

What this proves:
- **Nothing is dropped under load**: `qDrop` delta 0, `tcpDrop` 0, `qMax` peak 1, over 124 s at up to
  127 Mbps over Wi-Fi. The only anomaly was a single 13.7 ms TCP send spike, absorbed by the 384 KB
  send buffer (7.1).
- **`decLag` stayed 0.00 ms** for every sample - DXVA at 1440p still never buffers.
- **`pcLat` spread was 1.44 ms.** Fast motion raises the work (NVENC 6.65 -> 7.40 ms, NVOFA flow
  4.34 -> 4.96 ms) but the budget absorbs it, so it shows up as a slightly higher *fixed* cost rather
  than as jitter. This is the single most reassuring number in these notes.
- **Output fps sags with content rate, and that is not frame loss.** `out` is content x multiplier,
  so `uniq=14.9 -> out=29.9` while `uniq=30 -> out=60`. 19 of 62 samples were below 55 fps purely
  because the scene stopped changing (or the deduper culled it), not because the pipeline missed a
  deadline - see the 2-slot clamp in 7.4, which is why a 60 fps target cannot compensate with 4x.
  Whether that sag is *visible* depends on whether it happens while the camera moves: a static scene
  at 30 fps is indistinguishable, a mid-corner 30 fps is judder. Operator's verdict on this run:
  "looked pretty good".

---

## 10. Phase 7 rollout: everything the rename broke (read before renaming again)

Renaming the binary and the Android package cost far more debugging time than the HEVC work did.
All three failures presented as "the headset cannot reach the PC", and none of them was the PC.

### 10.1 Windows Firewall allows an exe by *path*, so a rename blocks inbound traffic
The allow rules created earlier were `C:\...\build\gta60frames.exe` (inbound, TCP + UDP, Public).
After the rename, `GTA-Sixty.exe` matched no rule, so Windows silently dropped **inbound TCP 7660
and UDP 7661**. Symptoms, in order of how they mislead you:

- `Get-NetTCPConnection -LocalPort 7660` shows the socket **listening** - the PC looks fine.
- The LAN discovery responder answers a loopback probe correctly (`GTA60_OFFER|...`), so discovery
  "works" when tested on the PC.
- From the headset, `ping` to the PC succeeds (ICMP is allowed) while a TCP connect **times out**,
  which reads like a network/AP-isolation problem.

Fix (needs an elevated shell once):

```powershell
New-NetFirewallRule -DisplayName 'GTA-Sixty' -Direction Inbound -Profile Any `
  -Program 'C:\...\build\GTA-Sixty.exe' -Action Allow
```

Diagnostic that settles it in one line - **ping proves nothing, a TCP connect does**:
`adb shell 'echo x | toybox nc -w 3 <pc-ip> 7660; echo rc=$?'` -> `rc=0` means the firewall is fine.

### 10.2 Changing the Android package id silently wipes the app's saved settings
`com.gta60frames.quest` -> `com.gtasixty.quest` is a *different app* to Android, so it gets a fresh
SharedPreferences store: the saved PC address and the cached Wake-on-LAN MAC are gone. This looks
exactly like "the app lost the PC IP" and has nothing to do with anything scrubbed for publication.
The old package also stays installed - uninstall it by hand.

### 10.3 First-run discovery never worked (fixed in Phase 7)
`discoverLanHost()` only probed `255.255.255.255` until a host had been saved at least once, and
Android/Wi-Fi drivers drop that packet often (the code's own comment admitted it). Combined with
10.2 there is no saved host, so **a fresh install could never discover the PC at all**. It now
derives the directed broadcast of every live interface (`InterfaceAddress.networkPrefixLength`) and
probes those as well - that is the address that actually arrives. Note this LAN is a **/22**, not the
/24 you would guess from a `192.168.x.y` address, so **compute the broadcast from the interface's own
prefix length** rather than assuming `/24` or borrowing the prefix from a remembered host.

### 10.4 Signing in can succeed while the token is thrown away
`runLogin()` used to print "Encrypted token saved to ..." on the strength of the device-code poll
alone, and `Account::store()` discarded `saveTokens()`'s result, so a failed write was invisible and
the next launch simply asked for a sign-in again. Both now verify, and `saveTokens()` names the
failing syscall (`fprintf` with the path and `errno`). Under an automation harness that confines
unsigned binaries to the working directory, this is exactly how the sign-in was lost three times;
`GTA60_XBOX_DATA` pointed at a writable directory is the workaround.

# Capture Mode Live A/B Benchmark & Visual Quality Report — 2026-11-24

Live A/B telemetry and synchronized source-vs-Quest video comparison of the **MintBook (`linux-capture`) -> Quest 3 (`quest-spatial-app`)** Capture LAN pipeline (`2560x1440@60` Elgato 4K S -> VA-API -> GT60 TCP `7660` -> Adreno 740 GLES 3.1 `FG120+SR` -> Meta Spatial SDK `0.15.0`).

---

## 1. Infrastructure & Telemetry Fixes Landed Before Test

1. **Fixed `GTA60-Perf` `Gen 127%` bug (`quest-spatial-app/src/main/java/com/gtasixty/spatial/MainActivity.java`)**:
   - `drainDecoderLoop` previously incremented `statRendered++` per 60 fps decoded packet even when `CaptureGlRenderer` was active, while `CaptureGlRenderer`'s `FrameListener` only incremented `statGenFrames++`, and `scheduleTelemetryTick` divided `statGenFrames / statFrames`.
   - Moved `statRendered++` into `CaptureGlRenderer`'s `FrameListener` (`eglSwapBuffers` count) and changed `genPct` to `statGenFrames / statRendered`.
2. **Added live A/B broadcast controls (`MainActivity.java`)**:
   - `com.gtasixty.spatial.SET_DISPLAY` now handles `bitrate` (`10..250`, added `100` to `BITRATE_OPTIONS`), `codec` (`hevc`, `hevc10`, `av1`, `h264`), `enhance` (`0..8`), `startStream`, and `recordMp4` / `recordSec`.
3. **Added non-disruptive live bitstream & Quest GL recorders**:
   - `linux-capture`: `--dump-bitstream` / `--dump-seconds` and UDP `7661` command `GTA60_DUMP|<sec>|<path>` (`Gt60Server.cpp`, `main.cpp`) writing raw Annex-B HEVC/H264 or IVF AV1 + `.csv` frame metadata (`frameIdx,timestampMs,isIdr,isDup,bytes`).
   - `linux-capture/CMakeLists.txt` & `scripts/linux/update-and-start.sh`: enabled `CMAKE_BUILD_TYPE=Release` (`-O3`).
   - `CaptureGlRenderer.java`: opt-in secondary `EGLSurface` `MediaCodec` (`video/hevc`) + `MediaMuxer` recorder + `.csv` (`frameIdx,ptsUs,generated,surfW,surfH,recW,recH,mode,taa,computeFlow`) capturing unwarped post-shader Quest output with zero overhead when inactive.

---

## 2. Live A/B Telemetry Matrix (`C1`–`C8`, `FG120+SR`, Active Driving)

Zero `0x502` GL errors, zero `FRAME_STALL`, zero `endFrameJob` stalls, and zero MintBook socket drops (`drop=0`) across all 8 configs. During active driving (`C2`–`C8`), the Xbox/game output ran in an uncapped/variable **~48 unique fps** (`4:1` cadence: `in=59.9`, `uniq=47.0–48.1`, `dup=19.7–21.4%`, `runs=23..25/0/0`).

| Config | Panel Scale | TAA | Flow | Target Codec / Ceiling | Quest `renFps` Mean (Range) | Quest `recvFps` | `Gen %` | Quest `Mbps` | `Feed ms` | MintBook `in` / `uniq` / `dup%` | MintBook `mad` |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| `C1` | `1800p SR` (`1`) | On | Compute | `HEVC` `50 Mbps` (`QVBR q=20`) | **80.4** (60.0–97.6) | 60.0 | 76% | 19.9 | 0.69 | 60.0 / 29.9 / 50.1% | 4.96 |
| `C2` | `1440p` (`0`) | On | Compute | `HEVC` `50 Mbps` (`QVBR q=20`) | **92.5** (91.8–94.0) | 59.9 | 83% | 26.0 | 0.66 | 59.7 / 47.0 / 21.4% | 4.35 |
| `C3` | `4K Ultra` (`2`) | On | Compute | `HEVC` `50 Mbps` (`QVBR q=20`) | **59.9** (47.9–71.9) | 59.9 | **100%** | 38.0 | 0.72 | 59.9 / 48.0 / 19.8% | 4.92 |
| `C4` | `1800p SR` (`1`) | **Off** | Compute | `HEVC` `50 Mbps` (`QVBR q=20`) | **89.7** (60.0–107.0) | 59.3 | 62% | 33.3 | 0.67 | 59.9 / 48.0 / 19.8% | 5.62 |
| `C5` | `1800p SR` (`1`) | On | **Frag** | `HEVC` `50 Mbps` (`QVBR q=20`) | **65.4** (45.9–86.0) | 59.9 | 69% | 33.0 | 0.77 | 59.9 / 47.9 / 20.0% | 6.32 |
| `C6` | `1800p SR` (`1`) | On | Compute | `HEVC` **`100 Mbps`** (`QVBR q=20`) | **81.3** (52.0–96.0) | 60.0 | 65% | 28.4 | 0.64 | 59.6 / 47.5 / 20.3% | 5.15 |
| `C7` | `1800p SR` (`1`) | On | Compute | `HEVC 10-bit` `100 Mbps` (`QVBR`) | **78.5** (52.0–93.8) | 58.6 | 63% | 26.1 | 0.83 | **55.1** (52.6–58.5) / 45.9 | 5.94 |
| `C8` | `1800p SR` (`1`) | On | Compute | `AV1` `100 Mbps` (`VBR`) | **79.2** (56.0–91.8) | 59.9 | 63% | **93.0** | 0.73 | 59.9 / 48.1 / 19.7% | 6.60 |

### Key Telemetry Takeaways
- **`4K Ultra` (`C3`) saturates Adreno 740** under `FG120+SR`: `renFps` collapsed to `47.9–71.9 fps` and `Gen` hit `100%` (real frames pre-empted before sub-frame timer fired).
- **GLES 3.1 Compute Flow (`C1`/`C6`) vs. Fragment Fallback (`C5`)**: Compute flow ran `+15–16 fps` faster (`80.4–81.3 fps` vs `65.4 fps` mean).
- **TAA On (`C1`) vs. Off (`C4`)**: `TAA Off` reached `107.0 fps` peak (`89.7 fps` mean) vs `97.6 fps` peak (`80.4 fps` mean) with `TAA On`.
- **`HEVC 10-bit` (`C7`) throttles MintBook**: Even built with `-O3`, `promoteNv12ToP010` + 2x surface upload (`18.91 ms/frame` in `--selftest-all`) dragged MintBook capture input down to `52.6–58.5 fps` (`55.1 fps` avg).
- **`HEVC QVBR q=20` ceiling vs. `AV1 VBR`**: Raising Quest bitrate `50 -> 100 Mbps` (`C6`) only used `28.4 Mbps` because `QVBR q=20` hits its quality target around `20–38 Mbps`. `AV1` (`VBR`, since `av1_vaapi` rejected `QVBR`) actually utilized `93.0 Mbps` cleanly on `c2.qti.av1.decoder` at `59.9 recvFps`.

---

## 3. Synchronized Source vs. Quest Video Comparison (`100 Mbps HEVC`, `FG120+SR`)

Three 10-second pairs were captured simultaneously during driving (`src_v{1,2,3}.hevc` on MintBook = `601` frames each @ `2560x1440`, paired with unwarped `CaptureGlRenderer` output `quest_v{1,2,3}.mp4` on Quest 3).

| Pair | Quest Config | Quest Recorded Frames (Real / Gen) | Real vs Src `@1440p` PSNR / SSIM | Real vs Bicubic Src `@3200x1800` PSNR / SSIM | Gen FG vs Nearest Src PSNR / SSIM | Sharpness (Laplacian Var) `Src -> Real / Gen` | Temporal `stepMAD` / `osc2` `Src -> Quest` |
|---|---|---|---:|---:|---:|---:|---:|
| `V1` | `1800p SR`, `TAA On` | `434` (`110` / `319`) | **37.13 dB / 0.9627** | 36.17 dB / 0.9531 | 29.33 dB / 0.8825 | `42.5 -> 70.4 / 30.4` | `4.04 / 3.67 -> 4.23 / 2.86` |
| `V2` | `1800p SR`, `TAA Off` | `458` (`116` / `340`) | **38.71 dB / 0.9761** | 37.53 dB / 0.9690 | 31.85 dB / 0.9365 | `29.4 -> 72.0 / 29.4` | `3.50 / 3.20 -> 3.54 / 2.39` |
| `V3` | `1440p`, `TAA On` | `919` (`386` / `531`) | **39.15 dB / 0.9683** | — | **33.25 dB / 0.9330** | `31.9 -> 48.7 / 36.1` | `3.89 / 3.55 -> 2.40 / 1.68` |

---

## 4. Root-Cause Diagnosis of Human Observations (Next-Agent Handoff)

### Issue A: Screen "Breathing" / Pulsing at `FG120+SR` (Improved at Lower Modes)
Confirmed by both quantitative metrics and side-by-side zoomed crops (`compare_V1_1800pSR_TAAon.png`, `compare_V2_1800pSR_TAAoff.png`, `compare_V3_1440p_TAAon.png`):
1. **Sharpness oscillation (>2x swing between Real and Generated frames)**:
   - On `1800p SR`, Real frames (`SGSR1 + RCAS`) have Laplacian variance **`70.4–72.0`**, while Generated FG frames drop to **`29.4–30.4`** because `CaptureProtocolHelper.computeRcasSharpnessStops` adds `+1.0` stop (halving RCAS sharpening) on generated frames AND `FRAG_WARP` blurs/blends frames. At `1440p` (`V3`), the swing shrinks (`48.7` vs `36.1`).
2. **Double-exposure ghosting & block seams in `FRAG_WARP` (`CaptureGlRenderer.java`)**:
   - In `FRAG_WARP`:
     ```glsl
     vec3 fallback = (uAlpha > 0.99) ? c1 : ((uAlpha < 0.35) ? c0 : mix(c0, c1, uAlpha * 0.5));
     fragColor = vec4(mix(fallback, bestWarped, w), 1.0);
     ```
   - During fast pans (`mot = 6.86–10.43`), wherever occlusion/SAD confidence `w` drops, `mix(c0, c1, ...)` blends two unaligned frames together (visible double-exposed faces/signs in `V1` and `V2` crops) and creates block seams around high-confidence islands.
3. **Sub-frame scheduler & `60 Hz` VirtualDisplay bugs causing Real frames to be skipped (`Gen 62–100%`)**:
   - `FramePacer.snapNextSubFrameNs(nowNs, 1)` calls `snapToNextVsyncNs(nowNs + 8.33ms, anchor, 8.33ms, 1.5ms)`, adding `+1.5 ms` lead margin on top of `nowNs + 8.33 ms`, which rounds `k` up to **2 vsyncs (~14–16 ms)** instead of 1 vsync (`8.33 ms`).
   - `dumpsys display` showed the Spatial panel's `VirtualDevice_4` (`displayId 22`, `3200x1800`) has **`renderFrameRate 60.0`** and **`refreshRateOverride 60.0`**, which caps `MainActivity`'s `Choreographer` callback in `CaptureGlRenderer` at 60 Hz even when OpenXR runs at 120 Hz.
   - In `CaptureCadenceController.java`, `WARP_60_TO_120` emits the **generated** frame (`alpha=0.5`) first and defers the **real** frame to the sub-frame timer; whenever the next decoded frame arrives before the delayed sub-frame fires, the real frame is dropped (`110` real vs `319` gen in `V1`, `100%` gen in `4K` `C3`).

**Recommended fixes for Next Agent (Breathing / FG120)**:
- **Always present the Real frame immediately** when a unique decoded frame arrives (`alpha = 0.0` or `1.0` real first, then schedule `alpha = 0.5` generated sub-frame `+8.33 ms` later) so real frames are NEVER skipped under load.
- Fix `FramePacer.snapNextSubFrameNs` so `leadMarginNs` is subtracted when waking the GL thread without pushing `snapToNextVsyncNs` into the `k+1` vsync bucket.
- Replace `FRAG_WARP`'s unaligned `mix(c0, c1, ...)` fallback with **global-motion-compensated fallback** (`uGlobalMotion` camera pan warp) or nearest-frame hold (`c1`) when confidence is low, and reduce/remove the `+1.0` RCAS stop penalty on well-matched warped pixels so sharpness doesn't pulse between real and generated frames.
- Investigate why `VirtualDevice_4` reported `60.0 Hz` (`SpatialMainActivity.kt` `ActivityPanelRegistration` vs Meta Spatial SDK `0.15.0` virtual display rate) or decouple `FramePacer` from a 60 Hz panel `Choreographer`.

### Issue B: Missing Bottom of Screen + Grey/Black Band at Top of Screen
- **Verified NOT in MintBook capture or `CaptureGlRenderer`**: Both `src_v*.hevc` (`2560x1440`) and `quest_v*.mp4` (`3200x1800` / `2560x1440` GL output) show the complete `16:9` frame (full sky at top, full minimap and call/HUD bar at bottom).
- **Observed in Quest `dumpsys`**: `MainActivity` `DecorView` / `SurfaceView` are `0,0-3200,1800`, `SurfaceView(BLAST)#738` has `geomBufferSize=[0 0 3200 1800]`, `geomContentCrop=[0 0 3200 1800]`, and `VirtualDevice_4` is `3200x1800`.
- **Root cause hypothesis to investigate in `SpatialMainActivity.kt` / `MainActivity.java`**:
  1. `SpatialMainActivity.kt` calls `pso.reshape(newCfg)` at runtime when changing `panelScale` (`1440p` <-> `1800p SR` <-> `4K`), and `MainActivity.java` calls `surfaceView.getHolder().setFixedSize(outW, outH)` + `adjustSurfaceAspect(width, height)` (`SurfaceView` inside `FrameLayout` with `Gravity.CENTER`). When the Spatial SDK `PanelSceneObject` / `VirtualDevice` backing texture size or `CylinderShapeOptions` (`curve=73%`) diverges from `SurfaceView` / `AndroidSurfaceSwapchain` bounds or top-bar `hudBar` layout pass, an OpenGL Y-flipped offset clips the bottom and leaves an uninitialized/grey-black band at the top.
  2. **First test for next agent**: Check `SpatialMainActivity.kt` (`buildPanelConfigOptions` + `reshapePanelNow` + `CylinderShapeOptions` at `curve=73%` vs `curve=0%`) and capture a `screenrecord` crop of the eye buffer (`display 0`) vs `quest_v*.mp4` while toggling `curve=0%` vs `73%` and `panelScale=0` vs `1` (cold-started vs reshaped).

### Issue C: Recommended Default Preset (Right Now)
Until Issue A (`FRAG_WARP` + `FramePacer` + real-frame-first cadence) and Issue B (reshape/top-band) are fixed:
- **Best immediate smoothness/clarity**: `1440p` (`panelScale=0`, or cold-start `1800p SR` if top-band is absent at cold start) + **`FG60+SR`** (`enhance=4`) or **`SuperRes`** (`enhance=2`) + **`TAA On`** + **`Compute Flow On`** + **`HEVC 8-bit`** (`--quality 16` or `--rc-mode vbr --bitrate 80..100` on MintBook so QVBR `q=20` doesn't cap at ~25 Mbps).
- Avoid `HEVC 10-bit` on MintBook until `NV12 -> P010` conversion is moved to VA-API VPP (`hwupload` + `scale_vaapi=format=p010`) so it stops throttling capture below 60 fps (`52.6–58.5 fps`).

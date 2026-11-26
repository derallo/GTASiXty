# GTA-Sixty (`GTASiXty`)

Play your **Xbox Series X|S** on a **Meta Quest 3** at up to **1440p60 / 120 fps** in Mixed Reality or inside a full 3D VR recreation of **Jimmy De Santa's bedroom from GTA V**, with real-time on-headset **Adreno 740 upscaling** and **optical-flow frame generation** (30 fps → 60 / 90 / 120 fps).

[![Download Latest APK](https://img.shields.io/badge/Download-Quest%203%20APK%20(v2.1)-00b894?style=for-the-badge)](https://github.com/derallo/GTASiXty/releases/latest)

---

## Highlights

- **Jimmy's Room 3D VR Environment** — Step inside Jimmy De Santa's bedroom (`V_Michael_1_Son` from GTA V Enhanced) at locked **120 Hz** on Quest 3, with your live Xbox display mounted cleanly inside Jimmy's TV bezel.
- **On-Quest Adreno 740 Upscaling & Optical-Flow Frame Generation** — 3-phase OpenGL ES 3.0/3.1 post-processing pipeline featuring **Qualcomm SGSR1** 12-tap `fastLanczos2` super-resolution, dither-aware **RCAS**, YCoCg variance-clamped temporal foliage accumulation (**SGSR2-lite TAA**), and **GLES 3.1 shared-memory compute optical flow** (`640×360`) for **FG60 / FG90 / FG120** frame generation.
- **Flexible Low-Latency Streaming Modes**:
  1. **Capture Mode (LAN or Direct USB-C UVC)** — HDMI capture card via dedicated Linux VA-API box (`linux-capture/`), Windows NVENC PC (`--capture`), or plugged straight into the Quest 3 USB-C port. Leaves your Xbox controller paired natively to the console.
  2. **Direct Mode (Quest ↔ Xbox WebRTC)** — No PC required; the headset signs in to your Xbox account and streams directly over LAN WebRTC ([DIRECT_MODE.md](DIRECT_MODE.md)).
  3. **Windows PC Relay Mode** — Xbox Remote Play → Windows PC (D3D12 dedup + NVOFA optical-flow FG + EASU/RCAS + NVENC H.264/HEVC) → Quest 3 over TCP (`7660`).
- **Spatial MR Theater Controls** — Locked `16:9` `2560×1440` zero-copy OpenXR layer (`mips = 1`, `LayerFilters.HIGHEST_QUALITY`) with **Curve** (`0–100%`), **Tilt** (`-45°` to `+45°`), **Height**, **Screen Size**, **Room Depth**, **Backdrop** (`Passthrough → Dim → Dark → Jimmy's Room`), **Recenter**, bottom window drag handle (**Trigger** or **Grip/Pinch** + thumbstick depth), and built-in **Net Check** diagnostics.

---

## Jimmy's Room 3D VR Environment

Cycle **Display → Backdrop** (`Passthrough → Dim → Dark → Jimmy's Room`) to leave passthrough behind and play your Xbox on Jimmy's bedroom TV inside a 1:1 3D VR room built from GTA V Enhanced (`v_int_44` / `V_Michael_1_Son`).

- **Genuine Geometry & Textures**: Assembled from 47/48 unique room shell, furniture, and prop meshes (`prop_tv_flat_01`, `v_res_msoncabinet`, `prop_console_01`, `prop_speaker_06`, guitar/amp stack, bed, desk, PC setup, posters, clutter) and 147/147 decoded BC1–BC7 textures packaged into `quest-spatial-app/src/main/assets/scenes/jimmys_room.glb`.
- **120 Hz Quest 3 Performance**: Uses `KHR_materials_unlit` with pre-baked vertex lighting (combining Rockstar's baked artificial/natural/AO vertex channels, overhead spotlights, golden sunlight shaft through the curtains, warm bedside lamp glow, desk lamp spotlight, and subtle TV/monitor bounce) plus normal-map micro-relief baked into diffuse maps — leaving full GPU headroom for 1440p upscaling and optical-flow frame generation.
- **TV Bezel Lock & Comfort Controls**:
  - The live `2560×1440` OpenXR panel locks flat at exact `16:9` (`2.100 m × 1.181 m`) inside Jimmy's TV bay (`1.5 cm` proud of the bezel to prevent Z-fighting).
  - **Screen Size**, **Curve**, and **Tilt** are automatically locked while in the room so the screen never detaches from the TV, and **Room Depth** switches to **Seat Distance to TV** (`1.32 m – 3.57 m`), moving the room and TV together as a rigid unit alongside **Height** and **Recenter**.
  - Switching back to `Passthrough`, `Dim`, or `Dark` restores your saved flat/curved theater size, curve, and tilt.

See [JIMMYS_ROOM_HANDOFF.md](JIMMYS_ROOM_HANDOFF.md) for the coordinate mapping, extraction/build scripts (`scripts/extract_gtav_enhanced_jimmys_room.py`, `scripts/build_real_jimmys_room_glb.py`), and spatial invariants.

---

## Upscaling & Optical-Flow Frame Generation

GTA-Sixty runs a dedicated real-time GPU enhancement pipeline designed for **30 fps console games** (like GTA V / GTA VI) as well as native **60 fps** streams.

### On-Quest Adreno 740 Pipeline (`CaptureGlRenderer.java`)

Built in OpenGL ES 3.0 / 3.1 directly on the Quest 3's Snapdragon XR2 Gen 2 (Adreno 740) with `SUSTAINED_HIGH` GPU boost and **1:1 `2560×1440`** output into the OpenXR `AndroidSurfaceSwapchain` (`mips = 1` + `LayerFilters.HIGHEST_QUALITY` compositor super-sampling):

1. **Super-Resolution & Sharpening (`SR` / `RCAS`)**:
   - **Qualcomm SGSR1** 12-tap `fastLanczos2` edge-directed spatial upscaling to `2560×1440`.
   - **Dither-aware RCAS** (Robust Contrast Adaptive Sharpening) tuned to sharpen detail without amplifying compression noise or sky banding, plus **sky debanding**.
   - **YCoCg Variance-Clamped Temporal Accumulation (SGSR2-lite TAA)** to stabilize fine foliage and sub-pixel shimmer.
2. **Optical-Flow Frame Generation (`FG60`, `FG90`, `FG120` + optional `+SR`)**:
   - **GLES 3.1 Shared-Memory Compute Optical Flow** (`COMP_FLOW_COARSE` `160×90` → `COMP_FLOW_FINE` `640×360`, with automatic fragment-shader fallback) writing `GL_RGBA16F` motion vectors (`MAX_UV = 0.20`).
   - **Foliage-Safe 4× Bilinear Luma Box Downsample**, **Bidirectional Occlusion Check**, **4-Candidate Trajectory Warp**, and smooth confidence fallback to prevent HUD/foliage tearing.
   - **Async PBO Affine `GlobalMotionEstimator`** to lock fast camera pans and stabilize motion estimation.
   - **Choreographer VSync-Locked Sub-Frame Pacing** (`eglPresentationTimeANDROID`) + **ADPF `PerformanceHintManager`** CPU boost + fixed cadence locking (`CaptureCadenceController`) to convert 30 fps console cadence smoothly into **60 fps (2×)**, **90 fps (3×)**, or **120 fps (4×)**.

| On-Quest Mode | What It Does |
|---|---|
| `Off` | Zero-overhead passthrough |
| `RCAS` | Dither-aware contrast-adaptive sharpening |
| `SR` | Qualcomm SGSR1 12-tap upscaling + RCAS + temporal foliage stabilization |
| `FG60` / `FG60+SR` | 2× optical-flow frame generation (30 → 60 fps), optionally paired with SR |
| `FG90` / `FG90+SR` | 3× optical-flow frame generation (30 → 90 fps), optionally paired with SR |
| `FG120` / `FG120+SR` | 4× optical-flow frame generation (30 → 120 fps), optionally paired with SR |

### Windows PC D3D12 Pipeline (`src/`)

When using the Windows PC streamer (`GTA-Sixty.exe`), an optional NVIDIA D3D12 GPU pass performs duplicate-frame detection, **NVOFA** optical-flow interpolation, **EASU / RCAS** upscaling & sharpening (`--upscale 1080p|1440p|4k`, `--sharpness`), and single-pass ultra-low-latency **NVENC** (`H.264` or `HEVC`) before streaming over TCP `7660`.

---

## Streaming Architecture

```text
1) Capture Mode (LAN — Linux VA-API or Windows NVENC)
   Xbox Series X --HDMI--> Capture Card --> Linux/PC Encoder --GT60 TCP 7660 (HEVC/H.264)--> Quest 3 (Adreno 740 SR/FG)

2) Capture Mode (Direct USB-C UVC)
   Xbox Series X --HDMI--> USB-C UVC Capture Card --> Quest 3 (Direct UVC -> Adreno 740 SR/FG)

3) Direct Mode (No PC)
   Xbox Series X --WebRTC (ICE/SRTP over LAN)--> Quest 3 APK

4) Windows PC Relay Mode
   Xbox Series X --WebRTC (LAN)--> Windows PC (D3D12 dedup/NVOFA/EASU/NVENC) --GT60 TCP 7660--> Quest 3
```

---

## Quick Start (Quest 3 APK)

1. Download **`GTA-Sixty-Quest3.apk`** from the [GitHub Releases](https://github.com/derallo/GTASiXty/releases/latest) page.
2. Sideload via ADB (USB or wireless `adb connect <quest-ip>:5555`):
   ```powershell
   adb install -r GTA-Sixty-Quest3.apk
   ```
   Or from this repo:
   ```powershell
   scripts\install-quest-apk.ps1 -Launch
   ```
3. Launch **GTA-Sixty** from Unknown Sources on Quest 3:
   - Tap **Mode** to pick **Capture**, **Direct**, or **PC**.
   - Tap **Display** to adjust **Curve**, **Tilt**, **Height**, **Screen Size**, **Room Depth**, or switch **Backdrop** to **Jimmy's Room**.
   - Grab the bottom handle bar with **Trigger** or **Grip/Pinch** to move the screen (push thumbstick up/down while holding Trigger to adjust depth).
   - Use **Net Check** anytime to verify LAN, TCP `7660`, USB UVC, and Xbox connectivity.

---

## Building From Source

### Requirements
- **Quest 3 APK (`quest-spatial-app/`)**: Android SDK (API 35) + JDK 17.
- **Linux Capture Server (`linux-capture/`)**: CMake, C++20 compiler, FFmpeg (`libavcodec`/`libavutil`), ALSA (`libasound2-dev`), Intel/AMD VA-API (`libva`).
- **Windows PC Streamer (`src/`)**: Windows 10/11 x64, NVIDIA GPU (NVENC/NVOFA), Visual Studio 2022 (C++), CMake, Ninja, Windows SDK `dxc.exe`.

### Build Commands

```powershell
# Quest 3 APK -> build\GTA-Sixty-Quest3.apk
scripts\build-quest-apk.ps1

# Windows PC streamer -> build\GTA-Sixty.exe
scripts\build.ps1

# Windows unit / protocol tests
build\GTA-Sixty_tests.exe
```

```bash
# Linux capture-card server (on Linux capture PC)
cmake -B linux-capture/build -S linux-capture
cmake --build linux-capture/build -j
```

---

## Running the Streamers

### Windows PC Streamer
```powershell
# One-time Microsoft account sign-in (device-code flow)
build\GTA-Sixty.exe --login

# List consoles on LAN
build\GTA-Sixty.exe --list-consoles

# Stream Xbox Remote Play to Quest 3
build\GTA-Sixty.exe --connect auto --quest-server --codec hevc --bitrate 80

# Or stream an HDMI capture card connected to this PC
build\GTA-Sixty.exe --capture --codec hevc --bitrate 80
```

| Flag | Meaning |
|---|---|
| `--connect <id\|auto>` | Xbox console to stream from |
| `--quest-server` | Encode and serve the Quest 3 APK on TCP `7660` / UDP `7661` |
| `--capture` | Capture-card mode (HDMI capture → NVENC → Quest, no Xbox sign-in) |
| `--capture-device <text>` | Substring match for capture device friendly name |
| `--codec <h264\|hevc>` | Video codec for PC → Quest (`hevc` carries ~2× detail per Mbps) |
| `--bitrate <Mbps>` | NVENC CBR target bitrate |
| `--max-bitrate <Mbps>` | Cap what the headset can request (`0` = no cap) |
| `--max-output-height <px>` | Cap encode height (default `1440`) |
| `--upscale <1080p\|1440p\|4k>` | Initial PC output resolution (overridable live from Quest) |
| `--sharpness <0..2>` | PC RCAS sharpening amount (default `0.25`) |
| `--fps <60\|90>` | Output frame rate |
| `--no-fg` | Disable PC frame generation (lowest latency) |
| `--agent` | Listen on UDP `7662` so the Quest can launch the streamer remotely |

### Remote Wake & Auto-Start (Windows PC)
Install the lightweight background agent (`UDP 7662`) so pressing **Start Stream** or **Wake PC** on the Quest automatically wakes the PC (Wake-on-LAN) and launches the streamer:
```powershell
scripts\install-autostart.ps1 -StartNow
scripts\install-autostart.ps1 -Remove
```

---

## Documentation & Deep Dives

- [SPATIAL_APP_PLAN.md](SPATIAL_APP_PLAN.md) — Meta Spatial SDK `0.15.0` architecture, zero-copy `mips = 1` swapchain invariants, and cylinder math.
- [JIMMYS_ROOM_HANDOFF.md](JIMMYS_ROOM_HANDOFF.md) — Jimmy's Room 3D VR scene asset pipeline, lighting model, and TV-lock invariants.
- [CAPTURE_MODE.md](CAPTURE_MODE.md) — HDMI capture-card setup (Linux VA-API & Windows NVENC) and GT60 protocol details.
- [DIRECT_MODE.md](DIRECT_MODE.md) — PC-less Quest ↔ Xbox WebRTC direct mode.
- [LATENCY_OPTIMIZATION_NOTES.md](LATENCY_OPTIMIZATION_NOTES.md) — End-to-end latency measurements, decoder buffering traps (`MF_LOW_LATENCY`), and benchmarks.
- [GTA_HANDS_HANDOFF.md](GTA_HANDS_HANDOFF.md) — Experimental OpenXR hand-tracked GTA V hands + virtual Xbox controller rig.

---

## Contributing

**Please merge your improvements into this project rather than creating a separate repo or standalone app.** See [agents/README.md](agents/README.md) and [AGENTS.md](AGENTS.md) for architecture notes, invariants, and build/test workflows before making changes.

---

## Troubleshooting

| Symptom | Likely Cause & Fix |
|---|---|
| Headset times out connecting or LAN scan finds nothing | **Windows Firewall** rules bind to exe path. Allow `GTA-Sixty.exe` inbound on TCP `7660` / UDP `7661`–`7662` (or run **Net Check** on the headset). |
| Decoder errors or garbled video after updating | Keep PC/Linux streamer and Quest APK in sync (both support the `VIDEO_CONFIG` codec byte). |
| Huge latency on Windows PC mode | Check `decLag` in telemetry — `MF_LOW_LATENCY` must remain enabled on the H.264 decoder (see [LATENCY_OPTIMIZATION_NOTES.md](LATENCY_OPTIMIZATION_NOTES.md)). |
| Direct mode pad works in Xbox Guide but not in a Quick-Resumed game | Press **Fix pad** in the Direct bar to re-announce virtual pad 0 without closing the game. |

---

## Scope & Licence

This is an interoperability project: it speaks Microsoft's Xbox remote-play protocol using **your own** account and **your own** console on **your own** LAN. It ships no Microsoft code and no credentials.

No licence file is included yet — treat the code as "all rights reserved" until one is added. Third-party components keep their own licences (see `third_party/`): OpenXR loader (Apache-2.0), mbedTLS (Apache-2.0), libdatachannel (MPL-2.0), Opus (BSD-3-Clause), nlohmann/json (MIT), ffnvcodec headers (MIT).

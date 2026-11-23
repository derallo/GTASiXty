# GTA60Frames / GTASiXty — Project Knowledge for Antigravity

## Overview
Streams Xbox gameplay to Meta Quest 3 at up to 1440p60/120fps over LAN using the custom **GT60** protocol:
- **TCP 7660**: 16-byte little-endian `Gt60Header` (`magic = 0x30365447` `"GT60"`) carrying `VideoConfig` (`0x01`), `VideoAccessUnit` (`0x02`; `flags`: `0x01=IDR`, `0x02=Generated`, `0x04=Duplicate` [experimental]), `AudioPcm` (`0x03`, 48 kHz stereo S16LE), and client backchannel (`0x81` IDR, `0x82` Gamepad, `0x83` Config, `0x84` Rendered echo).
- **UDP 7661**: LAN discovery (`GTA60_DISCOVER` -> `GTA60_OFFER|<port>|<w>|<h>|<fps>|<H264|HEVC>|<MAC>`) and `GTA60_STOP`.
- **UDP 7662**: Remote control agent (`GTA60_START` on Windows PC mode; `GTA60_UPDATE` on Ubuntu Capture mode).

## Components
1. **Windows PC Streamer (`src/`, `include/`, `CMakeLists.txt`)**
   - Xbox remote-play WebRTC receiver + D3D12 optical-flow frame-gen + NVENC + OpenXR/GT60 server.
   - Build: `scripts\build.ps1` -> `build\GTA-Sixty.exe`.
   - Unit tests: `tests\PipelineAndXboxTests.cpp`, `scripts\bench-selftest.ps1`.

2. **Quest 3 Client (`quest-app/`)**
   - Native Android/MediaCodec app with 3 modes cycling **PC -> Direct -> Capture**.
   - In **Capture** mode: connects to TCP 7660, decodes H.264/HEVC, plays 48 kHz PCM, never sends `GTA60_START` or gamepad frames, and can broadcast `GTA60_UPDATE` on UDP 7662 to trigger an update check on the Ubuntu capture box.
   - Build/install: `scripts\build-quest-apk.ps1`, `scripts\install-quest-apk.ps1`.

3. **Quest 3 Spatial Client (`quest-spatial-app/`)**
   - Side-by-side Meta Spatial SDK `0.15.0` Passthrough MR app (`com.gtasixty.spatial`, `GTA-Sixty Spatial`) hosting `MainActivity` inside a locked `16:9` `2560x1440 @ 256 dpi` (`mips = 1`) OpenXR layer with **Curve**, **Tilt**, **Height**, **Screen Size**, **Room Depth**, **Backdrop**, and **Recenter** controls (see [SPATIAL_APP_PLAN.md](SPATIAL_APP_PLAN.md)).
   - Build/install: `scripts\build-quest-spatial-apk.ps1`, `scripts\install-quest-spatial-apk.ps1`.

4. **Linux Capture-Card Passthrough (`linux-capture/`, `scripts/linux/`)**
   - Standalone CMake C++20 app for the dedicated Linux Mint / Ubuntu capture PC (no Windows/D3D12/WebRTC deps).
   - Grabs uncompressed UVC frames via V4L2 (2–3 buffer queue) + UAC audio via ALSA, encodes with VA-API (`hevc_vaapi` / `h264_vaapi`) via FFmpeg `libavcodec`, and serves GT60 on TCP 7660 / UDP 7661.

## Linux Capture Box (`capture-box`)
- **SSH alias**: `ssh capture-box` (`derek@192.168.68.90` wired Ethernet, configured in `~/.ssh/config`).
- **Hardware**: Intel Core Ultra 5 135U (`mintbook-g11`), Linux Mint 22.3, Elgato 4K S on USB 3.0 (`5000M`).
- **Encoder**: Intel `iHD` VA-API 1.20.0 (driver 24.1.0) on `/dev/dri/renderD128` (`H264` & `HEVC` `VAEntrypointEncSlice`).
- **Capture devices**: Video `/dev/v4l/by-id/usb-Elgato_Elgato_4K_S_AB2MB60110CB51-video-index0` (uncompressed `NV12` `2560x1440@60`), Audio `hw:CARD=S,DEV=0` (48 kHz stereo `S16_LE`).
- **Remote deploy & test**:
  ```powershell
  ssh capture-box "cd ~/GTASiXty && git pull --ff-only && cmake -B linux-capture/build -S linux-capture && cmake --build linux-capture/build -j && ./linux-capture/build/gta60-capture --selftest --device /dev/v4l/by-id/usb-Elgato_Elgato_4K_S_AB2MB60110CB51-video-index0 --width 2560 --height 1440 --fps 60"
  ```
- **Services on Linux (`mintbook-g11`)**:
  - `gta60-capture.service`: runs `scripts/linux/update-and-start.sh` (auto-pulls, rebuilds, runs `--selftest` with `.bak` rollback, then runs `gta60-capture`). Logs 2-second `[perf]` duplicate-frame telemetry (`in`, `uniq`, `dup%`, `runs=r1/r2/r3+`, `mad`, `drop`).
  - `gta60-updater.service`: listens on UDP `7662` for `GTA60_UPDATE` from Quest and restarts `gta60-capture.service` only if a new commit is available.
- **Baseline & GTA gap-fill handoff**: see [BENCHMARK_2026-11-22_CAPTURE.md](BENCHMARK_2026-11-22_CAPTURE.md) for the 1440p60 Halo Infinite baseline (`59.95 fps`, `1.0 ms` RTT) and experimental `kFlagDuplicate` (`0x04`) design notes.

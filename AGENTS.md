# GTA60Frames / GTASiXty — Project Knowledge for Antigravity

## Overview
Streams Xbox gameplay to Meta Quest 3 at up to 1440p60/120fps over LAN using the custom **GT60** protocol:
- **TCP 7660**: 16-byte little-endian `Gt60Header` (`magic = 0x30365447` `"GT60"`) carrying `VideoConfig` (`0x01`), `VideoAccessUnit` (`0x02`), `AudioPcm` (`0x03`, 48 kHz stereo S16LE), and client backchannel (`0x81` IDR, `0x82` Gamepad, `0x83` Config, `0x84` Rendered echo).
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

3. **Ubuntu Capture-Card Passthrough (`linux-capture/`, `scripts/linux/`)**
   - Standalone CMake C++20 app for the dedicated Ubuntu capture PC (no Windows/D3D12/WebRTC deps).
   - Grabs uncompressed UVC frames via V4L2 (2–3 buffer queue) + UAC audio via ALSA, encodes with VA-API (`hevc_vaapi` / `h264_vaapi`) via FFmpeg `libavcodec`, and serves GT60 on TCP 7660 / UDP 7661.

## Ubuntu Capture Box (`capture-box`)
- **SSH alias**: `ssh capture-box` (`littledemon@192.168.68.76`, configured in `~/.ssh/config`).
- **Hardware**: AMD Ryzen 5 PRO 7530U with Radeon Graphics (Barcelo), Ubuntu 24.04.3 LTS.
- **Encoder**: VA-API 1.20.0 on `/dev/dri/renderD128` (`H264` & `HEVC` `VAEntrypointEncSlice`).
- **Remote deploy & test**:
  ```powershell
  ssh capture-box "cd ~/GTASiXty && git pull --ff-only && cmake -B linux-capture/build -S linux-capture && cmake --build linux-capture/build -j && ./linux-capture/build/gta60-capture --selftest"
  ```
- **Services on Ubuntu**:
  - `gta60-capture.service`: runs `scripts/linux/update-and-start.sh` (auto-pulls, rebuilds, runs `--selftest` with `.bak` rollback, then runs `gta60-capture`).
  - `gta60-updater.service`: listens on UDP `7662` for `GTA60_UPDATE` from Quest and restarts `gta60-capture.service` only if a new commit is available.

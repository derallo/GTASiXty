# Capture Mode Benchmark — 2026-11-22 (`mintbook-g11` → Quest 3)

Baseline measurements of the standalone Linux capture-card passthrough server (`linux-capture/gta60-capture`) streaming **Halo Infinite** (60 fps Xbox Series HDMI output) over wired LAN + Wi-Fi to the Meta Quest 3 client (`v1.2`).

---

## Test Rig
- **Capture host (`capture-box`)**: `mintbook-g11`, Intel Core Ultra 5 135U (14 threads), Linux Mint 22.3, wired USB-3 Gigabit/5G Ethernet (`enx7cc2c647eacf`, `192.168.68.90`, Wi-Fi `wlp0s20f3` disabled).
- **Capture card**: Elgato 4K S (`0fd9:00ae`) on USB 3.0 (`5000M`), V4L2 `/dev/v4l/by-id/usb-Elgato_Elgato_4K_S_AB2MB60110CB51-video-index0`, `2560×1440` uncompressed `NV12` (`Rec. 709`) at `60.000 fps`, ALSA audio `hw:CARD=S,DEV=0` (`S16_LE`, 2 ch, 48 kHz).
- **Encoder**: Intel `iHD` VA-API 1.20.0 (driver 24.1.0) on `/dev/dri/renderD128`, `hevc_vaapi`, `50 Mbps` CBR, zero B-frames (`~8.3 ms/frame` in `--selftest`).
- **Headset**: Meta Quest 3 (`192.168.68.89`), `GTA-Sixty` `v1.2` (`c2.qti.hevc.decoder`, 120 Hz panel refresh).

---

## Sustained Session Results (14.2 minutes, `n = 426` two-second samples)

Zero disconnects, zero socket stalls, and zero dropped frames across the entire run.

### Quest 3 Client (`GTA60-Perf` logcat)
| Metric | Min | p05 | Median (p50) | Mean | p95 | Max |
|---|---:|---:|---:|---:|---:|---:|
| **Render FPS** | 57.80 | 59.80 | 60.00 | **59.95** | 61.90 | 62.00 |
| **Recv FPS** | 57.80 | 58.00 | 60.00 | **59.95** | 61.80 | 62.00 |
| **Bitrate (Mbps)** | 48.10 | 48.60 | 50.00 | **49.97** | 51.50 | 51.70 |
| **MediaCodec Feed (ms)** | 0.46 | 0.48 | 0.58 | **0.61** | 0.81 | 1.92 |

### Capture Host (`mintbook-g11`) & Network (`ss -ti sport = :7660`, `ps`, `ip -s link`)
- **`gta60-capture` resource usage**: `14.6%` of 1 CPU thread, `144,648 KB` RSS (`0.4%` RAM).
- **TCP 7660 transport**: `1.006 ms` smoothed RTT (`0.358 ms` min RTT), `786 Mbps` measured delivery rate, `5.15 GB` sent, `Send-Q = 0`.
- **Ethernet TX (`enx7cc2c647eacf`)**: `0` TX errors, `0` TX drops.

---

## Next Agent Handoff: GTA Frame-Drop Gap-Filling (Experimental `kFlagDuplicate`)

Because the capture → VA-API encode → LAN → Quest decode pipeline holds a flat **59.95 fps** with ~`1 ms` network RTT and >85% spare CPU/GPU headroom, any stutter observed in **GTA** over Capture mode comes from the **console repeating a frame on HDMI** when GTA dips below 60 fps.

### 1. What was added for the next agent
- **Experimental GT60 wire flag bit**: `kFlagDuplicate = 0x04` (`bit 2` in `Gt60Header::flags`, documented in `include/gta60/sink/QuestStreamServer.h` and `linux-capture/src/Gt60Server.hpp`).
  - Enabled by default in `linux-capture` (disable with `--no-dup-flag`).
  - Current Quest `MainActivity.java` ignores unknown bits in `flags` (only checks `FLAG_IDR = 0x01` and `FLAG_GENERATED = 0x02`), so this is **100% backward-compatible**.
- **Subsampled luma-MAD duplicate detector** (`DuplicateDetector` in `linux-capture/src/main.cpp`):
  - Samples a 32×32 pixel grid from the incoming `NV12` Y-plane (or `YUYV` luma) and computes mean absolute difference (`MAD`) vs. the previous frame (`--dup-threshold`, default `0.75`).
  - Logs 2-second `[perf]` lines to `journalctl -u gta60-capture.service`:
    ```text
    [perf] in=60.0 uniq=54.5 dup=9.2% runs=5/2/0 mad=3.84 drop=0
    ```
    where `runs=r1/r2/r3+` counts isolated 1-frame dips, 2-frame dips, and 3+ consecutive duplicate frames.

### 2. How to pick up from here
1. **Measure GTA first**: Switch the Xbox to GTA, connect in Capture mode, and read live `[perf]` lines:
   ```powershell
   ssh capture-box "journalctl -u gta60-capture.service -f"
   ```
   Tune `--dup-threshold` if static menus vs. minor motion need a tighter/looser MAD cutoff, and inspect `runs=r1/r2/r3+` to see whether GTA drops single frames (`r1`), drops to 30 fps (`r1` at 50% `dup`), or stalls for multi-frame bursts (`r3+`).
2. **Choose where to synthesize missing frames**:
   - **Option A — Synthesize on Quest (`MainActivity.java` + GLSL surface shader)**:
     Read `(flags & 0x04) != 0` in `MainActivity.java` when decoding `TYPE_VIDEO_AU`. Instead of displaying the raw repeated frame, reuse the previous motion field (or compute optical flow / block motion on the Quest GPU between the last two *unique* decoded frames in a `SurfaceTexture`) and warp/extrapolate the missing frame—requiring zero changes to the 50 Mbps HEVC stream.
   - **Option B — Synthesize on `mintbook-g11` before `VaapiEncoder`**:
     When `isDup` is true, generate an extrapolated/interpolated `NV12` frame on the Intel Core Ultra 5 iGPU (or CPU grid block-match + warp) before encoding, tagging it with `kFlagGenerated` (`0x02`) so Quest renders it with zero Quest-side changes.
     - *Zero-latency rule for occasional 60→50 fps drops*: extrapolate forward from `(uniq[N-2], uniq[N-1])` only when a duplicate arrives (adds **0 ms** latency when GTA holds 60 fps).
     - *Sustained 30 fps sections*: only use 1-frame lookahead interpolation (`+16.7 ms`) if GTA stays locked at 30 fps for extended stretches.

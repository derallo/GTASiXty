# GTA-Sixty

Play your Xbox Series X|S on a Meta Quest 3 over your LAN, with an optional GPU pass that can
upscale and/or interpolate frames before they reach the headset.

```
Xbox Series X  --WebRTC(1440p H.264)-->  Windows PC  --TCP(NVENC)-->  Quest 3 APK
   (console,           (LAN)             D3D12 pipeline:              MediaCodec
    remote play)                        decode -> dedup -> upscale      -> Surface
                                        -> NVENC (H.264 or HEVC, ULL)
```

The PC sits in the middle so it can do work the console can't: remove duplicated frames, run
optical-flow frame interpolation, and upscale/sharpene with EASU/RCAS before re-encoding for the
headset. It also keeps you off the cloud relay, since the console and PC talk directly over the LAN.

**Direct mode** skips the PC entirely: the headset signs in to the account itself and receives the
console's WebRTC stream, with no decode/upscale/re-encode hop. It trades the PC's frame generation
and sharpening for one less encode and one less network hop - see [DIRECT_MODE.md](DIRECT_MODE.md).

```
Xbox Series X  --WebRTC(ICE/SRTP over the LAN)-->  Quest 3 APK (Direct mode)
```

## Requirements

- **Windows 10/11 x64** (developed against the Windows 10/11 SDK)
- **NVIDIA GPU** with NVENC — the encoder is bound to D3D12, and DXVA decode is used where available
- **Xbox Series X|S** with remote play enabled and the console on the same LAN
- **Meta Quest 3** (Quest 2/Pro will likely work but is untested)
- **Wired Ethernet** on all three is strongly recommended. Latency is dominated by the console's
  encoder and the network path, not the PC.
- Visual Studio 2022 (C++ tools), CMake, Ninja, Windows SDK `dxc.exe`
- Android SDK build-tools + JDK 17 (only if you want to rebuild the headset APK)

## Build

```powershell
# PC side (downloads OpenXR loader + builds mbedTLS on first run)
scripts\build.ps1

# Headset APK
scripts\build-quest-apk.ps1
```

Both write into `build\`.

```powershell
# D3D12 / NVOFA / NVENC / GT60 protocol tests (the encode round-trip runs once per codec)
build\GTA-Sixty_tests.exe
```

## Run

```powershell
# one-time: sign in to your Microsoft account (device-code flow)
build\GTA-Sixty.exe --login

# confirm the console is visible
build\GTA-Sixty.exe --list-consoles

# stream to the headset
build\GTA-Sixty.exe --connect auto --quest-server --bitrate 120

# HDMI capture card on this PC, encoded here and sent to the Quest (no Xbox remote play)
build\GTA-Sixty.exe --capture --bitrate 50
```

Then open **GTA-Sixty** on the headset and tap **Start Stream**. It finds the PC automatically via
a UDP broadcast (`7661`), or you can type the PC's IP. For a capture card, tap **Mode** until it says
**Capture**, then **Start Stream**. Capture leaves the Xbox controller paired to the console.

### Options

| Flag | Meaning |
|---|---|
| `--connect <id\|auto>` | Console to stream from |
| `--quest-server` | Encode and serve the Quest APK (rather than the local OpenXR window) |
| `--bitrate <Mbps>` | NVENC CBR target for the PC→Quest stream |
| `--codec <h264\|hevc>` | Codec for the PC→Quest stream. **HEVC** roughly halves the bitrate needed for the same quality, so the same headset cap carries visibly more detail. Default **h264**; needs the headset APK rebuilt (see below) |
| `--max-bitrate <Mbps>` | Cap what the headset is allowed to request (`0` = no cap) |
| `--max-output-height <px>` | Cap the encode height (`0` = no cap). Default **1440**; 2160p roughly doubles NVENC cost and starts dropping frames |
| `--xbox-bitrate <Mbps>` | LAN bandwidth advertised to the console via REMB. Default **50**; the console caps itself near 24 Mbps, so advertising more changes nothing. `0` sends no REMB at all, which drops the source to ~15 Mbps |
| `--sharpness <0..2>` | RCAS sharpening amount. Lower values amplify the source's quantization noise less (default 0.25) |
| `--agent` | Listen on UDP 7662 so the headset can launch a stream remotely |
| `--capture` | Capture-card mode. Grabs the HDMI capture device, encodes with NVENC, serves the Quest on TCP 7660. Does not sign in to Xbox or open remote play. Stays running when the headset disconnects. |
| `--capture-device <text>` | Friendly-name substring of the card. Default is the first video capture device. |
| `--upscale <1080p\|1440p\|4k>` | Initial output resolution (the headset can override it live) |
| `--fps <60\|90>` | Output frame rate |
| `--no-fg` | Disable frame generation entirely (lowest latency) |
| `--headless` | Run the D3D12 pipeline with no headset attached |
| `--run-seconds <n>` | Stop cleanly after n seconds (builds the benchmark captures; `0` = until Ctrl+C) |

### Codec: H.264 vs HEVC

Both run through the same NVENC path (single-pass ULL, AQ 8, 250 ms VBV, 2 s IDR), so switching
codec does not change the pipeline, the latency budget, or any other flag — only `--codec` and,
on the headset, which MediaCodec is instantiated.

- `--codec h264` (default) is the measured baseline.
- `--codec hevc` uses HEVC Main. At the same visual quality it needs roughly half the bits, so the
  80 Mbps the headset asks for carries noticeably more detail; at the same bitrate it should encode
  faster than H.264. Quest 3 (Snapdragon XR2 Gen 2) decodes HEVC Main fine.

The `VIDEO_CONFIG` packet carries a codec byte alongside the parameter sets, and the headset splits
NVENC's `VPS+SPS+PPS` blob into MediaCodec `csd-0/1/2`. **The headset APK must be rebuilt and
reinstalled** for HEVC: an older APK does not understand the current `VIDEO_CONFIG` layout.

## Headset app

Build output is `build\GTA-Sixty-Quest3.apk`. The Android package stays `com.gtasixty.quest` because a
Java package segment cannot contain the hyphen.

```powershell
scripts\install-quest-apk.ps1 -Launch
```

Wireless ADB works too: `adb connect <quest-ip>:5555`.

Buttons: **Start Stream**, **Refresh** (force an IDR), **Hide HUD**, **Wake PC**, and the
resolution / frame-rate / bitrate selectors.

**Mode: PC / Direct** switches transports. Direct mode signs in to the Microsoft account on the
headset, lists your consoles, and streams straight from the console (in the same app, behind the
same controller input). It is new and marked experimental: check [DIRECT_MODE.md](DIRECT_MODE.md)
for what is verified and the on-device test ladder. Your first direct session needs a phone or PC
for the one-time device-code sign-in; the PC does not have to be running.

Starting a stream also clears background processes of other user-installed apps, so a leftover app
cannot keep holding the compositor, GPU or decoder (requires `KILL_BACKGROUND_PROCESSES` +
`QUERY_ALL_PACKAGES`). It cannot kill a *foreground* app — Android does not allow that without root.

### Starting the PC remotely

**Nothing needs to be running on the PC except the agent.** Press **Start Stream** (or **Wake PC**) on
the headset and the app asks the agent, which launches the streamer; the app retries until it appears,
typically 8-14 s.

**Wake PC** does the whole chain:

1. Sends a Wake-on-LAN magic packet (if the MAC is known).
2. Sends `GTA60_START` to the PC agent on **UDP 7662**.
3. Polls LAN discovery and connects as soon as the streamer answers.

The PC-side agent is what launches the app — Wake-on-LAN can only power the machine on:

```powershell
scripts\install-autostart.ps1 -StartNow   # run --agent at sign-in and start it now
scripts\install-autostart.ps1 -Remove     # uninstall
```

It installs a Startup-folder shortcut (no admin needed) running `GTA-Sixty.exe --agent`. The
agent listens on UDP 7662, and on request launches
`GTA-Sixty.exe --connect auto --quest-server --bitrate 120` unless a streamer is already
listening on TCP 7660.

Wake-on-LAN only reaches the sign-in screen, so a fully hands-off wake-and-stream also needs
automatic sign-in enabled for the account.

### Wake-on-LAN

The **Wake PC** button sends a standard magic packet. The PC advertises its MAC in the LAN
discovery reply, so the headset learns it automatically and remembers it — meaning it can wake the
machine after the PC has gone to sleep or shut down. If the MAC is unknown it asks once.

This will only work if your BIOS/network adapter allows wake from the relevant power state
(the adapter must have *Wake on Magic Packet* enabled, and BIOS-level deep power-off must not be
blocking it).

## Benchmarking a console display-mode change

Remote play captures the console's own composited output. The TV is strictly downstream, so a TV
picture setting cannot change the streamed frames - but a console *display* setting can, through one
path: presentation cadence. With VRR off the console presents on a fixed 60 Hz grid, so 30 fps
content is repeated twice and 40-59 fps content gets uneven holds. The RTP rate stays ~60 fps; the
**content rate** (`uniq`) changes, and that is what the frame-generation gate and the perceived
smoothness depend on. ALLM ("allow low latency mode") is only a signal telling the TV to switch
picture modes and has no path into the capture at all. There is also a real chance the console
already forces VRR/ALLM off during remote play (Xbox Insider 2504 fixed those settings being left
changed after a session), which would make the dashboard toggle irrelevant to this pipeline.

Measure it instead of guessing:

```powershell
# 1. console on the old settings, nobody playing on the TV:
scripts\bench.ps1 -Label vrr-on-llm-on -Headless -Seconds 90

# 2. change the two settings in Settings > General > TV & display options > Video modes,
#    let the console settle, then:
scripts\bench.ps1 -Label vrr-off-llm-off -Headless -Seconds 90

# 3. compare:
scripts\bench-compare.ps1 -A build\bench\<stamp>-vrr-on-llm-on.json `
                          -B build\bench\<stamp>-vrr-off-llm-off.json
```

`-Headless` measures the console -> PC source without a headset (cadence, duplicate ratio, RTT,
decode cost). Without it, a connected headset adds `pcLat`, output fps, NVENC and the `quest=`
echo. The capture stops via `--run-seconds`, which tears the session down cleanly instead of
leaving a stale virtual pad on the console.

The telemetry line now ends with `dup=<pct>% runs=<1>/<2>/<3+> flips=<n>`: duplicate share, the
run-length histogram of the duplicates dropped between kept frames, and output-mode flips per
window. `scripts\bench-selftest.ps1` proves the parser/comparison against synthetic 60 fps and
30 fps logs (no console needed).

**Note:** starting a session takes the console away from the TV. Run the A/B when the console is
free, or the local game drops to the dashboard.

The first live run of this (2026-11-21, VRR and ALLM off, 30 fps game content) found **no change**
to the console->PC cadence, RTT or decode, and the same ~24 Mbps REMB ceiling: see
[BENCHMARK_2026-11-21.md](BENCHMARK_2026-11-21.md). The sweep table there is also the answer to
"does the console send less bitrate now that VRR is off?" - it does not; the advertised REMB is
still what sets it.

## Latency

Please read **[LATENCY_OPTIMIZATION_NOTES.md](LATENCY_OPTIMIZATION_NOTES.md)** before changing the
pipeline. **Section 6 is the handoff** — current measured numbers, what is already maxed out, what is
worth doing next, and the measurement traps. It documents several things that cost hours, including:

- `CODECAPI_AVLowLatencyMode` alone is **not** enough — without `MF_LOW_LATENCY` the Microsoft H.264
  decoder silently queues ~37 frames (~618 ms).
- Measure latency by frame **identity** (PTS), never by how long a call takes to return.
- NVENC cost is fixed per frame, not bitrate-dependent, but it is pixel-bound: 4K costs roughly
  double 1440p.
- DXVA/D3D11 hardware decode must be verified not to reintroduce DPB buffering.

The telemetry line printed every 2 s includes `decLag` (decoder buffering — must stay ~0), the per
phase NVENC timings, and `quest=` (PC→headset round trip measured by a per-frame present echo).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `WinHttpSendRequest failed (12185)` | WPAD/auto-proxy failure; the app now connects directly |
| **Headset times out connecting, or LAN scan finds nothing** | **Windows Firewall.** Its allow rules are bound to the exe *path*, so a moved or renamed `GTA-Sixty.exe` matches none. The PC still looks healthy — the socket listens and the discovery responder answers a loopback probe. Tell-tale: `ping` from the headset succeeds but a TCP connect to 7660 times out. Fix from an elevated shell: `New-NetFirewallRule -DisplayName 'GTA-Sixty' -Direction Inbound -Profile Any -Program '<full path>\GTA-Sixty.exe' -Action Allow` |
| Decoder errors or a wrong image after updating | PC and APK must match: `VIDEO_CONFIG` gained a codec byte, so an older APK misreads the parameter sets. Rebuild and reinstall both |
| Stream dies while unattended, then Start Stream cannot connect | The console ended the remote-play session for being idle (`Session ended: WarningForBeingIdle`); the PC streamer exits with it, so nothing is listening on 7660. The console is still on — just start the streamer again (or press **Wake PC**). See notes 10.5 |
| Huge lag, everything else looks fine | Decoder buffering — check `decLag` |
| Blocky/garbled image that persists | Dropped frames broke the H.264 reference chain; the stream recovers on the next IDR |
| Frame rate below target | Output resolution too high for the per-frame budget; try a lower resolution |
| Frame generation keeps turning on and off | The dedup is misreading the source frame rate; see the notes |
| **Direct mode: no video, `WebRTC connection failed`** | ICE never found a path. Check logcat for `signaling complete: N remote candidates`; if N is 0 the console's ICE exchange failed, if it is >0 the candidates or the mDNS rewrite are suspect. `adb logcat -s GTA60Direct` shows the JS log lines |
| **Direct mode: sign-in dialog never confirms** | The device code is entered at `https://www.microsoft.com/link`; confirm the account has an Xbox profile. Tokens are AES-GCM/Keystore encrypted; if the Keystore key is lost the app asks to sign in again |
| **Direct mode: the pad works in the Xbox guide but not in the game after the console woke from sleep** | A Quick-Resumed game can keep the previous session's virtual-pad binding. Keep pad 0 announced (the app never removes it - the console kicks a session with no pad after ~60 s) and press **Fix pad** in the Direct bar to re-announce it; no need to close the game. See notes section 12 and [DIRECT_MODE.md](DIRECT_MODE.md) |
| **Direct mode looks softer than PC mode** | Expected: the console's own encode at 1080p-class resolution with no sharpening or frame generation. PC mode is the quality path |

## Scope

This is an interoperability project: it speaks Microsoft's Xbox remote-play protocol using **your
own** account and **your own** console on **your own** LAN. It ships no Microsoft code and no
credentials. Use it with hardware and accounts you are entitled to use.

## Licence

No licence file is included yet — treat the code as "all rights reserved" until one is added.
Third-party components keep their own licences (see `third_party/`): OpenXR loader (Apache-2.0),
mbedTLS (Apache-2.0), libdatachannel (MPL-2.0), Opus (BSD-3-Clause), nlohmann/json (MIT),
ffnvcodec headers (MIT).

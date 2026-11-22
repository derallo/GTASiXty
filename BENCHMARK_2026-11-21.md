# Benchmark — 2026-11-21: VRR/ALLM off, and the console's source bitrate

## Setup

- Console: Xbox Series X, **VRR off**, **"allow low latency mode" (ALLM) off** (changed this morning
  so the TV can motion-smooth).
- Path: console -> PC (wired) -> Quest 3. The captures are **headless** (`--headless`): no headset
  was connected, so they measure the console->PC source and the PC's decode. `pcLat`, NVENC and
  `quest=` are not applicable in these runs.
- Tooling: `scripts\bench.ps1` (90 s, then 60 s per sweep point), clean stop via `--run-seconds`.
  Raw CSV/JSON/Markdown per capture under `build\bench\`.

## 1. Did VRR-off change what the console sends?

The previous (VRR-on) session's telemetry contains a long 30 fps-content window; today's capture is
also 30 fps content, so the source-side metrics are directly comparable.

| metric | VRR on, 30 fps content (4131 samples, 2026-11-20) | VRR off, 30 fps content (today) |
|---|---|---|
| RTP fps (`in`) | 59.89 | 60.28 |
| unique content fps (`uniq`) | 29.47 | 29.55 (REMB 0) / 29.86 (REMB 18) / 26.44 (REMB 50) |
| duplicate share | not instrumented then | 50.7% / 50.1% / 55.9% |
| RTT | 17.14 ms | 17.04 / 17.11 / 17.21 ms |
| decode | 3.36 ms | 3.49 / 3.50 / 3.55 ms |
| source bitrate @ REMB 50 | 23.4 Mbps (notes 6.1) | 24.20 Mbps |

The content rate is ~30 unique fps inside a ~60 fps RTP stream **both before and after**: one
duplicate between kept frames, `runs ≈ 1`. The `uniq` spread in the REMB-50 row is a scene effect
(the capture includes pauses/static moments that run to 3+ duplicates), not a cadence change - the
median is 30.0 in every run. RTT and decode are unchanged within noise.

For the Quest path specifically, the previous VRR-on 30 fps-content window measured `pcLat 22.98 ms`,
`out 113.6 Mbps`, NVENC 6.66 ms and output 60 fps (frame generation active). Today's headless
captures cannot measure those fields, but they establish the input to that path - `uniq ~30`,
`runs ≈ 1` - is unchanged, so the PC has no new work to do and the Quest-side numbers should match.

**Conclusion:** for this content, turning VRR and ALLM off did not change the console's stream. That
is also what the protocol implies:

- ALLM only tells the TV to switch picture modes; it is downstream of the capture and has no path
  into the stream.
- VRR changes presentation timing, but remote play already encodes a fixed 60 fps stream; a 30 fps
  game is duplicated twice per frame with VRR on or off. VRR can only reach the Quest through a
  *game* changing its render mode when VRR disappears (e.g. a 40 fps mode falling back to 30). That
  did not happen here.

## 2. Does the console's remote-play bitrate change with VRR off?

No. The source rate still follows the REMB we advertise, with the same ~24 Mbps ceiling as before:

| advertised REMB | measured source bitrate | p95 | max |
|---|---|---|---|
| 0 (no REMB) | 14.13 Mbps | 14.9 | 15.6 |
| 18 | 16.67 Mbps | 17.4 | 17.8 |
| 50 | 24.20 Mbps | 25.4 | 26.8 |
| 80 | 23.19 Mbps | 25.6 | 27.1 |

The "18" remembered earlier is the advertised cap, not a console ceiling: at REMB 18 the console
targets ~18 and delivers ~16.7; advertise 50 or 80 and it returns to its own ~24 Mbps ceiling
(notes 6.1 measured 23.4 at REMB 50 with VRR on). RTT stayed 17.0-17.2 ms across the sweep.

## 3. What this means for the Quest app

The PC's per-frame work is content-driven, not display-mode-driven:

- **60 unique fps content** -> bypass, RCAS only, `pcLat ~10 ms` (notes 6.1).
- **30 unique fps content** -> frame generation engages; notes 9.7 measured `pcLat ~23 ms`,
  NVENC ~7.1 ms, output still 60 fps. Today's VRR-off source is exactly that 30 fps shape.

So the Quest app's current workload matches the pre-change workload for the same content. The only
cost that changed is on the TV: motion smoothing adds an estimated +10-125 ms of *display* latency
(RTINGS measurements vary by TV and picture mode), which is invisible to the headset because the TV
is downstream of the console capture.

## Caveats

- No headset was connected during these captures: they prove the **source** and decode are
  unchanged, and the PC's encode behavior is inferred from the measured 30 fps content and the
  previous FG-mode numbers (9.7). A headset-side capture would add today's `pcLat`/`quest=`.
- Content and scene differed slightly between sweep points (the `uniq` variation), so the bitrate
  comparison should be read as "no regression", not a precise equality.
- The historical window is from 2026-11-20 and is assumed to be the VRR-on configuration; the notes
  do not record the console's display settings at that time.

## Reproduce

```powershell
scripts\bench.ps1 -Label vrr-off -Headless -Seconds 90
scripts\bench.ps1 -Label vrr-off-remb0  -Headless -Seconds 60 -XboxBitrate 0
scripts\bench.ps1 -Label vrr-off-remb18 -Headless -Seconds 60 -XboxBitrate 18
scripts\bench.ps1 -Label vrr-off-remb80 -Headless -Seconds 60 -XboxBitrate 80
```

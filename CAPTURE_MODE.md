# Capture mode

Shipped host is the Windows streamer. Run `GTA-Sixty.exe --capture` (optional `--capture-device` name, `--bitrate`, `--codec hevc`). It grabs the card with Media Foundation, encodes with the same NVENC path as the Xbox streamer, and serves the existing GT60 port. On the Quest, Mode cycles PC, then Direct, then Capture. Capture plays that stream and leaves the controller paired to the Xbox. It does not send `GTA60_START`.

A third path, next to PC mode and Direct mode. The Xbox's HDMI port is the source. A capture card feeds the Windows PC. The notes further down also describe a Linux laptop as another possible host. The Quest plays the stream with the client it already has.

This is the route around the ~22 Mbps remote-play cap. Remote play is the console's own encoder, and that encoder is what refuses to go higher. HDMI is the picture the console already drew. The bitrate after that is ours.

## Do not plug the card into the Quest

Meta's HDMI Link path is a UVC capture card on the headset's USB-C port, and that path is 1080p60. The panel is 2560×1440, so 1080p is a soft upscale, and the USB port is then busy being a camera. The existing app already shows 1440p (and HEVC) when the video arrives over the network. The 1080p limit is the USB video input, not the decoder.

## Shape

```text
Xbox HDMI
  -> capture card (uncompressed UVC)
  -> Linux laptop, wired Ethernet
  -> encode
  -> Wi-Fi
  -> Quest, existing GT60 client (TCP 7660)
```

The controller stays paired to the Xbox. Capture mode does not open an xhome session, so it does not take the console's only remote-play slot and it cannot collide with PC or Direct mode.

The laptop sits next to the Xbox, because HDMI cables are short. Its Ethernet port goes to the same access point the Quest uses. The Quest stays on 5 or 6 GHz. The laptop does not join that Wi-Fi. Two wireless hops will eat the bitrate this mode exists to get.

## What the laptop runs

One small process. Not a desktop streaming app.

1. Grab frames from V4L2. Ask for a short queue (2 or 3 buffers). A driver that queues 8 frames has already spent 100 ms before the encoder starts.
2. Encode with the laptop's hardware encoder. Intel VAAPI / Quick Sync, AMD VAAPI, or NVENC. No B-frames, no lookahead, CBR, VBV about one frame, repeat SPS/PPS, IDR on connect.
3. Send the existing GT60 framing: 16-byte header, video config (width, height, codec, parameter sets), access units, PCM audio. Answer `GTA60_DISCOVER` on UDP 7661 with `GTA60_OFFER`, the same way the Windows streamer does.
4. If the Quest falls behind, drop frames. Do not build a queue. The Windows sender already does this.

GStreamer or ffmpeg is the right first experiment (`v4l2src` / `ffmpeg -f v4l2`, hardware encoder, local preview). Wrap GT60 only after the picture and the delay look right. ffmpeg does not speak GT60, so the protocol writer is a thin program in front of the encoder, not a new streaming stack.

Software x264 is the wrong default. Ultrafast can hold 1080p60 on a recent CPU and then falls over at 1440p60, which is the resolution worth sending. If the laptop has no usable GPU encoder, send 1080p and accept a softer picture. Do not put optical flow on this box.

## What the Quest does

Capture mode is PC mode's receiver with the Xbox parts turned off.

- Connect to the laptop on TCP 7660. Discovery can find it.
- Decode H.264 or HEVC with MediaCodec, same as today.
- Do not send `GTA60_START`. Nothing on the laptop is waiting to launch a Windows streamer.
- Do not forward the headset pad. There is no xhome session to hand it to. Buttons pressed on a Quest-paired controller go nowhere. The Xbox pad is the pad.
- Do not try to stop a PC session. Capture mode is not using the console's stream slot.

A mode button that cycles PC / Direct / Capture is enough. Under the hood Capture is "play this GT60 host, and leave the console alone."

## Resolution, codec, bitrate

| Choice | Recommendation |
|---|---|
| Xbox HDMI output | Fixed 1440p60, SDR. Turn VRR off for the capture. Many cards time VRR badly, and the Quest pipeline is SDR. |
| What the card must deliver | Uncompressed UVC, YUY2 or NV12, at that resolution. Confirm with `v4l2-ctl --list-formats-ext` before writing the sender. |
| What to encode | 1440p60. The panel is 1440p. A 4K encode costs bitrate and decode time the panel cannot show. |
| Codec | HEVC if the laptop encoder is good at it. The Quest client already takes `video/hevc` on this link. H.264 if HEVC encode latency is worse on that GPU. |
| Bitrate | Start at 50 Mbps HEVC or 70 Mbps H.264, CBR. Raise toward 80–100 Mbps only while motion still looks starved and Wi-Fi loss stays near zero. |
| Frame rate on the wire | Send every captured frame, including duplicates. A still or a repeated 30 fps frame encodes to almost nothing, so CBR does not waste the link the way remote play's rate control does. |

1440p60 YUY2 is about 3.5 Gbit/s. That fits USB 3 and does not fit USB 2. The laptop's USB-C port has to be USB 3, on its own controller, not shared with a dongle.

The failure mode to avoid: a card that says "4K" and then ships H.264 or a harsh MJPEG off its own chip. That encode is often no better than the 22 Mbps console stream, and it adds a generation of compression plus latency. If the only 1440p/4K format on the card is MJPEG, use the uncompressed 1080p60 mode instead and keep the quality decision in our encoder.

## Latency

HDMI out of the Xbox is the game's normal present path. It does not pass through the remote-play encoder, so a clean capture can beat Direct mode on both delay and bitrate. A bad capture card gives the delay back.

Rough budget when the card and the encoder are behaving:

| Stage | Time |
|---|---|
| Capture card | 8–20 ms for a good UVC card. Cheap ones sit at 30–70 ms. |
| Hardware encode | 2–8 ms with lookahead off |
| Network | A few milliseconds on a quiet 5/6 GHz hop from a wired laptop |
| Quest decode | Around 6 ms at 1440p, from the Direct-mode measurements |
| Jitter buffer | Keep it shallow. One or two frames, not the browser's buffer |

Glass-to-glass of about 30–45 ms on top of the console's own HDMI delay is a good result. Past 60 ms the picture is cleaner than remote play and the feel is worse.

Measure capture-to-send on the laptop and decode plus jitter on the Quest. A phone recording both the television (or the card's loop-out) and the headset is enough to know if the card itself is the problem. Do that before building the sender.

## Audio

Take HDMI audio from the card (UAC) and send PCM, 48 kHz stereo, on the GT60 audio message. That is about 1.5 Mbps and the Quest already plays it. Stamp audio with the same clock as the frames. Lip sync will need one adjustment; it does not need Opus in the first version.

## Frame generation

Capture mode fixes bitrate first. It does not fix the 30-unique-frames-inside-60 cadence.

If the console scans out 60 Hz with every other frame repeated, the encoder sends that cheaply and the Quest at 120 Hz holds each unique frame for four vsyncs. Same judder as now, sharper picture.

The Quest's frame generation lives in the Direct-mode WebView, not on the MediaCodec surface this link draws to. The Windows optical-flow path is the other one, and it expects a decoded Xbox stream on that GPU. Neither runs by pointing the current client at a laptop.

Leave frame generation out of the first capture client. If the sharper picture is not enough, the next step is a capture source feeding the Windows pipeline (card in that PC, or the laptop forwarding raw frames, which is a lot of bandwidth). Porting optical flow onto the Linux box is the heavy version of the thing this mode is trying not to be.

## What I would not build

- A new protocol. RTP, SRT, and a browser on the Quest are all more code than GT60, which the headset already decodes.
- Gamepad forwarding through the laptop. The lowest-latency pad is the one paired to the console. A virtual pad here recreates the Direct-mode pad bugs with no session to attach them to.
- Running the capture laptop on Wi-Fi.
- Stacking remote play on top of HDMI. The HDMI picture is the one we want. A second console encode only burns the session slot.
- Using the card's built-in encoder as the stream the Quest receives.

## First checks, before a client

1. Card on the laptop. `v4l2-ctl --list-formats-ext`. Uncompressed 1440p60 or 1080p60 has to be on the list.
2. Preview with the smallest V4L2 queue the driver allows. If the preview is already late or soft, the software will not fix it.
3. Encode a moving scene to a local file at the target bitrate and compare it to a remote-play capture of the same scene. This is the whole point of the mode. A still menu is not the test.
4. Only then, a GT60 sender and the Quest pointed at the laptop's address.

The Windows PC can host the same card and reuse NVENC plus the current Quest sender. That is less new code if that PC can sit on a short HDMI run from the Xbox. The laptop is the right host when the Windows machine stays somewhere else, and when the capture box should stay a capture box.

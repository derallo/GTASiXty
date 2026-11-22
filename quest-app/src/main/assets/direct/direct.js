// Direct xhome client media/input/GPU-enhancement half.
//
// Java owns auth, REST and session lifecycle; this page owns the WebRTC peer connection, the four
// data channels, the gamepad reports (including rumble forwarding), and the on-Quest WebGL2 GPU
// enhancement pipeline (AMD FidelityFX RCAS sharpening, EASU+RCAS Super-Resolution upscaling,
// Normal/Vivid/Night color looks, async PBO duplicate detection, and 2-level 80x45->320x180
// coarse-to-fine optical-flow 30->60 frame generation on the Quest 3's Adreno 740 GPU).
(function () {
  'use strict';

  var B = window.GTA60Bridge || {
    log: function () {},
    onState: function () {},
    onError: function () {},
    onRumble: function () {}
  };
  var HANDSHAKE_ID = 'be0bfc6d-1e83-4c8a-90ed-fa8601c5a179';
  var ACCESS_KEY = '4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E';

  // Enhancement modes:
  //   0 = OFF      (native <video> element)
  //   1 = RCAS     (WebGL2 AMD FidelityFX RCAS contrast-adaptive sharpening at 2560x1440)
  //   2 = SUPERRES (WebGL2 Edge-Adaptive Spatial Upscaling + RCAS at 3200x1800 supersampled canvas)
  //   3 = FG60     (WebGL2 2-level Optical Flow + FBO Warp + Separate 1px RCAS Pass)
  //   4 = FG60+SR  (WebGL2 2-level Optical Flow + FBO Warp + 3200x1800 SuperRes + RCAS)
  var MODE_OFF = 0;
  var MODE_RCAS = 1;
  var MODE_SUPERRES = 2;
  var MODE_FG60 = 3;
  var MODE_FG60_SR = 4;
  var MODE_NAMES = ['OFF', 'RCAS', 'SUPERRES', 'FG60', 'FG60+SR'];

  // Color look presets:
  //   0 = Normal (unmodified color)
  //   1 = Vivid  (gentle S-curve contrast + 12% saturation)
  //   2 = Night  (gentle shadow lift + 8% saturation for night driving on Quest 3 LCD)
  var LOOK_NORMAL = 0;
  var LOOK_VIVID = 1;
  var LOOK_NIGHT = 2;
  var LOOK_NAMES = ['Normal', 'Vivid', 'Night'];

  var pc = null;
  var video = null;
  var canvas = null;
  var audio = null;
  var hud = null;
  var errBox = null;
  var channels = {};
  var handshakeSent = false;
  var ready = false;
  var seq = 0;
  var lastPad = { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
  var lastUserPadAt = 0;
  var keepaliveJiggle = 256;
  var statsTimer = null;
  var keepaliveTimer = null;
  var keepaliveResetTimer = null;
  var lastBytes = 0;
  var lastStatsAt = 0;
  var lastFrames = 0;
  var localIp = '';
  var hudVisible = false;
  var enhanceMode = MODE_FG60_SR;
  var lookMode = LOOK_NORMAL;
  var rcasSharpness = 0.22;
  var lastRumbleZero = true;

  // Virtual-pad lifecycle. Pad 0 stays announced for the whole session: the console treats a
  // session with no pad as having no client and kicks it with KickForNoClientConnection ~60 s after
  // streaming starts (measured 2026-11-21). Android's gamepad presence only drives a re-announce
  // (add/remove/add) when a controller (re)connects, which gives a resumed game a fresh pad-arrival
  // event, and the Fix pad button replays it on demand.
  var padPresent = true;
  var localPadPresent = false;
  var lastPadPresenceAt = 0;
  var disconnectTimer = null;
  var remoteAudioStream = null;
  var remoteVideoStream = null;
  var vfcRunning = false;
  var vfcGen = 0;
  var lastResumeBounceAt = 0;
  var resumeAudioTimer = 0;

  // Pre-allocated binary report buffers to eliminate per-frame GC churn at 60-120 Hz.
  var padBuf = new ArrayBuffer(37);
  var padView = new DataView(padBuf);
  var fbBuf = new ArrayBuffer(43);
  var fbView = new DataView(fbBuf);
  var vfcFrameCount = 0;
  var lastVfcAt = 0;
  var lastRtpTs = -1;

  // WebGL2 on-Quest GPU enhancement state
  var gl = null;
  var glFailed = false;
  var glW = 0;
  var glH = 0;
  var canvasW = 0;
  var canvasH = 0;
  var FLOW_LO_W = 80;
  var FLOW_LO_H = 45;
  var FLOW_HI_W = 320;
  var FLOW_HI_H = 180;
  var DIFF_W = 4;
  var DIFF_H = 4;
  var vao = null;
  var progLuma = null;
  var progDownsample = null;
  var progDiff = null;
  var progFlowCoarse = null;
  var progFlowFine = null;
  var progFlowSmooth = null;
  var progWarp = null;
  var progRcas = null;

  var texRing = [null, null, null];
  var texLumaHi = [null, null];
  var fboLumaHi = [null, null];
  var texLumaHiScratch = null;
  var fboLumaHiScratch = null;
  var texLumaLo = [null, null];
  var fboLumaLo = [null, null];
  var texDiff = null;
  var fboDiff = null;
  var texFlowCoarse = null;
  var fboFlowCoarse = null;
  var texFlowFine = null;
  var fboFlowFine = null;
  var texFlowSmooth = null;
  var fboFlowSmooth = null;
  var texMid = null;
  var fboMid = null;
  var diffPixels = new Uint8Array(DIFF_W * DIFF_H * 4);

  // Async WebGL2 PIXEL_PACK_BUFFER (PBO) state for stall-free duplicate detection
  var pboSupported = false;
  var pboRing = [null, null];
  var pboFences = [null, null];
  var pboValid = [false, false];
  var pboPredictedDupe = [false, false];
  var pboUsedForPrediction = [false, false];
  var pboWriteIdx = 0;
  var pboSyncFrames = 3;
  var pboExpectedDupe = false;
  var pboMissedPolls = 0;
  var pboVerifiedPrevMad = 10.0;
  var pboVerifiedConsecDupes = 0;
  var pboVerifiedHasHistory = 0;
  var consecUniqueRun = 0;

  var prevUniqueSlot = 0;
  var currUniqueSlot = 1;
  var prevLumaIdx = 0;
  var currLumaIdx = 1;
  var hasUniqueHistory = 0;
  var consecDupes = 0;
  var prevFrameMad = 10.0;
  var pendingRealFrame = false;
  var realFramePresented = false;
  var realFrameTimerId = 0;

  // Telemetry counters
  var statUniqueFrames = 0;
  var statDupeFrames = 0;
  var statOutFrames = 0;
  var statFgFrames = 0;
  var statPboReads = 0;
  var statGpuMsSum = 0;
  var statGpuSamples = 0;
  var lastMad = 0;
  var fgActiveGate = false;

  function nowMs() { return performance.now(); }

  function log(msg) {
    B.log(String(msg));
    if (hud && hudVisible) hud.textContent = String(msg);
  }

  function fail(msg) {
    // Best effort: give the pad back before the channels close, so a failed session cannot leave a
    // stale virtual pad (or a held button) sitting on the console's player-1 slot.
    try { releasePad(); } catch (e) {}
    stopInternal();
    if (errBox) {
      errBox.style.display = 'block';
      errBox.textContent = String(msg);
    }
    B.onError(String(msg));
  }

  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0;
      return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
  }

  function sendText(ch, obj) {
    if (!ch || ch.readyState !== 'open') return false;
    ch.send(new TextEncoder().encode(JSON.stringify(obj)));
    return true;
  }

  function sendBin(ch, bytes) {
    if (!ch || ch.readyState !== 'open') return false;
    ch.send(bytes);
    return true;
  }

  function sendMessage(target, content) {
    return sendText(channels.message, {
      type: 'Message',
      content: JSON.stringify(content),
      id: uuid(),
      target: target,
      cv: ''
    });
  }

  function raiseH264Level(sdp) {
    return sdp
      .replace(/profile-level-id=4d001f/gi, 'profile-level-id=4d002a')
      .replace(/profile-level-id=4d001e/gi, 'profile-level-id=4d002a')
      .replace(/profile-level-id=42e01f/gi, 'profile-level-id=42e02a')
      .replace(/profile-level-id=42e01e/gi, 'profile-level-id=42e02a')
      .replace(/profile-level-id=42001f/gi, 'profile-level-id=42002a')
      .replace(/profile-level-id=42001e/gi, 'profile-level-id=42002a');
  }

  function conformOffer(sdp, localIp) {
    var lines = sdp.split(/\r?\n/);
    var fingerprint = '';
    var ufrag = '';
    var pwd = '';
    var firstPort = '9';
    var ptMain = '114', ptCb = '104', ptBase = '100';
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (!fingerprint && l.indexOf('a=fingerprint:') === 0) fingerprint = l;
      if (!ufrag && l.indexOf('a=ice-ufrag:') === 0) ufrag = l;
      if (!pwd && l.indexOf('a=ice-pwd:') === 0) pwd = l;
      if (firstPort === '9' && l.indexOf('m=audio ') === 0) {
        var mp = l.split(' ');
        if (mp.length > 1 && mp[1] !== '9' && mp[1] !== '0') firstPort = mp[1];
      }
      if (l.indexOf('a=fmtp:') === 0) {
        var fm = l.match(/^a=fmtp:(\d+)\s+.*profile-level-id=([0-9a-f]+)/i);
        if (fm) {
          var pt = fm[1];
          var prof = fm[2].toLowerCase();
          if (prof.indexOf('4d') === 0) ptMain = pt;
          else if (prof.indexOf('42e') === 0) ptCb = pt;
          else if (prof.indexOf('420') === 0) ptBase = pt;
        }
      }
    }
    var ip = localIp || '0.0.0.0';
    var out = [
      'v=0',
      'o=rtc 65029611 0 IN IP4 127.0.0.1',
      's=-',
      't=0 0',
      'a=group:BUNDLE 0 1 2',
      'a=group:LS 0 1',
      'a=msid-semantic:WMS *',
      'a=ice-options:ice2,trickle'
    ];
    if (fingerprint) out.push(fingerprint);
    out.push(
      'm=audio ' + firstPort + ' UDP/TLS/RTP/SAVPF 111',
      'c=IN IP4 ' + ip,
      'a=mid:0',
      'a=sendrecv',
      'a=rtcp-mux',
      'a=rtpmap:111 opus/48000/2',
      'a=fmtp:111 minptime=10;useinbandfec=1;stereo=1',
      'a=setup:actpass'
    );
    if (ufrag) out.push(ufrag);
    if (pwd) out.push(pwd);
    out.push(
      'm=video ' + firstPort + ' UDP/TLS/RTP/SAVPF ' + ptMain + ' ' + ptCb + ' ' + ptBase,
      'c=IN IP4 ' + ip,
      'b=AS:500000',
      'a=mid:1',
      'a=recvonly',
      'a=rtcp-mux',
      'a=rtpmap:' + ptMain + ' H264/90000',
      'a=rtcp-fb:' + ptMain + ' nack',
      'a=rtcp-fb:' + ptMain + ' nack pli',
      'a=rtcp-fb:' + ptMain + ' goog-remb',
      'a=fmtp:' + ptMain + ' level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=4d002a;x-google-min-bitrate=25000;x-google-start-bitrate=50000;x-google-max-bitrate=500000',
      'a=rtpmap:' + ptCb + ' H264/90000',
      'a=rtcp-fb:' + ptCb + ' nack',
      'a=rtcp-fb:' + ptCb + ' nack pli',
      'a=rtcp-fb:' + ptCb + ' goog-remb',
      'a=fmtp:' + ptCb + ' level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e02a;x-google-min-bitrate=25000;x-google-start-bitrate=50000;x-google-max-bitrate=500000',
      'a=rtpmap:' + ptBase + ' H264/90000',
      'a=rtcp-fb:' + ptBase + ' nack',
      'a=rtcp-fb:' + ptBase + ' nack pli',
      'a=rtcp-fb:' + ptBase + ' goog-remb',
      'a=fmtp:' + ptBase + ' level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42002a;x-google-min-bitrate=25000;x-google-start-bitrate=50000;x-google-max-bitrate=500000',
      'a=setup:actpass'
    );
    if (ufrag) out.push(ufrag);
    if (pwd) out.push(pwd);
    out.push(
      'm=application ' + firstPort + ' UDP/DTLS/SCTP webrtc-datachannel',
      'c=IN IP4 ' + ip,
      'a=mid:2',
      'a=sendrecv',
      'a=sctp-port:5000',
      'a=max-message-size:262144',
      'a=setup:actpass'
    );
    if (ufrag) out.push(ufrag);
    if (pwd) out.push(pwd);
    return out.join('\r\n') + '\r\n';
  }

  function videoCodecPrefs() {
    try {
      var caps = RTCRtpReceiver.getCapabilities('video');
      if (!caps || !caps.codecs) return null;
      var main = [], cb = [], base = [];
      for (var i = 0; i < caps.codecs.length; i++) {
        var c = caps.codecs[i];
        var mime = c.mimeType || '';
        var f = (c.sdpFmtpLine || '').toLowerCase();
        if (mime.indexOf('H264') >= 0 && f.indexOf('packetization-mode=1') >= 0) {
          if (f.indexOf('profile-level-id=4d') >= 0) main.push(c);
          else if (f.indexOf('profile-level-id=42e') >= 0) cb.push(c);
          else if (f.indexOf('profile-level-id=420') >= 0) base.push(c);
        }
      }
      var order = main.concat(cb).concat(base);
      B.log('codec order: ' + order.map(function (c) {
        return c.mimeType + (c.sdpFmtpLine ? ' ' + c.sdpFmtpLine : '');
      }).join(' | '));
      return order.length ? order : null;
    } catch (e) {
      return null;
    }
  }

  function audioCodecPrefs() {
    try {
      var caps = RTCRtpReceiver.getCapabilities('audio');
      if (!caps || !caps.codecs) return null;
      var opus = caps.codecs.filter(function (c) {
        return (c.mimeType || '').toLowerCase() === 'audio/opus';
      });
      return opus.length ? opus : null;
    } catch (e) {
      return null;
    }
  }

  function clientMetadata() {
    var buf = new ArrayBuffer(15);
    var dv = new DataView(buf);
    dv.setUint16(0, 8, true);            // ReportClientMetadata
    dv.setUint32(2, seq++, true);
    dv.setFloat64(6, nowMs(), true);
    dv.setUint8(14, 1);                  // max touch points
    return buf;
  }

  function videoFeedbackReport(rtpTimestamp, arrivalMs, submittedMs, decodedMs, renderedMs) {
    var t = Math.max(0, nowMs());
    var sent = t >>> 0;
    fbView.setUint16(0, 1, true);        // ReportMetadata (video presentation feedback)
    fbView.setUint32(2, seq++, true);
    fbView.setFloat64(6, t, true);
    fbView.setUint8(14, 1);              // 1 entry
    fbView.setUint32(15, rtpTimestamp >>> 0, true);
    fbView.setUint32(19, arrivalMs >>> 0, true);
    fbView.setUint32(23, submittedMs >>> 0, true);
    fbView.setUint32(27, decodedMs >>> 0, true);
    fbView.setUint32(31, renderedMs >>> 0, true);
    fbView.setUint32(35, sent, true);
    fbView.setUint32(39, sent, true);
    return fbBuf;
  }

  function writeGamepadReport(buttons, lx, ly, rx, ry, lt, rt) {
    padView.setUint16(0, 2, true);       // ReportGamepad
    padView.setUint32(2, seq++, true);
    padView.setFloat64(6, nowMs(), true);
    padView.setUint8(14, 1);             // one pad
    padView.setUint8(15, 0);             // index
    padView.setUint16(16, buttons & 0xffff, true);
    padView.setInt16(18, lx, true);
    padView.setInt16(20, ly, true);
    padView.setInt16(22, rx, true);
    padView.setInt16(24, ry, true);
    padView.setUint16(26, lt, true);
    padView.setUint16(28, rt, true);
    padView.setUint32(30, 1, true);      // virtual physicality
    padView.setUint8(34, 0);
    padView.setUint8(35, 0);
    padView.setUint8(36, 1);
    return padBuf;
  }

  function gamepadReport(state) {
    return writeGamepadReport(
      state.buttons || 0,
      state.lx || 0,
      state.ly || 0,
      state.rx || 0,
      state.ry || 0,
      state.lt || 0,
      state.rt || 0
    );
  }

  // ---------------------------------------------------------------- Virtual pad presence

  function controlPadAdded(added) {
    return sendText(channels.control, { message: 'gamepadChanged', gamepadIndex: 0, wasAdded: !!added });
  }

  function neutralPadReport() {
    return sendBin(channels.input, writeGamepadReport(0, 0, 0, 0, 0, 0, 0));
  }

  /**
   * Android's gamepad presence changed. A pad appearing triggers the add/remove/add re-announce; a
   * pad disappearing releases virtual pad 0 AFTER the handshake so a controller paired directly to
   * the Xbox keeps Player 1 instead of being displaced by an idle virtual pad 0.
   */
  function setPadPresent(present) {
    present = !!present;
    if (!ready || !channels.control) return false;
    if (present === localPadPresent && (present || !padPresent)) return true;
    localPadPresent = present;
    lastPadPresenceAt = nowMs();
    if (present) {
      resyncPad();
      log('gamepad connected on the headset; pad 0 re-announced');
    } else {
      lastPad = { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
      if (padPresent) {
        neutralPadReport();
        controlPadAdded(false);
        padPresent = false;
      }
      log('no gamepad on the headset; virtual pad 0 released (Xbox controller keeps player 1)');
      B.onState('padReleased');
    }
    return true;
  }

  /**
   * Force-sync virtual pad state:
   * - If a controller is connected to the headset, replay add/remove/add so a resumed game gets a
   *   fresh pad-arrival event.
   * - If no controller is connected to the headset (controller is paired to the Xbox), release
   *   virtual pad 0 so the Xbox-paired controller keeps Player 1 in-game.
   */
  function resyncPad() {
    if (!ready || !channels.control) return 'not streaming';
    if (!localPadPresent) {
      lastPad = { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
      neutralPadReport();
      controlPadAdded(false);
      padPresent = false;
      lastPadPresenceAt = nowMs();
      log('virtual pad released (Xbox-paired controller keeps player 1)');
      B.onState('padReleased');
      return 'released';
    }
    controlPadAdded(true);
    controlPadAdded(false);
    controlPadAdded(true);
    padPresent = true;
    lastPadPresenceAt = nowMs();
    neutralPadReport();
    sendBin(channels.input, gamepadReport(lastPad));
    log('pad re-synced (gamepadChanged add/remove/add)');
    B.onState('padResync');
    return 'ok';
  }

  /** Neutral report, then take the pad away. Used before closing a direct session. */
  function releasePad() {
    if (!ready || !channels.control) return;
    neutralPadReport();
    controlPadAdded(false);
    padPresent = false;
    lastPad = { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
    log('released virtual pad before stopping');
  }

  // Pure-JS duplicate cadence classifier (also exposed for unit testing via _classifyDupe).
  function classifyDupeCadence(mad, maxBlock, prevMad, curConsecDupes, curHasHistory) {
    if (curHasHistory < 1 || curConsecDupes >= 1) return false;
    return (mad < 1.9 && maxBlock <= 9) || (prevMad >= 3.5 && mad < prevMad * 0.38);
  }

  function analyzeDiffPixels(bytes) {
    var sumDiff = 0;
    var maxBlock = 0;
    for (var i = 0; i < bytes.length; i += 4) {
      var dAvg = bytes[i];
      var dMax = bytes[i + 1];
      sumDiff += dAvg;
      if (dMax > maxBlock) maxBlock = dMax;
    }
    return { mad: sumDiff / (DIFF_W * DIFF_H), maxBlock: maxBlock };
  }

  // ---------------------------------------------------------------- WebGL2 On-Quest GPU Pipeline

  var VERT_FBO = '#version 300 es\n'
    + 'layout(location=0) in vec2 aPos;\n'
    + 'out vec2 vUv;\n'
    + 'void main() {\n'
    + '  vUv = aPos * 0.5 + 0.5;\n'
    + '  gl_Position = vec4(aPos, 0.0, 1.0);\n'
    + '}\n';

  var VERT_CANVAS = '#version 300 es\n'
    + 'layout(location=0) in vec2 aPos;\n'
    + 'out vec2 vUv;\n'
    + 'void main() {\n'
    + '  vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);\n'
    + '  gl_Position = vec4(aPos, 0.0, 1.0);\n'
    + '}\n';

  // Pass 1 (320x180): Luma + per-texel absolute difference against previous unique frame.
  var FRAG_LUMA = '#version 300 es\n'
    + 'precision mediump float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uVideoTex;\n'
    + 'uniform sampler2D uPrevLumaTex;\n'
    + 'out vec4 fragColor;\n'
    + 'void main() {\n'
    + '  vec3 c = texture(uVideoTex, vUv).rgb;\n'
    + '  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));\n'
    + '  float py = texture(uPrevLumaTex, vUv).r;\n'
    + '  fragColor = vec4(y, abs(y - py), 0.0, 1.0);\n'
    + '}\n';

  // Pass 1b (320x180 -> 80x45): Anti-aliased 4-tap bilinear box downsample for coarse optical flow.
  var FRAG_DOWNSAMPLE = '#version 300 es\n'
    + 'precision mediump float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uHiLumaTex;\n'
    + 'out vec4 fragColor;\n'
    + 'void main() {\n'
    + '  vec2 d = vec2(1.0 / 320.0, 1.0 / 180.0);\n'
    + '  float y = 0.25 * (\n'
    + '    texture(uHiLumaTex, vUv + vec2(-d.x, -d.y)).r +\n'
    + '    texture(uHiLumaTex, vUv + vec2( d.x, -d.y)).r +\n'
    + '    texture(uHiLumaTex, vUv + vec2(-d.x,  d.y)).r +\n'
    + '    texture(uHiLumaTex, vUv + vec2( d.x,  d.y)).r);\n'
    + '  fragColor = vec4(y, 0.0, 0.0, 1.0);\n'
    + '}\n';

  // Pass 2 (4x4): Regional abs-diff summary from 320x180 scratch buffer.
  var FRAG_DIFF = '#version 300 es\n'
    + 'precision mediump float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uLumaScratchTex;\n'
    + 'out vec4 fragColor;\n'
    + 'void main() {\n'
    + '  vec2 stepUv = vec2(1.0 / 320.0, 1.0 / 180.0);\n'
    + '  float sum = 0.0;\n'
    + '  float mx = 0.0;\n'
    + '  for (int dy = -1; dy <= 1; dy++) {\n'
    + '    for (int dx = -1; dx <= 1; dx++) {\n'
    + '      float d = texture(uLumaScratchTex, vUv + vec2(float(dx) * 16.0, float(dy) * 10.0) * stepUv).g;\n'
    + '      sum += d;\n'
    + '      mx = max(mx, d);\n'
    + '    }\n'
    + '  }\n'
    + '  fragColor = vec4(sum * (1.0 / 9.0), mx, 0.0, 1.0);\n'
    + '}\n';

  // Pass 3a (80x45): Level-0 Wide Coarse Optical Flow (+-160 px horiz / +-96 px vert at 1440p).
  var FRAG_FLOW_COARSE = '#version 300 es\n'
    + 'precision mediump float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uPrevLumaLo;\n'
    + 'uniform sampler2D uCurrLumaLo;\n'
    + 'out vec4 fragColor;\n'
    + 'const vec2 STEP = vec2(1.0 / 80.0, 1.0 / 45.0);\n'
    + 'const float MAX_UV = 0.08;\n'
    + 'float patchSad(vec2 uvCurr, vec2 uvPrev) {\n'
    + '  float s = abs(texture(uCurrLumaLo, uvCurr).r - texture(uPrevLumaLo, uvPrev).r) * 2.0;\n'
    + '  s += abs(texture(uCurrLumaLo, uvCurr + vec2(STEP.x, 0.0)).r - texture(uPrevLumaLo, uvPrev + vec2(STEP.x, 0.0)).r);\n'
    + '  s += abs(texture(uCurrLumaLo, uvCurr - vec2(STEP.x, 0.0)).r - texture(uPrevLumaLo, uvPrev - vec2(STEP.x, 0.0)).r);\n'
    + '  s += abs(texture(uCurrLumaLo, uvCurr + vec2(0.0, STEP.y)).r - texture(uPrevLumaLo, uvPrev + vec2(0.0, STEP.y)).r);\n'
    + '  s += abs(texture(uCurrLumaLo, uvCurr - vec2(0.0, STEP.y)).r - texture(uPrevLumaLo, uvPrev - vec2(0.0, STEP.y)).r);\n'
    + '  return s * (1.0 / 6.0);\n'
    + '}\n'
    + 'void main() {\n'
    + '  float bestSad = patchSad(vUv, vUv);\n'
    + '  vec2 bestOff = vec2(0.0);\n'
    + '  if (bestSad > 0.008) {\n'
    + '    for (int dy = -3; dy <= 3; dy++) {\n'
    + '      for (int dx = -5; dx <= 5; dx++) {\n'
    + '        if (dx == 0 && dy == 0) continue;\n'
    + '        vec2 off = vec2(float(dx), float(dy)) * STEP;\n'
    + '        float penalty = 0.0012 * length(vec2(float(dx), float(dy)));\n'
    + '        float s = patchSad(vUv, vUv + off) + penalty;\n'
    + '        if (s < bestSad) { bestSad = s; bestOff = off; }\n'
    + '      }\n'
    + '    }\n'
    + '  }\n'
    + '  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n'
    + '  fragColor = vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), 1.0);\n'
    + '}\n';

  // Pass 3b (320x180): Level-1 Fine Quarter-Texel Optical Flow (3x3 patch) + Bidirectional Consistency.
  var FRAG_FLOW_FINE = '#version 300 es\n'
    + 'precision mediump float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uPrevLumaHi;\n'
    + 'uniform sampler2D uCurrLumaHi;\n'
    + 'uniform sampler2D uCoarseFlow;\n'
    + 'out vec4 fragColor;\n'
    + 'const vec2 STEP = vec2(1.0 / 320.0, 1.0 / 180.0);\n'
    + 'const float MAX_UV = 0.08;\n'
    + 'vec2 decodeFlow(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n'
    + 'float patchSad(vec2 uvCurr, vec2 uvPrev) {\n'
    + '  float s = abs(texture(uCurrLumaHi, uvCurr).r - texture(uPrevLumaHi, uvPrev).r) * 2.0;\n'
    + '  s += abs(texture(uCurrLumaHi, uvCurr + vec2(STEP.x, 0.0)).r - texture(uPrevLumaHi, uvPrev + vec2(STEP.x, 0.0)).r);\n'
    + '  s += abs(texture(uCurrLumaHi, uvCurr - vec2(STEP.x, 0.0)).r - texture(uPrevLumaHi, uvPrev - vec2(STEP.x, 0.0)).r);\n'
    + '  s += abs(texture(uCurrLumaHi, uvCurr + vec2(0.0, STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2(0.0, STEP.y)).r);\n'
    + '  s += abs(texture(uCurrLumaHi, uvCurr - vec2(0.0, STEP.y)).r - texture(uPrevLumaHi, uvPrev - vec2(0.0, STEP.y)).r);\n'
    + '  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2( STEP.x,  STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2( STEP.x,  STEP.y)).r);\n'
    + '  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2(-STEP.x,  STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2(-STEP.x,  STEP.y)).r);\n'
    + '  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2( STEP.x, -STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2( STEP.x, -STEP.y)).r);\n'
    + '  s += 0.65 * abs(texture(uCurrLumaHi, uvCurr + vec2(-STEP.x, -STEP.y)).r - texture(uPrevLumaHi, uvPrev + vec2(-STEP.x, -STEP.y)).r);\n'
    + '  return s * (1.0 / 8.6);\n'
    + '}\n'
    + 'float patchSadRev(vec2 uvPrev, vec2 uvCurr) {\n'
    + '  float s = abs(texture(uPrevLumaHi, uvPrev).r - texture(uCurrLumaHi, uvCurr).r) * 2.0;\n'
    + '  s += abs(texture(uPrevLumaHi, uvPrev + vec2(STEP.x, 0.0)).r - texture(uCurrLumaHi, uvCurr + vec2(STEP.x, 0.0)).r);\n'
    + '  s += abs(texture(uPrevLumaHi, uvPrev - vec2(STEP.x, 0.0)).r - texture(uCurrLumaHi, uvCurr - vec2(STEP.x, 0.0)).r);\n'
    + '  s += abs(texture(uPrevLumaHi, uvPrev + vec2(0.0, STEP.y)).r - texture(uCurrLumaHi, uvCurr + vec2(0.0, STEP.y)).r);\n'
    + '  s += abs(texture(uPrevLumaHi, uvPrev - vec2(0.0, STEP.y)).r - texture(uCurrLumaHi, uvCurr - vec2(0.0, STEP.y)).r);\n'
    + '  return s * (1.0 / 6.0);\n'
    + '}\n'
    + 'void main() {\n'
    + '  vec2 initOff = decodeFlow(texture(uCoarseFlow, vUv).rg);\n'
    + '  float sad0 = patchSad(vUv, vUv);\n'
    + '  float bestSad = patchSad(vUv, vUv + initOff);\n'
    + '  vec2 bestOff = initOff;\n'
    + '  if (sad0 <= bestSad + 0.001) { bestSad = sad0; bestOff = vec2(0.0); }\n'
    + '  if (sad0 > 0.008 || length(initOff) > 0.0005) {\n'
    + '    vec2 s2 = bestOff;\n'
    + '    for (int dy = -1; dy <= 1; dy++) {\n'
    + '      for (int dx = -1; dx <= 1; dx++) {\n'
    + '        if (dx == 0 && dy == 0) continue;\n'
    + '        vec2 off = s2 + vec2(float(dx) * 2.0, float(dy) * 2.0) * STEP;\n'
    + '        float s = patchSad(vUv, vUv + off);\n'
    + '        if (s < bestSad) { bestSad = s; bestOff = off; }\n'
    + '      }\n'
    + '    }\n'
    + '    vec2 s1 = bestOff;\n'
    + '    for (int dy = -1; dy <= 1; dy++) {\n'
    + '      for (int dx = -1; dx <= 1; dx++) {\n'
    + '        if (dx == 0 && dy == 0) continue;\n'
    + '        vec2 off = s1 + vec2(float(dx), float(dy)) * STEP;\n'
    + '        float s = patchSad(vUv, vUv + off);\n'
    + '        if (s < bestSad) { bestSad = s; bestOff = off; }\n'
    + '      }\n'
    + '    }\n'
    + '    vec2 s0 = bestOff;\n'
    + '    for (int dy = -1; dy <= 1; dy++) {\n'
    + '      for (int dx = -1; dx <= 1; dx++) {\n'
    + '        if (dx == 0 && dy == 0) continue;\n'
    + '        vec2 off = s0 + vec2(float(dx), float(dy)) * STEP * 0.5;\n'
    + '        float s = patchSad(vUv, vUv + off);\n'
    + '        if (s < bestSad) { bestSad = s; bestOff = off; }\n'
    + '      }\n'
    + '    }\n'
    + '    vec2 sq = bestOff;\n'
    + '    for (int dy = -1; dy <= 1; dy++) {\n'
    + '      for (int dx = -1; dx <= 1; dx++) {\n'
    + '        if (dx == 0 && dy == 0) continue;\n'
    + '        vec2 off = sq + vec2(float(dx), float(dy)) * STEP * 0.25;\n'
    + '        float s = patchSad(vUv, vUv + off);\n'
    + '        if (s < bestSad) { bestSad = s; bestOff = off; }\n'
    + '      }\n'
    + '    }\n'
    + '  }\n'
    + '  vec2 prevUv = clamp(vUv + bestOff, vec2(0.001), vec2(0.999));\n'
    + '  float revSad = patchSadRev(prevUv, vUv);\n'
    + '  vec2 revCoarse = decodeFlow(texture(uCoarseFlow, prevUv).rg);\n'
    + '  float vecAgree = clamp(1.0 - (length(revCoarse - bestOff) / MAX_UV) * 2.2, 0.0, 1.0);\n'
    + '  float gain = clamp((sad0 - bestSad) / max(sad0, 0.005), 0.0, 1.0);\n'
    + '  float matchConf = clamp(1.0 - max(bestSad, revSad) * 7.5, 0.0, 1.0);\n'
    + '  float conf = matchConf * (sad0 <= 0.008 ? 1.0 : gain) * mix(0.45, 1.0, vecAgree);\n'
    + '  vec2 enc = clamp((bestOff / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n'
    + '  fragColor = vec4(enc.x, enc.y, clamp(bestSad * 4.0, 0.0, 1.0), conf);\n'
    + '}\n';

  // Pass 3c (320x180): 3x3 Bilateral Edge-Preserving Flow Smoothing & Outlier Rejection.
  var FRAG_FLOW_SMOOTH = '#version 300 es\n'
    + 'precision mediump float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uRawFlowTex;\n'
    + 'uniform sampler2D uCurrLuma;\n'
    + 'out vec4 fragColor;\n'
    + 'const vec2 STEP = vec2(1.0 / 320.0, 1.0 / 180.0);\n'
    + 'const float MAX_UV = 0.08;\n'
    + 'vec2 dec(vec2 r) { return (r - (128.0 / 255.0)) * 2.0 * MAX_UV; }\n'
    + 'void main() {\n'
    + '  float cLuma = texture(uCurrLuma, vUv).r;\n'
    + '  vec4 cFlow = texture(uRawFlowTex, vUv);\n'
    + '  vec2 cVec = dec(cFlow.rg);\n'
    + '  vec2 sumVec = vec2(0.0);\n'
    + '  float sumConf = 0.0;\n'
    + '  float sumW = 0.0;\n'
    + '  float maxDiff = 0.0;\n'
    + '  for (int dy = -1; dy <= 1; dy++) {\n'
    + '    for (int dx = -1; dx <= 1; dx++) {\n'
    + '      vec2 uv = vUv + vec2(float(dx), float(dy)) * STEP;\n'
    + '      vec4 nf = texture(uRawFlowTex, uv);\n'
    + '      float nl = texture(uCurrLuma, uv).r;\n'
    + '      vec2 nv = dec(nf.rg);\n'
    + '      float wSpatial = (dx == 0 && dy == 0) ? 2.0 : ((dx == 0 || dy == 0) ? 1.0 : 0.6);\n'
    + '      float wLuma = exp(-abs(nl - cLuma) * 10.0);\n'
    + '      float w = wSpatial * wLuma * (0.15 + nf.a);\n'
    + '      sumVec += nv * w;\n'
    + '      sumConf += nf.a * w;\n'
    + '      sumW += w;\n'
    + '      maxDiff = max(maxDiff, length(nv - cVec) / MAX_UV);\n'
    + '    }\n'
    + '  }\n'
    + '  vec2 avgVec = sumVec / max(sumW, 1e-4);\n'
    + '  float avgConf = (sumConf / max(sumW, 1e-4)) * clamp(1.0 - maxDiff * 1.4, 0.0, 1.0);\n'
    + '  vec2 enc = clamp((avgVec / MAX_UV) * 0.5 + (128.0 / 255.0), 0.0, 1.0);\n'
    + '  fragColor = vec4(enc.x, enc.y, cFlow.b, avgConf);\n'
    + '}\n';

  // Pass 4a (2560x1440 -> fboMid): Disocclusion-Safe Smooth-Weighted Midpoint Warp.
  var FRAG_WARP = '#version 300 es\n'
    + 'precision highp float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uPrevTex;\n'
    + 'uniform sampler2D uCurrTex;\n'
    + 'uniform sampler2D uFlowTex;\n'
    + 'out vec4 fragColor;\n'
    + 'const float MAX_UV = 0.08;\n'
    + 'vec2 decodeFlow(vec2 raw) {\n'
    + '  return (raw - (128.0 / 255.0)) * 2.0 * MAX_UV;\n'
    + '}\n'
    + 'void main() {\n'
    + '  vec3 c0 = texture(uPrevTex, vUv).rgb;\n'
    + '  vec3 c1 = texture(uCurrTex, vUv).rgb;\n'
    + '  bool inMinimap = (vUv.x < 0.24 && vUv.y > 0.72);\n'
    + '  bool inTopRightHud = (vUv.x > 0.77 && vUv.y < 0.22);\n'
    + '  bool inReticle = (abs(vUv.x - 0.5) < 0.018 && abs(vUv.y - 0.5) < 0.028);\n'
    + '  if (inMinimap || inTopRightHud || inReticle) {\n'
    + '    fragColor = vec4(c1, 1.0);\n'
    + '    return;\n'
    + '  }\n'
    + '  float es = dot(abs(c0 - c1), vec3(0.333333));\n'
    + '  if (es < 0.008) {\n'
    + '    fragColor = vec4(c1, 1.0);\n'
    + '    return;\n'
    + '  }\n'
    + '  vec4 f = texture(uFlowTex, vUv);\n'
    + '  vec2 v = decodeFlow(f.rg);\n'
    + '  vec2 p0 = clamp(vUv + 0.5 * v, vec2(0.001), vec2(0.999));\n'
    + '  vec2 p1 = clamp(vUv - 0.5 * v, vec2(0.001), vec2(0.999));\n'
    + '  vec3 w0 = texture(uPrevTex, p0).rgb;\n'
    + '  vec3 w1 = texture(uCurrTex, p1).rgb;\n'
    + '  float em = dot(abs(w0 - w1), vec3(0.333333));\n'
    + '  vec3 warpedMid = mix(w0, w1, 0.5);\n'
    + '  float fit = smoothstep(0.09, 0.012, em) * smoothstep(0.0, 0.022, es - em);\n'
    + '  float w = clamp(f.a * fit, 0.0, 1.0);\n'
    + '  fragColor = vec4(mix(c1, warpedMid, w), 1.0);\n'
    + '}\n';

  // Pass 4b (2560x1440 or 3200x1800 -> Canvas): Standalone True 1-Pixel AMD FidelityFX RCAS
  // + Edge-Directed Super-Resolution + Normal/Vivid/Night color grading.
  var FRAG_RCAS = '#version 300 es\n'
    + 'precision highp float;\n'
    + 'in vec2 vUv;\n'
    + 'uniform sampler2D uInputTex;\n'
    + 'uniform vec2 uTexelSize;\n'
    + 'uniform float uSharpness;\n'
    + 'uniform float uSuperRes;\n'
    + 'uniform float uLookMode;\n'
    + 'out vec4 fragColor;\n'
    + 'float lum(vec3 c) { return c.g + 0.5 * (c.r + c.b); }\n'
    + 'void main() {\n'
    + '  vec3 b = texture(uInputTex, vUv - vec2(0.0, uTexelSize.y)).rgb;\n'
    + '  vec3 d = texture(uInputTex, vUv - vec2(uTexelSize.x, 0.0)).rgb;\n'
    + '  vec3 e = texture(uInputTex, vUv).rgb;\n'
    + '  vec3 f = texture(uInputTex, vUv + vec2(uTexelSize.x, 0.0)).rgb;\n'
    + '  vec3 h = texture(uInputTex, vUv + vec2(0.0, uTexelSize.y)).rgb;\n'
    + '  float bL = lum(b);\n'
    + '  float dL = lum(d);\n'
    + '  float eL = lum(e);\n'
    + '  float fL = lum(f);\n'
    + '  float hL = lum(h);\n'
    + '  if (uSuperRes > 0.5) {\n'
    + '    vec3 nw = texture(uInputTex, vUv + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;\n'
    + '    vec3 ne = texture(uInputTex, vUv + vec2( uTexelSize.x, -uTexelSize.y)).rgb;\n'
    + '    vec3 sw = texture(uInputTex, vUv + vec2(-uTexelSize.x,  uTexelSize.y)).rgb;\n'
    + '    vec3 se = texture(uInputTex, vUv + vec2( uTexelSize.x,  uTexelSize.y)).rgb;\n'
    + '    float gx = (lum(ne) + 2.0 * fL + lum(se)) - (lum(nw) + 2.0 * dL + lum(sw));\n'
    + '    float gy = (lum(sw) + 2.0 * hL + lum(se)) - (lum(nw) + 2.0 * bL + lum(ne));\n'
    + '    vec2 dir = vec2(-gy, gx);\n'
    + '    float dirLen = length(dir);\n'
    + '    if (dirLen > 1e-4) {\n'
    + '      vec2 tDir = (dir / dirLen) * uTexelSize;\n'
    + '      vec3 e0 = texture(uInputTex, vUv - tDir * 0.5).rgb;\n'
    + '      vec3 e1 = texture(uInputTex, vUv + tDir * 0.5).rgb;\n'
    + '      vec3 e2 = texture(uInputTex, vUv - tDir * 1.15).rgb;\n'
    + '      vec3 e3 = texture(uInputTex, vUv + tDir * 1.15).rgb;\n'
    + '      vec3 easu = (e * 0.50 + 0.32 * (e0 + e1) - 0.07 * (e2 + e3));\n'
    + '      vec3 min4 = min(min(b, d), min(f, h));\n'
    + '      vec3 max4 = max(max(b, d), max(f, h));\n'
    + '      easu = clamp(easu, min(e, min4), max(e, max4));\n'
    + '      e = mix(e, easu, clamp(dirLen * 1.8, 0.0, 0.65));\n'
    + '      eL = lum(e);\n'
    + '    }\n'
    + '  }\n'
    + '  float nz = 0.25 * (bL + dL + fL + hL) - eL;\n'
    + '  float range = max(max(bL, dL), max(fL, max(hL, eL))) - min(min(bL, dL), min(fL, min(hL, eL)));\n'
    + '  float nzClamp = range > 1e-4 ? clamp(abs(nz) / range, 0.0, 1.0) : 0.0;\n'
    + '  vec3 mn = min(min(min(b, d), min(f, h)), e);\n'
    + '  vec3 mx = max(max(max(b, d), max(f, h)), e);\n'
    + '  vec3 hitMin = mn / max(4.0 * mx, vec3(1e-5));\n'
    + '  vec3 hitMax = (1.0 - mx) / min(4.0 * mn - 4.0, vec3(-1e-5));\n'
    + '  vec3 lobeRGB = max(-hitMin, hitMax);\n'
    + '  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0));\n'
    + '  lobe *= exp2(-uSharpness) * (1.0 - 0.5 * nzClamp);\n'
    + '  vec3 pix = clamp((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 0.0, 1.0);\n'
    + '  if (uLookMode > 1.5) {\n'
    + '    float y0 = dot(pix, vec3(0.2126, 0.7152, 0.0722));\n'
    + '    pix = pow(max(pix, vec3(0.0)), vec3(0.88)) + vec3(0.015 * (1.0 - y0));\n'
    + '    float y1 = dot(pix, vec3(0.2126, 0.7152, 0.0722));\n'
    + '    pix = mix(vec3(y1), pix, 1.08);\n'
    + '  } else if (uLookMode > 0.5) {\n'
    + '    pix = mix(pix, pix * pix * (3.0 - 2.0 * pix), 0.14);\n'
    + '    float y1 = dot(pix, vec3(0.2126, 0.7152, 0.0722));\n'
    + '    pix = mix(vec3(y1), pix, 1.12);\n'
    + '  }\n'
    + '  fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);\n'
    + '}\n';

  function compileShader(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      var info = gl.getShaderInfoLog(s);
      gl.deleteShader(s);
      throw new Error('shader compile failed: ' + info);
    }
    return s;
  }

  function linkProgram(vsSrc, fsSrc) {
    var vs = compileShader(gl.VERTEX_SHADER, vsSrc);
    var fs = compileShader(gl.FRAGMENT_SHADER, fsSrc);
    var p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      var info = gl.getProgramInfoLog(p);
      gl.deleteProgram(p);
      throw new Error('program link failed: ' + info);
    }
    return p;
  }

  function createTex2D(w, h) {
    var t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    return t;
  }

  function createFbo(tex) {
    var f = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, f);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    return f;
  }

  function resetPboState() {
    pboSyncFrames = 3;
    pboExpectedDupe = false;
    pboMissedPolls = 0;
    pboVerifiedPrevMad = 10.0;
    pboVerifiedConsecDupes = 0;
    pboVerifiedHasHistory = 0;
    consecUniqueRun = 0;
    pboWriteIdx = 0;
    for (var i = 0; i < 2; i++) {
      pboValid[i] = false;
      pboPredictedDupe[i] = false;
      pboUsedForPrediction[i] = false;
      if (gl && pboFences[i]) {
        try { gl.deleteSync(pboFences[i]); } catch (e) {}
      }
      pboFences[i] = null;
    }
  }

  function resetGlForContextLoss() {
    resetPboState();
    gl = null;
    glW = 0;
    glH = 0;
    canvasW = 0;
    canvasH = 0;
    glFailed = false;
  }

  function ensureGl(w, h) {
    if (glFailed || !canvas || typeof canvas.getContext !== 'function') return false;
    if (!gl) {
      try {
        gl = canvas.getContext('webgl2', {
          alpha: false,
          depth: false,
          stencil: false,
          antialias: false,
          desynchronized: true,
          powerPreference: 'high-performance',
          preserveDrawingBuffer: false
        });
        if (!gl) {
          glFailed = true;
          B.log('WebGL2 unavailable; falling back to native <video>');
          return false;
        }
        progLuma = { prog: linkProgram(VERT_FBO, FRAG_LUMA) };
        progLuma.uVideoTex = gl.getUniformLocation(progLuma.prog, 'uVideoTex');
        progLuma.uPrevLumaTex = gl.getUniformLocation(progLuma.prog, 'uPrevLumaTex');

        progDownsample = { prog: linkProgram(VERT_FBO, FRAG_DOWNSAMPLE) };
        progDownsample.uHiLumaTex = gl.getUniformLocation(progDownsample.prog, 'uHiLumaTex');

        progDiff = { prog: linkProgram(VERT_FBO, FRAG_DIFF) };
        progDiff.uLumaScratchTex = gl.getUniformLocation(progDiff.prog, 'uLumaScratchTex');

        progFlowCoarse = { prog: linkProgram(VERT_FBO, FRAG_FLOW_COARSE) };
        progFlowCoarse.uPrevLumaLo = gl.getUniformLocation(progFlowCoarse.prog, 'uPrevLumaLo');
        progFlowCoarse.uCurrLumaLo = gl.getUniformLocation(progFlowCoarse.prog, 'uCurrLumaLo');

        progFlowFine = { prog: linkProgram(VERT_FBO, FRAG_FLOW_FINE) };
        progFlowFine.uPrevLumaHi = gl.getUniformLocation(progFlowFine.prog, 'uPrevLumaHi');
        progFlowFine.uCurrLumaHi = gl.getUniformLocation(progFlowFine.prog, 'uCurrLumaHi');
        progFlowFine.uCoarseFlow = gl.getUniformLocation(progFlowFine.prog, 'uCoarseFlow');

        progFlowSmooth = { prog: linkProgram(VERT_FBO, FRAG_FLOW_SMOOTH) };
        progFlowSmooth.uRawFlowTex = gl.getUniformLocation(progFlowSmooth.prog, 'uRawFlowTex');
        progFlowSmooth.uCurrLuma = gl.getUniformLocation(progFlowSmooth.prog, 'uCurrLuma');

        progWarp = { prog: linkProgram(VERT_FBO, FRAG_WARP) };
        progWarp.uPrevTex = gl.getUniformLocation(progWarp.prog, 'uPrevTex');
        progWarp.uCurrTex = gl.getUniformLocation(progWarp.prog, 'uCurrTex');
        progWarp.uFlowTex = gl.getUniformLocation(progWarp.prog, 'uFlowTex');

        progRcas = { prog: linkProgram(VERT_CANVAS, FRAG_RCAS) };
        progRcas.uInputTex = gl.getUniformLocation(progRcas.prog, 'uInputTex');
        progRcas.uTexelSize = gl.getUniformLocation(progRcas.prog, 'uTexelSize');
        progRcas.uSharpness = gl.getUniformLocation(progRcas.prog, 'uSharpness');
        progRcas.uSuperRes = gl.getUniformLocation(progRcas.prog, 'uSuperRes');
        progRcas.uLookMode = gl.getUniformLocation(progRcas.prog, 'uLookMode');

        vao = gl.createVertexArray();
        gl.bindVertexArray(vao);
        var vbo = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

        texLumaHi[0] = createTex2D(FLOW_HI_W, FLOW_HI_H);
        texLumaHi[1] = createTex2D(FLOW_HI_W, FLOW_HI_H);
        fboLumaHi[0] = createFbo(texLumaHi[0]);
        fboLumaHi[1] = createFbo(texLumaHi[1]);
        texLumaHiScratch = createTex2D(FLOW_HI_W, FLOW_HI_H);
        fboLumaHiScratch = createFbo(texLumaHiScratch);

        texLumaLo[0] = createTex2D(FLOW_LO_W, FLOW_LO_H);
        texLumaLo[1] = createTex2D(FLOW_LO_W, FLOW_LO_H);
        fboLumaLo[0] = createFbo(texLumaLo[0]);
        fboLumaLo[1] = createFbo(texLumaLo[1]);

        texDiff = createTex2D(DIFF_W, DIFF_H);
        fboDiff = createFbo(texDiff);
        texFlowCoarse = createTex2D(FLOW_LO_W, FLOW_LO_H);
        fboFlowCoarse = createFbo(texFlowCoarse);
        texFlowFine = createTex2D(FLOW_HI_W, FLOW_HI_H);
        fboFlowFine = createFbo(texFlowFine);
        texFlowSmooth = createTex2D(FLOW_HI_W, FLOW_HI_H);
        fboFlowSmooth = createFbo(texFlowSmooth);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);

        pboSupported = (typeof gl.PIXEL_PACK_BUFFER !== 'undefined' &&
          typeof gl.fenceSync === 'function' &&
          typeof gl.clientWaitSync === 'function' &&
          typeof gl.getBufferSubData === 'function');
        if (pboSupported) {
          for (var p = 0; p < 2; p++) {
            pboRing[p] = gl.createBuffer();
            gl.bindBuffer(gl.PIXEL_PACK_BUFFER, pboRing[p]);
            gl.bufferData(gl.PIXEL_PACK_BUFFER, DIFF_W * DIFF_H * 4, gl.STREAM_READ);
          }
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
        }
        resetPboState();
        B.log('WebGL2 Adreno GPU pipeline v3 initialized (80x45->320x180 pyramid, PBO=' + pboSupported + ')');
      } catch (e) {
        glFailed = true;
        gl = null;
        B.log('WebGL2 init error: ' + e.message);
        return false;
      }
    }
    if (w > 0 && h > 0 && (w !== glW || h !== glH)) {
      glW = w;
      glH = h;
      for (var i = 0; i < 3; i++) {
        if (texRing[i]) gl.deleteTexture(texRing[i]);
        texRing[i] = createTex2D(w, h);
      }
      if (texMid) gl.deleteTexture(texMid);
      if (fboMid) gl.deleteFramebuffer(fboMid);
      texMid = createTex2D(w, h);
      fboMid = createFbo(texMid);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      hasUniqueHistory = 0;
      consecDupes = 0;
      resetPboState();
      B.log('WebGL2 video ring + fboMid allocated: 4x ' + w + 'x' + h);
    }
    var useSr = (enhanceMode === MODE_SUPERRES || enhanceMode === MODE_FG60_SR);
    var dpr = (typeof window !== 'undefined' && window.devicePixelRatio) ? window.devicePixelRatio : 1;
    var domW = (canvas && canvas.clientWidth) ? Math.round(canvas.clientWidth * dpr) : 0;
    var domH = (canvas && canvas.clientHeight) ? Math.round(canvas.clientHeight * dpr) : 0;
    var targetCW = useSr ? Math.min(3200, domW >= w ? domW : Math.round(w * 1.25)) : w;
    var targetCH = useSr ? Math.min(1800, domH >= h ? domH : Math.round(h * 1.25)) : h;
    if (targetCW > 0 && targetCH > 0 && (targetCW !== canvasW || targetCH !== canvasH)) {
      canvasW = targetCW;
      canvasH = targetCH;
      canvas.width = canvasW;
      canvas.height = canvasH;
      B.log('WebGL2 output canvas size: ' + canvasW + 'x' + canvasH + ' (' + MODE_NAMES[enhanceMode] + ')');
    }
    return glW > 0 && glH > 0;
  }

  function syncEnhanceDom() {
    if (!video || !canvas) return;
    if (enhanceMode === MODE_OFF || glFailed) {
      if (canvas.style) canvas.style.display = 'none';
      if (video.classList) video.classList.remove('gl-hidden');
    } else {
      if (canvas.style) canvas.style.display = 'block';
      if (video.classList) video.classList.add('gl-hidden');
    }
  }

  function cancelPendingRealFrame() {
    pendingRealFrame = false;
    if (realFrameTimerId) {
      clearTimeout(realFrameTimerId);
      realFrameTimerId = 0;
    }
  }

  function renderRcasToCanvas(srcTex, isGenerated) {
    if (!gl || !glW || !glH || !canvasW || !canvasH) return;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, canvasW, canvasH);
    gl.useProgram(progRcas.prog);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, srcTex);
    gl.uniform1i(progRcas.uInputTex, 0);
    gl.uniform2f(progRcas.uTexelSize, 1.0 / glW, 1.0 / glH);
    var useSr = (enhanceMode === MODE_SUPERRES || enhanceMode === MODE_FG60_SR);
    var sharp = useSr ? 0.18 : rcasSharpness;
    gl.uniform1f(progRcas.uSharpness, sharp);
    gl.uniform1f(progRcas.uSuperRes, useSr ? 1.0 : 0.0);
    gl.uniform1f(progRcas.uLookMode, lookMode * 1.0);
    gl.bindVertexArray(vao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    statOutFrames++;
    if (isGenerated) statFgFrames++;
  }

  function processVideoFrameGpu() {
    if (!video || enhanceMode === MODE_OFF) return;
    var vw = video.videoWidth || 0;
    var vh = video.videoHeight || 0;
    if (!vw || !vh || !ensureGl(vw, vh)) {
      syncEnhanceDom();
      return;
    }
    syncEnhanceDom();
    var t0 = nowMs();
    cancelPendingRealFrame();

    // Fast path for RCAS and SUPERRES: zero gl.readPixels CPU-GPU stall, <0.6 ms pure GPU pass!
    if (enhanceMode === MODE_RCAS || enhanceMode === MODE_SUPERRES) {
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, texRing[currUniqueSlot]);
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, video);
      renderRcasToCanvas(texRing[currUniqueSlot], false);
      statUniqueFrames++;
      var dtFast = nowMs() - t0;
      statGpuMsSum += dtFast;
      statGpuSamples++;
      return;
    }

    // FG60 / FG60+SR path:
    // 1. Poll any finished PBO readback from the previous frame before writing new GPU work.
    if (pboSupported && pboSyncFrames === 0) {
      var readIdx = 1 - pboWriteIdx;
      if (pboValid[readIdx] && pboFences[readIdx]) {
        var waitStatus = gl.clientWaitSync(pboFences[readIdx], 0, 0);
        if (waitStatus === gl.ALREADY_SIGNALED || waitStatus === gl.CONDITION_SATISFIED) {
          pboMissedPolls = 0;
          gl.deleteSync(pboFences[readIdx]);
          pboFences[readIdx] = null;
          pboValid[readIdx] = false;
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, pboRing[readIdx]);
          gl.getBufferSubData(gl.PIXEL_PACK_BUFFER, 0, diffPixels);
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
          var prevStats = analyzeDiffPixels(diffPixels);
          lastMad = prevStats.mad;
          var actualPrevDupe = classifyDupeCadence(
            prevStats.mad, prevStats.maxBlock, pboVerifiedPrevMad, pboVerifiedConsecDupes, pboVerifiedHasHistory);
          pboVerifiedPrevMad = prevStats.mad;
          pboVerifiedConsecDupes = actualPrevDupe ? (pboVerifiedConsecDupes + 1) : 0;
          pboVerifiedHasHistory = Math.min(2, pboVerifiedHasHistory + 1);
          statPboReads++;
          if (pboUsedForPrediction[readIdx] && actualPrevDupe !== pboPredictedDupe[readIdx]) {
            pboSyncFrames = 2;
            consecUniqueRun = 0;
          }
        } else {
          pboMissedPolls++;
          if (pboMissedPolls > 3) {
            pboMissedPolls = 0;
            pboSyncFrames = 2;
          }
        }
      }
    }

    // 2. Upload incoming <video> frame into the scratch slot of our 3-texture ring.
    var scratchSlot = 3 - currUniqueSlot - prevUniqueSlot;
    if (scratchSlot < 0 || scratchSlot > 2) scratchSlot = 2;
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texRing[scratchSlot]);
    gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, video);

    // 3. Render 320x180 Luma + AbsDiff against currUnique's luma.
    gl.bindFramebuffer(gl.FRAMEBUFFER, fboLumaHiScratch);
    gl.viewport(0, 0, FLOW_HI_W, FLOW_HI_H);
    gl.useProgram(progLuma.prog);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texRing[scratchSlot]);
    gl.uniform1i(progLuma.uVideoTex, 0);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, texLumaHi[currLumaIdx]);
    gl.uniform1i(progLuma.uPrevLumaTex, 1);
    gl.bindVertexArray(vao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

    // 4. Reduce 320x180 AbsDiff into 4x4 regional summary.
    gl.bindFramebuffer(gl.FRAMEBUFFER, fboDiff);
    gl.viewport(0, 0, DIFF_W, DIFF_H);
    gl.useProgram(progDiff.prog);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texLumaHiScratch);
    gl.uniform1i(progDiff.uLumaScratchTex, 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

    // 5. Determine whether this frame is a duplicate (async PBO phase-lock or sync resync).
    var isDupe = false;
    if (!pboSupported || pboSyncFrames > 0) {
      gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
      gl.readPixels(0, 0, DIFF_W, DIFF_H, gl.RGBA, gl.UNSIGNED_BYTE, diffPixels);
      var syncStats = analyzeDiffPixels(diffPixels);
      lastMad = syncStats.mad;
      isDupe = classifyDupeCadence(syncStats.mad, syncStats.maxBlock, prevFrameMad, consecDupes, hasUniqueHistory);
      prevFrameMad = syncStats.mad;
      pboVerifiedPrevMad = syncStats.mad;
      pboVerifiedConsecDupes = isDupe ? 1 : 0;
      pboVerifiedHasHistory = Math.min(2, pboVerifiedHasHistory + 1);
      if (isDupe) consecUniqueRun = 0;
      else consecUniqueRun++;
      if (pboSupported && pboSyncFrames > 0) {
        pboSyncFrames--;
        if (pboSyncFrames === 0) {
          pboExpectedDupe = !isDupe && (fgActiveGate || consecUniqueRun < 2);
          pboValid[0] = false;
          pboValid[1] = false;
        }
      }
    } else {
      isDupe = pboExpectedDupe;
      if (isDupe) consecUniqueRun = 0;
      else consecUniqueRun++;

      if (pboFences[pboWriteIdx]) {
        try { gl.deleteSync(pboFences[pboWriteIdx]); } catch (e) {}
        pboFences[pboWriteIdx] = null;
      }
      gl.bindBuffer(gl.PIXEL_PACK_BUFFER, pboRing[pboWriteIdx]);
      gl.readPixels(0, 0, DIFF_W, DIFF_H, gl.RGBA, gl.UNSIGNED_BYTE, 0);
      gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
      pboFences[pboWriteIdx] = gl.fenceSync(gl.SYNC_GPU_COMMANDS_COMPLETE, 0);
      pboPredictedDupe[pboWriteIdx] = isDupe;
      pboUsedForPrediction[pboWriteIdx] = true;
      pboValid[pboWriteIdx] = true;
      pboWriteIdx = 1 - pboWriteIdx;
      pboExpectedDupe = !isDupe && (fgActiveGate || consecUniqueRun < 2);
    }

    if (isDupe) {
      consecDupes++;
      statDupeFrames++;
      if (!realFramePresented) {
        realFramePresented = true;
        renderRcasToCanvas(texRing[currUniqueSlot], false);
      }
    } else {
      consecDupes = 0;
      statUniqueFrames++;
      realFramePresented = false;
      prevUniqueSlot = currUniqueSlot;
      currUniqueSlot = scratchSlot;
      prevLumaIdx = currLumaIdx;
      currLumaIdx = 1 - currLumaIdx;

      var tmpTex = texLumaHi[currLumaIdx];
      var tmpFbo = fboLumaHi[currLumaIdx];
      texLumaHi[currLumaIdx] = texLumaHiScratch;
      fboLumaHi[currLumaIdx] = fboLumaHiScratch;
      texLumaHiScratch = tmpTex;
      fboLumaHiScratch = tmpFbo;

      // Keep 80x45 anti-aliased coarse luma in sync for every unique frame.
      gl.bindFramebuffer(gl.FRAMEBUFFER, fboLumaLo[currLumaIdx]);
      gl.viewport(0, 0, FLOW_LO_W, FLOW_LO_H);
      gl.useProgram(progDownsample.prog);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, texLumaHi[currLumaIdx]);
      gl.uniform1i(progDownsample.uHiLumaTex, 0);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

      hasUniqueHistory = Math.min(2, hasUniqueHistory + 1);

      var wantFlow = (enhanceMode === MODE_FG60 || enhanceMode === MODE_FG60_SR) && fgActiveGate && hasUniqueHistory >= 2;
      if (wantFlow) {
        // Pass 3a: Level-0 Coarse Optical Flow (80x45)
        gl.bindFramebuffer(gl.FRAMEBUFFER, fboFlowCoarse);
        gl.viewport(0, 0, FLOW_LO_W, FLOW_LO_H);
        gl.useProgram(progFlowCoarse.prog);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, texLumaLo[prevLumaIdx]);
        gl.uniform1i(progFlowCoarse.uPrevLumaLo, 0);
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, texLumaLo[currLumaIdx]);
        gl.uniform1i(progFlowCoarse.uCurrLumaLo, 1);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

        // Pass 3b: Level-1 Fine Sub-Pixel Flow + Bidirectional Consistency Check (320x180)
        gl.bindFramebuffer(gl.FRAMEBUFFER, fboFlowFine);
        gl.viewport(0, 0, FLOW_HI_W, FLOW_HI_H);
        gl.useProgram(progFlowFine.prog);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, texLumaHi[prevLumaIdx]);
        gl.uniform1i(progFlowFine.uPrevLumaHi, 0);
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, texLumaHi[currLumaIdx]);
        gl.uniform1i(progFlowFine.uCurrLumaHi, 1);
        gl.activeTexture(gl.TEXTURE2);
        gl.bindTexture(gl.TEXTURE_2D, texFlowCoarse);
        gl.uniform1i(progFlowFine.uCoarseFlow, 2);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

        // Pass 3c: 3x3 Bilateral Edge-Preserving Flow Smoothing & Outlier Rejection (320x180)
        gl.bindFramebuffer(gl.FRAMEBUFFER, fboFlowSmooth);
        gl.viewport(0, 0, FLOW_HI_W, FLOW_HI_H);
        gl.useProgram(progFlowSmooth.prog);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, texFlowFine);
        gl.uniform1i(progFlowSmooth.uRawFlowTex, 0);
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, texLumaHi[currLumaIdx]);
        gl.uniform1i(progFlowSmooth.uCurrLuma, 1);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

        // Pass 4a: Render smooth-weighted midpoint warp into fboMid (2560x1440)
        gl.bindFramebuffer(gl.FRAMEBUFFER, fboMid);
        gl.viewport(0, 0, glW, glH);
        gl.useProgram(progWarp.prog);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, texRing[prevUniqueSlot]);
        gl.uniform1i(progWarp.uPrevTex, 0);
        gl.activeTexture(gl.TEXTURE1);
        gl.bindTexture(gl.TEXTURE_2D, texRing[currUniqueSlot]);
        gl.uniform1i(progWarp.uCurrTex, 1);
        gl.activeTexture(gl.TEXTURE2);
        gl.bindTexture(gl.TEXTURE_2D, texFlowSmooth);
        gl.uniform1i(progWarp.uFlowTex, 2);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

        // Pass 4b: Run true 1-pixel AMD FidelityFX RCAS (+ optional SuperRes) on fboMid -> Canvas
        renderRcasToCanvas(texMid, true);

        pendingRealFrame = true;
        realFrameTimerId = setTimeout(function () {
          realFrameTimerId = 0;
          if (!pendingRealFrame || realFramePresented || !pc) return;
          pendingRealFrame = false;
          realFramePresented = true;
          renderRcasToCanvas(texRing[currUniqueSlot], false);
        }, 17);
      } else {
        realFramePresented = true;
        renderRcasToCanvas(texRing[currUniqueSlot], false);
      }
    }

    var dtGpu = nowMs() - t0;
    statGpuMsSum += dtGpu;
    statGpuSamples++;
  }

  // ---------------------------------------------------------------- Signaling & Handshake

  function maybeHandshake() {
    if (handshakeSent) return;
    var names = ['chat', 'control', 'input', 'message'];
    for (var i = 0; i < names.length; i++) {
      var ch = channels[names[i]];
      if (!ch || ch.readyState !== 'open') return;
    }
    handshakeSent = true;
    log('data channels open; handshake');
    sendText(channels.message, { type: 'Handshake', version: 'messageV1', id: HANDSHAKE_ID, cv: '0' });
  }

  function onHandshakeAck() {
    sendText(channels.control, { message: 'authorizationRequest', accessKey: ACCESS_KEY });
    // Pad 0 is announced for the whole session and never removed mid-stream. The console treats a
    // session without a pad as having no client and kicks it with KickForNoClientConnection ~60 s
    // after streaming starts (measured 2026-11-21 with two builds that skipped or released the pad).
    sendText(channels.control, { message: 'gamepadChanged', gamepadIndex: 0, wasAdded: true });
    sendText(channels.control, { message: 'gamepadChanged', gamepadIndex: 0, wasAdded: false });
    sendText(channels.control, { message: 'gamepadChanged', gamepadIndex: 0, wasAdded: true });
    padPresent = true;
    localPadPresent = false;
    lastPadPresenceAt = nowMs();
    sendBin(channels.input, clientMetadata());

    sendMessage('/streaming/systemUi/configuration', { version: [0, 2, 0], systemUis: [] });
    sendMessage('/streaming/properties/clientappinstallidchanged',
      { clientAppInstallId: 'c97d7ee0-73b2-4239-bf1d-9d805a338429' });
    sendMessage('/streaming/characteristics/orientationchanged', { orientation: 0 });
    sendMessage('/streaming/characteristics/touchinputenabledchanged', { touchInputEnabled: false });
    sendMessage('/streaming/characteristics/clientdevicecapabilities', {});
    sendMessage('/streaming/characteristics/dimensionschanged', {
      horizontal: 1920, vertical: 1080,
      preferredWidth: 1920, preferredHeight: 1080,
      safeAreaLeft: 0, safeAreaTop: 0, safeAreaRight: 1920, safeAreaBottom: 1080,
      supportsCustomResolution: false
    });
    sendText(channels.control, { message: 'videoKeyframeRequested', ifrRequested: true });

    ready = true;
    log('direct: streaming');
    B.onState('streaming');
    if (keepaliveTimer) clearInterval(keepaliveTimer);
    if (keepaliveResetTimer) { clearTimeout(keepaliveResetTimer); keepaliveResetTimer = null; }
    keepaliveTimer = setInterval(function () {
      if (!ready || !channels.input) return;
      sendMessage('/streaming/sessionLifetimeManagement/clientKeepAlive', {});
      // Only send the sub-deadzone stick pulse when a headset pad is active (padPresent). When
      // the physical controller is paired directly to the Xbox, sending ReportGamepad on index 0
      // steals Player 1 focus from the Xbox-paired controller.
      if (!padPresent) return;
      var p = lastPad || { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
      if (nowMs() - lastUserPadAt > 12000 && p.lx === 0 && p.ly === 0) {
        keepaliveJiggle = -keepaliveJiggle;
        sendBin(channels.input, writeGamepadReport(p.buttons, keepaliveJiggle, p.ly, p.rx, p.ry, p.lt, p.rt));
        if (keepaliveResetTimer) clearTimeout(keepaliveResetTimer);
        keepaliveResetTimer = setTimeout(function () {
          keepaliveResetTimer = null;
          if (!ready || !channels.input || !padPresent) return;
          var cur = lastPad || { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
          sendBin(channels.input, gamepadReport(cur));
        }, 60);
      } else {
        sendBin(channels.input, gamepadReport(p));
      }
    }, 15000);
  }

  function onMessageChannel(ev) {
    var text;
    if (typeof ev.data === 'string') text = ev.data;
    else text = new TextDecoder().decode(ev.data);
    var j;
    try { j = JSON.parse(text); } catch (e) { return; }
    if (j.type === 'HandshakeAck') { onHandshakeAck(); return; }
    if (j.type === 'TransactionStart' || j.type === 'Message') {
      var id = j.id || '';
      if (j.target === '/streaming/sessionLifetimeManagement/serverInitiatedDisconnect') {
        var content = null;
        try { content = JSON.parse(j.content || '""'); } catch (e) {}
        sendText(channels.message, { type: 'TransactionComplete', content: '""', id: id, cv: '' });
        if (content && content.reason === 'WarningForBeingIdle') {
          sendMessage('/streaming/sessionLifetimeManagement/clientKeepAlive', {});
          if (channels.input && padPresent) {
            var p = lastPad || { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
            keepaliveJiggle = -keepaliveJiggle;
            var nudge = p.lx || (keepaliveJiggle * 2);
            sendBin(channels.input, writeGamepadReport(p.buttons, nudge, p.ly, p.rx, p.ry, p.lt, p.rt));
            if (keepaliveResetTimer) clearTimeout(keepaliveResetTimer);
            keepaliveResetTimer = setTimeout(function () {
              keepaliveResetTimer = null;
              if (!ready || !channels.input || !padPresent) return;
              var cur = lastPad || { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
              sendBin(channels.input, gamepadReport(cur));
            }, 80);
          }
          B.log('idle warning acknowledged; sent clientKeepAlive');
          return;
        }
        fail('The console ended the session' + (content && content.reason ? ' (' + content.reason + ')' : ''));
        return;
      }
      if (j.target === '/streaming/systemUi/messages/ShowMessageDialog') {
        sendText(channels.message, { type: 'TransactionComplete', content: JSON.stringify({ Result: 0 }), id: id, cv: '' });
        return;
      }
      if (j.type === 'TransactionStart') {
        sendMessageComplete(id);
      }
    }
  }

  function sendMessageComplete(id) {
    sendText(channels.message, { type: 'TransactionComplete', content: '""', id: id, cv: '' });
  }

  function onInputChannel(ev) {
    try {
      var dv = new DataView(ev.data);
      var type = dv.getUint16(0, true);
      if (type === 128 && dv.byteLength >= 13) {
        var leftMotor = dv.getUint8(4);
        var rightMotor = dv.getUint8(5);
        var leftTrigger = dv.getUint8(6);
        var rightTrigger = dv.getUint8(7);
        var durationMs = dv.getUint16(8, true);
        var isZero = (leftMotor === 0 && rightMotor === 0 && leftTrigger === 0 && rightTrigger === 0);
        if (isZero && lastRumbleZero) return;
        lastRumbleZero = isZero;
        if (typeof B.onRumble === 'function') {
          B.onRumble(leftMotor, rightMotor, leftTrigger, rightTrigger, durationMs);
        }
        return;
      }
      if (type !== 128 && type !== 16) B.log('input channel: type ' + type + ' (' + dv.byteLength + ' bytes)');
    } catch (e) {}
  }

  function postLocalDescription() {
    var sdp = pc.localDescription.sdp;
    var lines = sdp.split(/\r?\n/);
    var mid = '0';
    var mline = 0;
    var candidates = [];
    var kept = [];
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (line.indexOf('m=') === 0) {
        mid = String(mline);
        mline++;
      } else if (line.indexOf('a=mid:') === 0) {
        mid = line.substring(6).trim();
      } else if (line.indexOf('a=candidate:') === 0) {
        var cand = line.substring(2);
        var parts = cand.split(' ');
        if (parts.length > 2 && parts[2].toLowerCase() !== 'udp') continue;
        parts[2] = 'UDP';
        if (localIp && parts.length > 5 && /\.local$/i.test(parts[4])) parts[4] = localIp;
        var typIdx = parts.indexOf('typ');
        if (typIdx > 0 && parts.length > typIdx + 2) {
          parts = parts.slice(0, typIdx + 2);
        }
        cand = parts.join(' ');
        candidates.push({ candidate: cand, sdpMid: mid, sdpMLineIndex: mline - 1 });
        continue;
      } else if (line.indexOf('a=end-of-candidates') === 0) {
        continue;
      }
      kept.push(line);
    }
    var clean = kept.join('\r\n') + '\r\n';
    var wire = conformOffer(clean, localIp);
    try {
      pc.getStats().then(function (stats) {
        stats.forEach(function (r) {
          if (r.type === 'local-candidate') {
            B.log('local-candidate ' + r.protocol + ' ' + r.address + ':' + r.port + ' ' + r.candidateType);
          }
        });
      }).catch(function () {});
    } catch (e) {}
    log('signaling (' + candidates.length + ' local candidates, ' + wire.split(/\r?\n/).length + ' lines)');
    try {
      if (window.GTA60Bridge && GTA60Bridge.saveDebug) GTA60Bridge.saveDebug('offer.sdp', wire);
    } catch (e) {}
    try {
      var codecs = wire.split(/\r?\n/).filter(function (l) {
        return l.indexOf('a=rtpmap:') === 0 || l.indexOf('a=fmtp:') === 0;
      });
      B.log('offer codecs: ' + codecs.join(' | '));
    } catch (e) {}
    if (!window.GTA60Bridge || typeof GTA60Bridge.exchange !== 'function') {
      fail('signaling bridge missing');
      return;
    }
    var result;
    try {
      result = JSON.parse(GTA60Bridge.exchange(JSON.stringify({ sdp: wire, candidates: candidates })));
    } catch (e) {
      fail('signaling failed: ' + e.message);
      return;
    }
    if (!result || !result.sdp) {
      fail('signaling failed: ' + ((result && result.error) || 'no answer'));
      return;
    }
    onAnswer(result.sdp, result.candidates || []);
  }

  function waitForGathering(ms) {
    return new Promise(function (resolve) {
      if (!pc || pc.iceGatheringState === 'complete') { resolve(); return; }
      var done = false;
      function finish() {
        if (done) return;
        done = true;
        pc.removeEventListener('icegatheringstatechange', changed);
        resolve();
      }
      function changed() {
        if (pc.iceGatheringState === 'complete') finish();
      }
      pc.addEventListener('icegatheringstatechange', changed);
      setTimeout(finish, ms);
    });
  }

  function conformAnswer(sdp) {
    var lines = sdp.split(/\r?\n/);
    var out = [];
    var inVideo = false;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (l.indexOf('m=') === 0) {
        inVideo = l.indexOf('m=video ') === 0;
        out.push(l);
        continue;
      }
      if (inVideo && l.indexOf('c=IN ') === 0) {
        out.push(l);
        out.push('b=AS:500000');
        continue;
      }
      if (inVideo && l.indexOf('a=ssrc:') === 0 && l.indexOf(' cname:') > 0) {
        out.push(l.replace(/cname:\S+/, 'cname:gta60video'));
        continue;
      }
      if (inVideo && l.indexOf('a=fmtp:') === 0 && l.indexOf('profile-level-id=') >= 0 &&
          l.indexOf('x-google-min-bitrate') < 0) {
        out.push(l + ';x-google-min-bitrate=25000;x-google-start-bitrate=50000;x-google-max-bitrate=500000');
        continue;
      }
      out.push(l);
    }
    return out.join('\r\n');
  }

  function configureReceiverHints(recv, kind) {
    if (!recv) return;
    try {
      if ('jitterBufferTarget' in recv) recv.jitterBufferTarget = 0;
      if (kind === 'audio' && 'playoutDelayHint' in recv) recv.playoutDelayHint = 0;
    } catch (e) {}
  }

  async function onAnswer(answerSdp, remoteCandidates) {
    if (!pc) return;
    try {
      if (window.GTA60Bridge && GTA60Bridge.saveDebug) GTA60Bridge.saveDebug('answer.sdp', answerSdp);
    } catch (e) {}
    var tunedAnswer = conformAnswer(answerSdp);
    try {
      await pc.setRemoteDescription({ type: 'answer', sdp: tunedAnswer });
    } catch (e) {
      fail('the console answer was rejected: ' + e.message);
      return;
    }
    pc.getReceivers().forEach(function (recv) {
      configureReceiverHints(recv, recv.track ? recv.track.kind : '');
    });
    var added = 0;
    for (var i = 0; i < remoteCandidates.length; i++) {
      var c = remoteCandidates[i];
      if (!c || !c.candidate) continue;
      try {
        await pc.addIceCandidate({
          candidate: c.candidate,
          sdpMid: c.sdpMid == null ? '0' : String(c.sdpMid),
          sdpMLineIndex: c.sdpMLineIndex == null ? 0 : c.sdpMLineIndex
        });
        added++;
      } catch (e) {
        B.log('candidate rejected: ' + e.message);
      }
    }
    log('answer applied (' + added + '/' + remoteCandidates.length + ' candidates)');
  }

  function scheduleVideoFeedback(forceRestart) {
    if (!video || typeof video.requestVideoFrameCallback !== 'function') return;
    if (forceRestart) {
      vfcRunning = false;
      vfcGen++;
    }
    if (vfcRunning) return;
    vfcRunning = true;
    var myGen = vfcGen;
    function onFrame(now, meta) {
      if (!pc || myGen !== vfcGen) {
        if (myGen === vfcGen) vfcRunning = false;
        return;
      }
      lastVfcAt = now;
      if (meta && meta.rtpTimestamp != null) {
        var rtp = meta.rtpTimestamp >>> 0;
        if (rtp !== lastRtpTs) {
          lastRtpTs = rtp;
          vfcFrameCount++;
          try {
            processVideoFrameGpu();
          } catch (e) {
            B.log('GPU frame error: ' + e.message);
          }
          if (ready && channels.input && channels.input.readyState === 'open' &&
              (vfcFrameCount <= 30 || (vfcFrameCount % 3) === 0)) {
            var t = Math.max(10, Math.round(now));
            var arr = meta.receiveTime ? Math.round(meta.receiveTime) : (t - 4);
            var sub = arr;
            var dec = sub + 2;
            var ren = dec + 1;
            sendBin(channels.input, videoFeedbackReport(rtp, arr, sub, dec, ren));
          }
        }
      }
      if (video && pc && myGen === vfcGen) video.requestVideoFrameCallback(onFrame);
      else if (myGen === vfcGen) vfcRunning = false;
    }
    video.requestVideoFrameCallback(onFrame);
  }

  async function start(cfg) {
    stopInternal();
    localIp = (cfg && cfg.localIp) || '';
    localPadPresent = (cfg && cfg.padPresent === true);
    if (cfg && typeof cfg.enhance === 'number') {
      enhanceMode = Math.max(0, Math.min(4, cfg.enhance | 0));
    }
    if (cfg && typeof cfg.look === 'number') {
      lookMode = Math.max(0, Math.min(2, cfg.look | 0));
    }
    if (errBox) { errBox.style.display = 'none'; errBox.textContent = ''; }
    video = document.getElementById('v');
    canvas = document.getElementById('c');
    if (canvas && !canvas._gta60CtxBound && typeof canvas.addEventListener === 'function') {
      canvas._gta60CtxBound = true;
      canvas.addEventListener('webglcontextlost', function (ev) {
        if (ev && ev.preventDefault) ev.preventDefault();
        B.log('WebGL2 context lost event; resetting GPU pipeline');
        resetGlForContextLoss();
      }, false);
      canvas.addEventListener('webglcontextrestored', function () {
        B.log('WebGL2 context restored; restarting frame loop');
        if (pc && ready) scheduleVideoFeedback(true);
      }, false);
    }
    audio = document.getElementById('a');
    hud = document.getElementById('hud');
    errBox = document.getElementById('err');
    syncEnhanceDom();

    if (!window.RTCPeerConnection) {
      fail('This WebView has no WebRTC support.');
      return;
    }

    pc = new RTCPeerConnection({ bundlePolicy: 'max-bundle' });

    pc.ontrack = function (ev) {
      try {
        if (ev.receiver) {
          configureReceiverHints(ev.receiver, ev.track.kind);
          B.log('ontrack ' + ev.track.kind + ': playoutDelayHint=' + ev.receiver.playoutDelayHint
            + ' jitterBufferTarget=' + ev.receiver.jitterBufferTarget);
        }
        if (ev.track.kind === 'audio') {
          if (audio) {
            remoteAudioStream = (ev.streams && ev.streams[0]) ? ev.streams[0] : new MediaStream([ev.track]);
            audio.srcObject = remoteAudioStream;
            var ap = audio.play();
            if (ap && ap.then) {
              ap.then(function () { B.log('audio.play ok (paused=' + audio.paused + ', muted=' + audio.muted + ')'); })
                .catch(function (e) { B.log('audio.play rejected: ' + e.message); });
            }
          }
          return;
        }
        video.muted = true;
        remoteVideoStream = (ev.streams && ev.streams[0]) ? ev.streams[0] : new MediaStream([ev.track]);
        video.srcObject = remoteVideoStream;
        scheduleVideoFeedback(false);
        var vp = video.play();
        if (vp && vp.then) {
          vp.then(function () { B.log('video.play ok'); })
            .catch(function (e) { B.log('video.play rejected: ' + e.message); });
        }
      } catch (e) {
        fail('cannot attach media track: ' + e.message);
      }
    };

    pc.onconnectionstatechange = function () {
      if (!pc) return;
      B.onState('webrtc:' + pc.connectionState);
      if (pc.connectionState === 'connected') {
        if (disconnectTimer) { clearTimeout(disconnectTimer); disconnectTimer = null; }
        return;
      }
      if (pc.connectionState === 'disconnected' && !disconnectTimer) {
        // A console going to sleep and a Wi-Fi blip both land here. Chromium can still recover, so
        // wait 20 s before declaring the session dead (which releases it on the console).
        disconnectTimer = setTimeout(function () {
          disconnectTimer = null;
          if (pc && pc.connectionState === 'disconnected') {
            fail('The console stopped responding (sleep or network drop). Press Start direct to resume.');
          }
        }, 20000);
        return;
      }
      if (pc.connectionState === 'failed') fail('WebRTC connection failed (no ICE path to the console)');
    };

    var audioTx = pc.addTransceiver('audio', { direction: 'sendrecv' });
    var videoTx = pc.addTransceiver('video', { direction: 'recvonly' });
    if (audioTx) {
      var aprefs = audioCodecPrefs();
      if (aprefs) {
        try { audioTx.setCodecPreferences(aprefs); } catch (e) { B.log('audio codec preferences rejected: ' + e.message); }
      }
    }
    var prefs = videoCodecPrefs();
    if (prefs) {
      try { videoTx.setCodecPreferences(prefs); } catch (e) { B.log('codec preferences rejected: ' + e.message); }
    }
    pc.getReceivers().forEach(function (recv) {
      configureReceiverHints(recv, recv.track ? recv.track.kind : '');
    });

    channels.chat = pc.createDataChannel('chat', { protocol: 'chatV1', ordered: true });
    channels.control = pc.createDataChannel('control', { protocol: 'controlV1', ordered: true });
    channels.input = pc.createDataChannel('input', { protocol: '1.0', ordered: true });
    channels.message = pc.createDataChannel('message', { protocol: 'messageV1', ordered: true });
    Object.keys(channels).forEach(function (k) {
      channels[k].binaryType = 'arraybuffer';
      channels[k].onopen = maybeHandshake;
    });
    channels.message.onmessage = onMessageChannel;
    channels.input.onmessage = onInputChannel;

    var offer = await pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
    var edited = raiseH264Level(offer.sdp).replace('useinbandfec=1', 'useinbandfec=1;stereo=1');
    await pc.setLocalDescription({ type: 'offer', sdp: edited });
    await waitForGathering(3000);
    postLocalDescription();

    if (statsTimer) clearInterval(statsTimer);
    statsTimer = setInterval(updateStats, 1000);
  }

  var lastJbDelay = 0;
  var lastJbTargetDelay = 0;
  var lastJbMinDelay = 0;
  var lastJbCount = 0;
  var lastDecTime = 0;

  function updateStats() {
    if (!pc) return;
    pc.getStats().then(function (stats) {
      var inbound = null;
      var audioIn = null;
      var rttMs = 0;
      stats.forEach(function (r) {
        if (r.type === 'inbound-rtp' && (r.kind === 'video' || r.mediaType === 'video')) inbound = r;
        if (r.type === 'inbound-rtp' && (r.kind === 'audio' || r.mediaType === 'audio')) audioIn = r;
        if (r.type === 'candidate-pair' && r.state === 'succeeded' && r.currentRoundTripTime != null) {
          rttMs = r.currentRoundTripTime * 1000;
        }
      });
      if (!inbound) return;
      var t = performance.now();
      var bytes = inbound.bytesReceived || 0;
      var frames = inbound.framesDecoded || 0;
      var dt = lastStatsAt ? (t - lastStatsAt) / 1000 : 0;
      var mbps = dt > 0 ? ((bytes - lastBytes) * 8) / dt / 1e6 : 0;
      var fps = dt > 0 ? (frames - lastFrames) / dt : (inbound.framesPerSecond || 0);
      var dFrames = frames - lastFrames;
      var decTime = inbound.totalDecodeTime || 0;
      var decMs = dFrames > 0 ? ((decTime - lastDecTime) / dFrames) * 1000 : 0;
      lastDecTime = decTime;
      lastBytes = bytes;
      lastFrames = frames;
      lastStatsAt = t;

      if (ready && dFrames > 0 && t - lastVfcAt > 500 && channels.input && channels.input.readyState === 'open') {
        var nowR = Math.max(10, Math.round(t));
        lastRtpTs = (lastRtpTs + 3000) >>> 0;
        sendBin(channels.input, videoFeedbackReport(lastRtpTs, nowR - 4, nowR - 4, nowR - 2, nowR - 1));
      }
      if (ready && dFrames > 0 && t - lastVfcAt > 1500 && (!document || !document.hidden) && enhanceMode !== MODE_OFF) {
        scheduleVideoFeedback(true);
      }

      var jbDelay = inbound.jitterBufferDelay || 0;
      var jbTargetDelay = inbound.jitterBufferTargetDelay || 0;
      var jbMinDelay = inbound.jitterBufferMinimumDelay || 0;
      var jbCount = inbound.jitterBufferEmittedCount || 0;
      var dJbCount = jbCount - lastJbCount;
      var jb = dJbCount > 0 ? ((jbDelay - lastJbDelay) / dJbCount) * 1000 : 0;
      var jbTarget = dJbCount > 0 ? ((jbTargetDelay - lastJbTargetDelay) / dJbCount) * 1000 : -1;
      var jbMin = dJbCount > 0 ? ((jbMinDelay - lastJbMinDelay) / dJbCount) * 1000 : -1;
      lastJbDelay = jbDelay;
      lastJbTargetDelay = jbTargetDelay;
      lastJbMinDelay = jbMinDelay;
      lastJbCount = jbCount;

      var lost = inbound.packetsLost || 0;
      var dropped = inbound.framesDropped || 0;
      var vw = (inbound.frameWidth || (video && video.videoWidth) || 0);
      var vh = (inbound.frameHeight || (video && video.videoHeight) || 0);
      var res = (vw && vh) ? (vw + 'x' + vh) : '?x?';
      var audSamples = audioIn ? (audioIn.totalSamplesReceived || 0) : -1;
      var audPkts = audioIn ? (audioIn.packetsReceived || 0) : -1;

      var uniqFps = dt > 0 && statUniqueFrames > 0 ? (statUniqueFrames / dt) : fps;
      var outFps = dt > 0 && statOutFrames > 0 ? (statOutFrames / dt) : fps;
      var fgFps = dt > 0 ? (statFgFrames / dt) : 0;
      var gpuMs = statGpuSamples > 0 ? (statGpuMsSum / statGpuSamples) : 0;
      var totalAnalyzed = statUniqueFrames + statDupeFrames;
      var dupRatio = totalAnalyzed > 0 ? (statDupeFrames / totalAnalyzed) : 0;
      var pboActive = statPboReads > 0 ? 1 : 0;

      var isFgMode = (enhanceMode === MODE_FG60 || enhanceMode === MODE_FG60_SR);
      if (totalAnalyzed >= 15 && isFgMode) {
        if (dupRatio >= 0.20 && uniqFps <= 46.5) fgActiveGate = true;
        else if (dupRatio < 0.10 && uniqFps >= 50.0) fgActiveGate = false;
      }
      statUniqueFrames = 0;
      statDupeFrames = 0;
      statOutFrames = 0;
      statFgFrames = 0;
      statPboReads = 0;
      statGpuMsSum = 0;
      statGpuSamples = 0;

      var modeTag = MODE_NAMES[enhanceMode] || 'OFF';
      if (isFgMode && !fgActiveGate) {
        modeTag += '-1x';
      }

      B.log('perf: ' + res + ' ' + fps.toFixed(1) + 'fps uniq=' + uniqFps.toFixed(1)
        + 'fps out=' + outFps.toFixed(1) + 'fps(fg=' + fgFps.toFixed(1)
        + ',dup=' + Math.round(dupRatio * 100) + '%,mad=' + lastMad.toFixed(1)
        + ',pbo=' + pboActive + ',gpu=' + gpuMs.toFixed(2) + 'ms,mode=' + modeTag
        + ',look=' + (LOOK_NAMES[lookMode] || 'Normal') + ') '
        + mbps.toFixed(1) + 'Mbps dec=' + decMs.toFixed(2)
        + 'ms jb=' + jb.toFixed(1) + 'ms(target=' + jbTarget.toFixed(1) + ',min=' + jbMin.toFixed(1)
        + ') rtt=' + rttMs.toFixed(1) + 'ms lost=' + lost + ' drop=' + dropped + ' aud=' + audPkts + '/' + audSamples);

      if (hud && hudVisible) {
        hud.textContent = 'direct ' + res + '  uniq ' + uniqFps.toFixed(0) + '->' + outFps.toFixed(0) + ' fps [' + modeTag + ']'
          + '  ' + mbps.toFixed(1) + ' Mbps  dec ' + decMs.toFixed(1) + 'ms  gpu ' + gpuMs.toFixed(1)
          + 'ms  jb ' + jb.toFixed(1) + 'ms  rtt ' + rttMs.toFixed(1) + 'ms';
      }
      B.onState('stats:' + outFps.toFixed(1) + ':' + mbps.toFixed(1) + ':' + jb.toFixed(1)
        + ':' + res + ':' + rttMs.toFixed(1) + ':' + uniqFps.toFixed(0) + ':' + modeTag + ':' + gpuMs.toFixed(1));
    }).catch(function () {});
  }

  function pad(state) {
    if (!state) return;
    p(state.buttons || 0, state.lx || 0, state.ly || 0, state.rx || 0, state.ry || 0, state.lt || 0, state.rt || 0);
  }

  function p(buttons, lx, ly, rx, ry, lt, rt) {
    lastPad.buttons = buttons;
    lastPad.lx = lx;
    lastPad.ly = ly;
    lastPad.rx = rx;
    lastPad.ry = ry;
    lastPad.lt = lt;
    lastPad.rt = rt;
    lastUserPadAt = nowMs();
    if (!ready || !channels.input) return;
    // A local button/stick edge is proof the headset has a pad, even if the platform's device
    // listener missed the reconnect: re-announce instead of silently dropping the report.
    if (!localPadPresent) setPadPresent(true);
    sendBin(channels.input, writeGamepadReport(buttons, lx, ly, rx, ry, lt, rt));
  }

  function setEnhance(mode) {
    enhanceMode = Math.max(0, Math.min(4, mode | 0));
    cancelPendingRealFrame();
    hasUniqueHistory = 0;
    consecDupes = 0;
    resetPboState();
    syncEnhanceDom();
    if (gl && glW > 0 && glH > 0) {
      ensureGl(glW, glH);
    }
    B.log('enhance mode set to ' + MODE_NAMES[enhanceMode] + ' (' + enhanceMode + ')');
    return MODE_NAMES[enhanceMode];
  }

  function setLook(mode) {
    lookMode = Math.max(0, Math.min(2, mode | 0));
    B.log('look mode set to ' + LOOK_NAMES[lookMode] + ' (' + lookMode + ')');
    return LOOK_NAMES[lookMode];
  }

  function setHudVisible(vis) {
    hudVisible = !!vis;
    if (hud && hud.style) hud.style.display = hudVisible ? 'block' : 'none';
  }

  /**
   * Re-kick AAudio and requestVideoFrameCallback/WebGL2 after Quest standby/wake. Horizon OS
   * can leave a backgrounded WebView's AAudio player stuck in state:stopped/mutedState:appOps and
   * stall rVFC while the panel was hidden; detaching/re-attaching srcObject forces a fresh track.
   */
  function onResume() {
    if (!pc || !ready) return 'idle';
    var t = nowMs();
    if (lastResumeBounceAt > 0 && (t - lastResumeBounceAt < 250)) return 'debounced';
    lastResumeBounceAt = t;

    if (audio && remoteAudioStream) {
      if (resumeAudioTimer && typeof clearTimeout === 'function') {
        clearTimeout(resumeAudioTimer);
      }
      resumeAudioTimer = 0;
      try { audio.pause(); } catch (e) {}
      try { audio.srcObject = null; } catch (e) {}
      resumeAudioTimer = setTimeout(function () {
        resumeAudioTimer = 0;
        if (!pc || !ready || !audio || !remoteAudioStream) return;
        try {
          audio.srcObject = remoteAudioStream;
          audio.muted = false;
          var ap = audio.play();
          if (ap && ap.then) {
            ap.then(function () {
              B.log('audio resumed after wake (paused=' + audio.paused + ', muted=' + audio.muted + ')');
            }).catch(function (e) {
              B.log('audio resume rejected: ' + e.message);
            });
          } else {
            B.log('audio resumed after wake (paused=' + audio.paused + ', muted=' + audio.muted + ')');
          }
        } catch (e) {
          B.log('audio resume error: ' + e.message);
        }
      }, 80);
    }

    if (video && remoteVideoStream) {
      try {
        if (!video.srcObject) video.srcObject = remoteVideoStream;
        var vp = video.play();
        if (vp && vp.catch) vp.catch(function () {});
      } catch (e) {}
      if (gl && typeof gl.isContextLost === 'function' && gl.isContextLost()) {
        B.log('WebGL2 context lost after standby; resetting GPU pipeline');
        resetGlForContextLoss();
      }
      // Horizon OS can detach a desynchronized WebGL2 <canvas> compositor layer across headset
      // sleep without firing webglcontextlost, leaving a black screen until Enhance is cycled.
      // Force the same DOM + drawing-buffer rebind here so the picture returns automatically.
      cancelPendingRealFrame();
      hasUniqueHistory = 0;
      consecDupes = 0;
      resetPboState();
      if (canvas && canvas.style && enhanceMode !== MODE_OFF) {
        canvas.style.display = 'none';
        void canvas.offsetHeight;
        canvasW = 0;
        canvasH = 0;
        syncEnhanceDom();
      }
      scheduleVideoFeedback(true);
      setTimeout(function () {
        if (!pc || !ready || !video || !remoteVideoStream) return;
        try {
          var vp2 = video.play();
          if (vp2 && vp2.catch) vp2.catch(function () {});
        } catch (e) {}
        if (canvas && canvas.style && enhanceMode !== MODE_OFF) {
          canvasW = 0;
          canvasH = 0;
          syncEnhanceDom();
        }
        scheduleVideoFeedback(true);
      }, 150);
    }

    if (channels.control && channels.control.readyState === 'open') {
      sendText(channels.control, { message: 'videoKeyframeRequested', ifrRequested: true });
    }
    return 'ok';
  }

  function stopInternal() {
    ready = false;
    handshakeSent = false;
    padPresent = true;
    localPadPresent = false;
    lastPadPresenceAt = 0;
    lastResumeBounceAt = 0;
    vfcRunning = false;
    vfcGen++;
    if (resumeAudioTimer && typeof clearTimeout === 'function') {
      clearTimeout(resumeAudioTimer);
    }
    resumeAudioTimer = 0;
    remoteAudioStream = null;
    remoteVideoStream = null;
    lastPad = { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
    if (disconnectTimer && typeof clearTimeout === 'function') clearTimeout(disconnectTimer);
    disconnectTimer = null;
    cancelPendingRealFrame();
    lastBytes = 0;
    lastFrames = 0;
    lastStatsAt = 0;
    lastJbDelay = 0;
    lastJbTargetDelay = 0;
    lastJbMinDelay = 0;
    lastJbCount = 0;
    lastDecTime = 0;
    vfcFrameCount = 0;
    hasUniqueHistory = 0;
    consecDupes = 0;
    lastRumbleZero = true;
    resetPboState();
    statUniqueFrames = 0;
    statDupeFrames = 0;
    statOutFrames = 0;
    statFgFrames = 0;
    statPboReads = 0;
    statGpuMsSum = 0;
    statGpuSamples = 0;
    if (statsTimer) { clearInterval(statsTimer); statsTimer = null; }
    if (keepaliveTimer) { clearInterval(keepaliveTimer); keepaliveTimer = null; }
    if (keepaliveResetTimer) { clearTimeout(keepaliveResetTimer); keepaliveResetTimer = null; }
    Object.keys(channels).forEach(function (k) {
      try { channels[k].close(); } catch (e) {}
    });
    channels = {};
    if (pc) {
      try { pc.close(); } catch (e) {}
      pc = null;
    }
    if (video) {
      try { video.srcObject = null; } catch (e) {}
    }
    if (audio) {
      try { audio.srcObject = null; } catch (e) {}
    }
    if (hud && hudVisible) hud.textContent = 'direct: idle';
  }

  window.GTA60Direct = {
    start: function (cfg) { return start(cfg || {}); },
    onAnswer: onAnswer,
    pad: pad,
    p: p,
    setPadPresent: setPadPresent,
    resyncPad: resyncPad,
    releasePad: releasePad,
    setEnhance: setEnhance,
    setLook: setLook,
    setHudVisible: setHudVisible,
    onResume: onResume,
    stop: stopInternal,
    _classifyDupe: classifyDupeCadence
  };

  try {
    if (document && typeof document.addEventListener === 'function') {
      document.addEventListener('click', function () {
        B.onState('toggleHud');
      });
      document.addEventListener('visibilitychange', function () {
        if (!document.hidden && pc && ready) {
          onResume();
        }
      });
    }
  } catch (e) {}

  var h264 = false;
  try {
    var caps = RTCRtpReceiver.getCapabilities && RTCRtpReceiver.getCapabilities('video');
    h264 = !!(caps && caps.codecs && caps.codecs.some(function (c) {
      return /h264/i.test(c.mimeType || '');
    }));
  } catch (e) {}
  B.log('page ready; webrtc=' + (typeof RTCPeerConnection !== 'undefined') + '; h264=' + h264);
})();

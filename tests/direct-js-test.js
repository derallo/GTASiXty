// Protocol-level test for quest-app/src/main/assets/direct/direct.js.
//
// No WebRTC is involved: a mock RTCPeerConnection records what the page does, so the parts that
// are easy to get silently wrong can be checked on a desktop - SDP candidate stripping, the
// four-channel handshake sequence, and the exact binary layout of the gamepad reports.
//
// Run: node tests/direct-js-test.js
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SCRIPT = path.join(__dirname, '..', 'quest-app', 'src', 'main', 'assets', 'direct', 'direct.js');
const code = fs.readFileSync(SCRIPT, 'utf8');

let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log(`ok   ${name}`);
  } else {
    failures++;
    console.log(`FAIL ${name}${detail ? ' - ' + detail : ''}`);
  }
}

class MockChannel {
  constructor(label) {
    this.label = label;
    this.readyState = 'connecting';
    this.sent = [];
    this.onopen = null;
    this.onmessage = null;
    this.binaryType = 'blob';
  }
  send(data) { this.sent.push(data); }
  close() { this.readyState = 'closed'; }
  open() { this.readyState = 'open'; if (this.onopen) this.onopen(); }
}

const OFFER_SDP = [
  'v=0',
  'o=- 1 2 IN IP4 127.0.0.1',
  's=-',
  't=0 0',
  'a=group:BUNDLE 0 1 2',
  'm=audio 9 UDP/TLS/RTP/SAVPF 111',
  'a=mid:0',
  'a=candidate:1 1 udp 2122260223 4f2a3b1c.local 50000 typ host',
  'm=video 9 UDP/TLS/RTP/SAVPF 102',
  'a=mid:1',
  'a=candidate:2 1 udp 2122260223 4f2a3b1c.local 50000 typ host',
  'a=end-of-candidates',
  'm=application 9 UDP/DTLS/SCTP webrtc-datachannel',
  'a=mid:2'
].join('\r\n') + '\r\n';

class MockPC {
  constructor(config) {
    this.config = config;
    this.localDescription = null;
    this.remoteDescription = null;
    this.iceGatheringState = 'complete';
    this.connectionState = 'new';
    this.channels = [];
    this.ice = [];
    this.listeners = {};
    MockPC.instances.push(this);
  }
  addTransceiver(kind) {
    this.transceiver = { kind, direction: kind === 'video' ? 'recvonly' : 'sendrecv' };
    this.transceivers = this.transceivers || [];
    this.transceivers.push(kind);
    return { setCodecPreferences(prefs) { this.prefs = prefs; } };
  }
  createDataChannel(label, options) {
    const ch = new MockChannel(label);
    ch.options = options;
    this.channels.push(ch);
    return ch;
  }
  createOffer() { return Promise.resolve({ type: 'offer', sdp: 'mock-offer' }); }
  setLocalDescription() { this.localDescription = { type: 'offer', sdp: OFFER_SDP }; return Promise.resolve(); }
  setRemoteDescription(d) { this.remoteDescription = d; return Promise.resolve(); }
  addIceCandidate(c) { this.ice.push(c); return Promise.resolve(); }
  getStats() { return Promise.resolve(new Map()); }
  getReceivers() { return []; }
  close() { this.connectionState = 'closed'; }
  addEventListener(name, fn) { (this.listeners[name] = this.listeners[name] || []).push(fn); }
  removeEventListener() {}
}

class MockStream {
  constructor() { this.tracks = []; }
  addTrack(t) { this.tracks.push(t); }
}
MockPC.instances = [];

let audioPlayCount = 0;
let audioPauseCount = 0;
const videoEl = { srcObject: null, muted: false, play: () => Promise.resolve() };
const audioEl = {
  srcObject: null,
  paused: false,
  muted: false,
  play: () => { audioPlayCount++; return Promise.resolve(); },
  pause: () => { audioPauseCount++; }
};
const hudEl = { textContent: '' };
const errEl = { style: {}, textContent: '' };

let exchangePayload = null;
  const rumbles = [];
  const sandbox = {
    console,
    performance,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    TextEncoder,
    TextDecoder,
    Math,
    JSON,
    Object,
    Promise,
    MediaStream: MockStream,
    RTCPeerConnection: MockPC,
    RTCRtpReceiver: {
      getCapabilities: () => ({
        codecs: [
          { mimeType: 'video/H264', clockRate: 90000, sdpFmtpLine: 'level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f' },
          { mimeType: 'video/VP8', clockRate: 90000 }
        ]
      })
    },
    document: {
      getElementById: (id) => (id === 'v' ? videoEl : id === 'a' ? audioEl : id === 'hud' ? hudEl : errEl)
    },
    GTA60Bridge: {
      log: () => {},
      onState: () => {},
      onError: (m) => { sandbox.__error = m; },
      onRumble: (lm, rm, lt, rt, dur) => { rumbles.push({ lm, rm, lt, rt, dur }); },
      exchange: (payload) => {
        exchangePayload = JSON.parse(payload);
        return JSON.stringify({
          sdp: 'v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\na=mid:1\r\n',
          candidates: [{ candidate: 'candidate:1 1 udp 1 192.168.68.73 50000 typ host', sdpMid: '1', sdpMLineIndex: 1 }]
        });
      }
    }
  };
sandbox.window = sandbox;
sandbox.crypto = { randomUUID: () => '00000000-0000-4000-8000-000000000000' };
sandbox.globalThis = sandbox;

vm.createContext(sandbox);
vm.runInContext(code, sandbox, { filename: 'direct.js' });

(async () => {
  const api = sandbox.GTA60Direct;
  check('page exposes GTA60Direct', !!api && typeof api.start === 'function');
  if (!api) process.exit(1);

  await api.start({ localIp: '192.168.68.73' });
  const pc = MockPC.instances[0];
  check('peer connection created', !!pc);
  check('uses exactly 4 data channels', !!pc && pc.channels.length === 4,
    String(pc && pc.channels.length));
  check('audio and video transceivers added in order',
    !!pc && pc.transceivers.join(',') === 'audio,video', pc && pc.transceivers.join(','));

  check('signaling payload received', exchangePayload !== null);
  if (exchangePayload) {
    check('offer strips inline candidates and conforms to PC offer shape',
      !/a=candidate:/.test(exchangePayload.sdp) &&
      !/\.local/.test(exchangePayload.sdp) &&
      !/end-of-candidates/.test(exchangePayload.sdp) &&
      /o=rtc 65029611 0 IN IP4 127\.0\.0\.1/.test(exchangePayload.sdp) &&
      /a=ice-options:ice2,trickle/.test(exchangePayload.sdp) &&
      /profile-level-id=4d002a/.test(exchangePayload.sdp) &&
      /x-google-max-bitrate=500000/.test(exchangePayload.sdp),
      exchangePayload.sdp);
    check('two local candidates forwarded', exchangePayload.candidates.length === 2,
      String(exchangePayload.candidates.length));
    check('audio candidate mapped to mid 0',
      exchangePayload.candidates[0].sdpMid === '0' && exchangePayload.candidates[0].sdpMLineIndex === 0,
      JSON.stringify(exchangePayload.candidates[0]));
    check('video candidate mapped to mid 1',
      exchangePayload.candidates[1].sdpMid === '1' && exchangePayload.candidates[1].sdpMLineIndex === 1,
      JSON.stringify(exchangePayload.candidates[1]));
    check('candidate keeps the real port', / 50000 typ host/.test(exchangePayload.candidates[0].candidate));
  }

  await new Promise((r) => setImmediate(r));
  check('remote answer applied', !!(pc && pc.remoteDescription && pc.remoteDescription.type === 'answer'));
  check('remote candidate added', !!(pc && pc.ice.length === 1));

  // Open the four channels in a different order than creation: the handshake must wait for all.
  const byLabel = {};
  pc.channels.forEach((c) => { byLabel[c.label] = c; });
  ['chat', 'control', 'input'].forEach((label) => byLabel[label].open());
  check('no handshake before all channels open', byLabel.message.sent.length === 0);
  byLabel.message.open();
  check('handshake sent when all four open', byLabel.message.sent.length === 1);

  let handshake = null;
  try { handshake = JSON.parse(new TextDecoder().decode(byLabel.message.sent[0])); } catch (e) {}
  check('handshake is binary JSON with messageV1', handshake && handshake.type === 'Handshake' && handshake.version === 'messageV1',
    String(handshake && handshake.type));

  // HandshakeAck -> control/authorization + pad add/remove/add + client metadata.
  byLabel.message.onmessage({ data: new TextEncoder().encode(JSON.stringify({ type: 'HandshakeAck' })).buffer });
  check('control authorization sent', byLabel.control.sent.length >= 4,
    String(byLabel.control.sent.length));
  let auth = null;
  try { auth = JSON.parse(new TextDecoder().decode(byLabel.control.sent[0])); } catch (e) {}
  check('authorizationRequest has the access key',
    auth && auth.message === 'authorizationRequest' && auth.accessKey === '4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E');
  check('client metadata is 15 bytes', byLabel.input.sent.length === 1 && byLabel.input.sent[0].byteLength === 15,
    byLabel.input.sent.length ? String(byLabel.input.sent[0].byteLength) : 'none');

  // Gamepad report layout: 37 bytes, little endian, documented offsets.
  api.pad({ buttons: 0x0010, lx: -100, ly: 200, rx: 300, ry: -400, lt: 65535, rt: 0 });
  const report = byLabel.input.sent[byLabel.input.sent.length - 1];
  check('gamepad report is 37 bytes', report.byteLength === 37, String(report.byteLength));
  const dv = new DataView(report);
  check('report type is 2', dv.getUint16(0, true) === 2);
  check('one pad, index 0', dv.getUint8(14) === 1 && dv.getUint8(15) === 0);
  check('buttons at offset 16', dv.getUint16(16, true) === 0x0010);
  check('left stick axes at 18/20', dv.getInt16(18, true) === -100 && dv.getInt16(20, true) === 200);
  check('right stick axes at 22/24', dv.getInt16(22, true) === 300 && dv.getInt16(24, true) === -400);
  check('triggers at 26/28', dv.getUint16(26, true) === 65535 && dv.getUint16(28, true) === 0);
  check('virtual physicality flags', dv.getUint32(30, true) === 1 && dv.getUint8(36) === 1);

  // Fast primitive zero-alloc gamepad entrypoint (GTA60Direct.p).
  api.p(0x1020, 1234, -5678, -9012, 3456, 12000, 54000);
  const fastReport = byLabel.input.sent[byLabel.input.sent.length - 1];
  const fdv = new DataView(fastReport);
  check('fast p() gamepad report is 37 bytes with exact offsets',
    fastReport.byteLength === 37 &&
    fdv.getUint16(16, true) === 0x1020 &&
    fdv.getInt16(18, true) === 1234 &&
    fdv.getInt16(20, true) === -5678 &&
    fdv.getInt16(22, true) === -9012 &&
    fdv.getInt16(24, true) === 3456 &&
    fdv.getUint16(26, true) === 12000 &&
    fdv.getUint16(28, true) === 54000);

  // Virtual-pad liveness. Pad 0 is announced for the whole session: two 2026-11-21 builds that
  // skipped or released it were kicked by the console with KickForNoClientConnection ~60 s after
  // streaming started, so presence only drives a re-announce (and teardown releases it).
  const controlMsgs = () => byLabel.control.sent.map((b) => JSON.parse(new TextDecoder().decode(b)));
  const padMsgs = () => controlMsgs().filter((m) => m.message === 'gamepadChanged');
  const padBase = padMsgs().length;
  check('handshake announces pad 0 add/remove/add',
    padBase >= 3 && padMsgs().slice(0, 3).map((m) => m.wasAdded).join(',') === 'true,false,true',
    JSON.stringify(padMsgs()));

  api.setPadPresent(false);
  check('missing local pad releases pad 0 after handshake so Xbox controller keeps player 1',
    padMsgs().length === padBase + 1 && padMsgs()[padBase].wasAdded === false,
    JSON.stringify(padMsgs()));
  check('resyncPad releases pad 0 when no local pad is attached',
    api.resyncPad() === 'released' &&
    padMsgs().length === padBase + 2 && padMsgs()[padBase + 1].wasAdded === false);
  const neutralKeepalive = byLabel.input.sent[byLabel.input.sent.length - 1];
  const nrDv = new DataView(neutralKeepalive);
  check('pad reports stay neutral before release',
    neutralKeepalive.byteLength === 37 && nrDv.getUint16(16, true) === 0 &&
    nrDv.getInt16(18, true) === 0 && nrDv.getUint16(26, true) === 0);

  // A pad reappearing (Bluetooth reconnect after sleep) re-announces pad 0 so a resumed game gets
  // a fresh pad-arrival event.
  api.p(0x0010, 0, 0, 0, 0, 0, 0);
  check('first input after a reconnect re-announces pad 0',
    padMsgs().length === padBase + 5 &&
    padMsgs().slice(-3).map((m) => m.wasAdded).join(',') === 'true,false,true',
    JSON.stringify(padMsgs()));
  check('setPadPresent(true) is a no-op when a pad is already present',
    api.setPadPresent(true) === true && padMsgs().length === padBase + 5);
  check('resyncPad forces add/remove/add and reports ok when local pad present',
    api.resyncPad() === 'ok' &&
    padMsgs().length === padBase + 8 &&
    padMsgs().slice(-3).map((m) => m.wasAdded).join(',') === 'true,false,true');
  api.releasePad();
  check('releasePad neutralizes and removes pad 0 on teardown',
    padMsgs().length === padBase + 9 && padMsgs()[padBase + 8].wasAdded === false);

  // On-Quest GPU enhancement mode selector (0=OFF, 1=RCAS, 2=SUPERRES, 3=FG60, 4=FG60+SR).
  check('setEnhance cycles modes cleanly',
    api.setEnhance(0) === 'OFF' &&
    api.setEnhance(1) === 'RCAS' &&
    api.setEnhance(2) === 'SUPERRES' &&
    api.setEnhance(3) === 'FG60' &&
    api.setEnhance(4) === 'FG60+SR');

  // Color look preset selector (0=Normal, 1=Vivid, 2=Night).
  check('setLook cycles presets cleanly',
    api.setLook(0) === 'Normal' &&
    api.setLook(1) === 'Vivid' &&
    api.setLook(2) === 'Night');

  // Vibration forwarding: 13-byte ReportVibration (type 128) on input channel -> Bridge.onRumble.
  const vibBuf = new ArrayBuffer(13);
  const vibDv = new DataView(vibBuf);
  vibDv.setUint16(0, 128, true);
  vibDv.setUint8(3, 0);
  vibDv.setUint8(4, 80);
  vibDv.setUint8(5, 40);
  vibDv.setUint8(6, 25);
  vibDv.setUint8(7, 10);
  vibDv.setUint16(8, 250, true);
  byLabel.input.onmessage({ data: vibBuf });
  check('vibration report forwards to GTA60Bridge.onRumble',
    rumbles.length === 1 &&
    rumbles[0].lm === 80 && rumbles[0].rm === 40 &&
    rumbles[0].lt === 25 && rumbles[0].rt === 10 && rumbles[0].dur === 250);

  // Relative cadence duplicate detector: synthetic 30fps-in-60Hz noisy sequence vs native 60fps.
  const noisy30 = [22.0, 1.8, 19.5, 2.1, 25.0, 1.5];
  const res30 = [];
  let prevM = 10.0, cDup = 0, hist = 0;
  for (const m of noisy30) {
    const isD = api._classifyDupe(m, 5, prevM, cDup, hist);
    res30.push(isD ? 1 : 0);
    cDup = isD ? cDup + 1 : 0;
    prevM = m;
    hist = Math.min(2, hist + 1);
  }
  const native60 = [15.0, 16.0, 14.5, 17.0];
  const res60 = [];
  prevM = 10.0; cDup = 0; hist = 0;
  for (const m of native60) {
    const isD = api._classifyDupe(m, 12, prevM, cDup, hist);
    res60.push(isD ? 1 : 0);
    cDup = isD ? cDup + 1 : 0;
    prevM = m;
    hist = Math.min(2, hist + 1);
  }
  check('cadence classifier phase-locks noisy 30fps and passes native 60fps',
    res30.join(',') === '0,1,0,1,0,1' && res60.join(',') === '0,0,0,0',
    `30=${res30.join(',')} 60=${res60.join(',')}`);

  const triple = [20.0, 1.2, 1.0, 18.0];
  const resTriple = [];
  prevM = 10.0; cDup = 0; hist = 0;
  for (const m of triple) {
    const isD = api._classifyDupe(m, 8, prevM, cDup, hist);
    resTriple.push(isD ? 1 : 0);
    cDup = isD ? cDup + 1 : 0;
    prevM = m;
    hist = Math.min(2, hist + 1);
  }
  check('cadence classifier allows a triple hold',
    resTriple.join(',') === '0,1,1,0',
    resTriple.join(','));
  check('low mean with a hot block is not a duplicate',
    api._classifyDupe(1.0, 40, 10.0, 0, 1) === false);
  check('setDisplayHz keeps 120 and 90',
    api.setDisplayHz(120) === 120 && api.setDisplayHz(90) === 90 && api.setDisplayHz(72) === 120);

  // WarningForBeingIdle should keep the session alive instead of calling onError.
  sandbox.__error = null;
  const msgCountBefore = byLabel.message.sent.length;
  byLabel.message.onmessage({
    data: new TextEncoder().encode(JSON.stringify({
      type: 'Message',
      id: 'idle-1',
      target: '/streaming/sessionLifetimeManagement/serverInitiatedDisconnect',
      content: JSON.stringify({ reason: 'WarningForBeingIdle' })
    })).buffer
  });
  check('WarningForBeingIdle does not fail the session', sandbox.__error === null && byLabel.message.sent.length >= msgCountBefore + 2);

  api.stop();
  check('stop closes the channel set', byLabel.input.readyState === 'closed');
  check('resyncPad is a no-op once stopped', api.resyncPad() === 'not streaming');

  // Second session started while no controller is attached (padPresent: false). Pad 0 must still
  // be announced in the handshake (removing it gets the session kicked), and onResume() must bounce
  // audio + request a keyframe.
  await api.start({ localIp: '192.168.1.50', padPresent: false });
  const pc2 = MockPC.instances[1];
  const ch2 = {};
  pc2.channels.forEach((c) => { ch2[c.label] = c; c.open(); });
  ch2.message.onmessage({ data: new TextEncoder().encode(JSON.stringify({ type: 'HandshakeAck' })).buffer });
  const ctrl2Msgs = () => ch2.control.sent.map((b) => JSON.parse(new TextDecoder().decode(b)));
  const pad2Msgs = () => ctrl2Msgs().filter((m) => m.message === 'gamepadChanged');
  check('handshake announces pad 0 even with no local controller',
    pad2Msgs().length === 3 &&
    pad2Msgs().map((m) => m.wasAdded).join(',') === 'true,false,true',
    JSON.stringify(pad2Msgs()));

  api.releasePad();
  check('releasePad still removes pad 0 when the console session ends',
    pad2Msgs().length === 4 && pad2Msgs()[3].wasAdded === false);

  const mockAudioTrack = { kind: 'audio' };
  const mockAudioStream = new MockStream();
  mockAudioStream.addTrack(mockAudioTrack);
  pc2.ontrack({ track: mockAudioTrack, streams: [mockAudioStream] });
  const playCountBeforeResume = audioPlayCount;
  const ctrlBeforeResume = ctrl2Msgs().length;
  const resumeRet = api.onResume();
  check('onResume detaches audio.srcObject immediately and requests keyframe',
    resumeRet === 'ok' &&
    audioEl.srcObject === null &&
    audioPauseCount >= 1 &&
    ctrl2Msgs().slice(ctrlBeforeResume).some((m) => m.message === 'videoKeyframeRequested' && m.ifrRequested === true));
  await new Promise((r) => setTimeout(r, 120));
  check('onResume re-attaches audio.srcObject and calls audio.play()',
    audioEl.srcObject === mockAudioStream && audioPlayCount === playCountBeforeResume + 1);
  api.stop();

  console.log(failures === 0 ? '\ndirect.js protocol test PASS' : `\n${failures} check(s) failed`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});

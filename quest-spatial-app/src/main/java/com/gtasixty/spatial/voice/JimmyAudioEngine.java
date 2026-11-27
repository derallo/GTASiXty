package com.gtasixty.spatial.voice;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Always-listening 16 kHz mono PCM voice-activity gate for Jimmy.
 * Uses VOICE_RECOGNITION (fallback MIC) with adaptive ambient noise floor, open/close hysteresis,
 * 1.0 s circular pre-roll, non-blocking stop, and automatic pause/tail guard while Jimmy is busy.
 */
final class JimmyAudioEngine {

    private static final String TAG = "GTA60-JimmyAudio";
    private static final int SAMPLE_RATE = JimmyAudioBuffer.SAMPLE_RATE_16K;
    private static final int FRAME_SAMPLES = 320; // 20 ms at 16 kHz
    private static final int PRE_ROLL_SAMPLES = SAMPLE_RATE; // 1.0 s rolling pre-roll
    private static final long WARMUP_MS = 350L;
    private static final long MIN_SPEECH_ONSET_MS = 100L;
    private static final long WAKE_SILENCE_MS = 480L;
    private static final long QUESTION_SILENCE_MS = 750L;
    private static final long MAX_WAKE_UTTERANCE_MS = 7_000L;
    private static final long MAX_QUESTION_UTTERANCE_MS = 8_000L;
    private static final long POST_SPEECH_TAIL_GUARD_MS = 280L;

    interface Listener {
        void onAudioLevel(int level0To100);
        void onSpeechStarted(boolean wakeWordRequired);
        void onUtteranceCaptured(short[] pcm16, int durationMs, boolean wakeWordRequired);
        void onActiveCountdown(int secondsRemaining);
        void onActiveTimeout();
        void onError(String message);
    }

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object audioLifecycleLock = new Object();
    private Thread captureThread;

    private volatile boolean wakeWordEnabled = true;
    private volatile int sensitivity = JimmyConfig.SENSITIVITY_NORMAL;
    private volatile boolean pausedForBusy = false;
    private volatile long busyReleaseGuardUntilMs = 0L;

    private volatile boolean activeQuestionPending = false;
    private volatile long activeQuestionDeadlineMs = 0L;
    private volatile int lastCountdownSec = -1;

    JimmyAudioEngine(Listener listener) {
        this.listener = listener;
    }

    void configure(boolean wakeWordEnabled, int sensitivity) {
        this.wakeWordEnabled = wakeWordEnabled;
        this.sensitivity = JimmyConfig.clampSensitivity(sensitivity);
    }

    boolean isRunning() {
        return running.get();
    }

    void setPausedForBusy(boolean paused) {
        final boolean wasPaused = this.pausedForBusy;
        this.pausedForBusy = paused;
        if (wasPaused && !paused) {
            busyReleaseGuardUntilMs = SystemClock.uptimeMillis() + POST_SPEECH_TAIL_GUARD_MS;
        }
    }

    void enterActiveQuestionMode(long timeoutMs) {
        lastCountdownSec = -1;
        activeQuestionPending = true;
        activeQuestionDeadlineMs = SystemClock.uptimeMillis() + Math.max(2000L, timeoutMs);
    }

    void cancelActiveQuestionMode() {
        activeQuestionPending = false;
        activeQuestionDeadlineMs = 0L;
        lastCountdownSec = -1;
    }

    /**
     * Arms active question listening (waits for the user to start speaking after the chime,
     * then captures the full question cleanly without a runaway 15 s pre-speech recording).
     */
    void triggerManualCapture(long timeoutMs) {
        enterActiveQuestionMode(timeoutMs);
    }

    synchronized void start() {
        if (running.getAndSet(true)) return;
        captureThread = new Thread(this::captureLoop, "Jimmy-AudioGate");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /**
     * Non-blocking stop so UI lifecycle calls (onPause/save/destroy) never stall waiting on AudioRecord.read().
     */
    synchronized void stop() {
        if (!running.getAndSet(false)) return;
        activeQuestionPending = false;
        final Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    @SuppressLint("MissingPermission")
    private void captureLoop() {
        final int minBufBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufBytes <= 0) {
            running.set(false);
            if (listener != null) listener.onError("AudioRecord unavailable on this device.");
            return;
        }

        final int bufBytes = Math.max(minBufBytes, FRAME_SAMPLES * 2 * 10);
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;

        synchronized (audioLifecycleLock) {
            try {
                record = createInitializedRecord(bufBytes);
                if (record == null) {
                    running.set(false);
                    if (listener != null) listener.onError("Could not initialize Quest microphone.");
                    return;
                }

                final int sessionId = record.getAudioSessionId();
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(sessionId);
                        if (aec != null) aec.setEnabled(true);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(sessionId);
                        if (ns != null) ns.setEnabled(true);
                    }
                } catch (Throwable ignored) {}

                record.startRecording();
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    running.set(false);
                    if (listener != null) listener.onError("Microphone failed to start recording.");
                    return;
                }
                Log.i(TAG, "Mic loop started (source=" + record.getAudioSource() + ", rate=" + SAMPLE_RATE + ")");
            } catch (Throwable t) {
                running.set(false);
                if (listener != null) listener.onError("Mic init error: " + t.getMessage());
                releaseSafely(record, aec, ns);
                return;
            }
        }

        try {
            final short[] frame = new short[FRAME_SAMPLES];
            final JimmyAudioBuffer.Pcm16RingBuffer preRoll =
                    new JimmyAudioBuffer.Pcm16RingBuffer(PRE_ROLL_SAMPLES);
            ByteArrayOutputStream recBytes = null;

            boolean recording = false;
            boolean recordingWakeRequired = true;
            long speechStartMs = 0L;
            long activeSinceMs = 0L;
            long silenceStartMs = 0L;
            float noiseFloor = 3.0f;
            long startedAtMs = SystemClock.uptimeMillis();
            long lastLevelReportMs = 0L;
            long lastDiagLogMs = startedAtMs;

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                final int read = record.read(frame, 0, frame.length);
                if (read <= 0) continue;

                final long now = SystemClock.uptimeMillis();
                final int level = JimmyAudioBuffer.computeLevel0To100(frame, 0, read);
                if (listener != null && (now - lastLevelReportMs) >= 80L) {
                    lastLevelReportMs = now;
                    listener.onAudioLevel(pausedForBusy ? 0 : level);
                }

                if (pausedForBusy || now < busyReleaseGuardUntilMs) {
                    if (recording) {
                        recording = false;
                        recBytes = null;
                    }
                    activeSinceMs = 0L;
                    silenceStartMs = 0L;
                    preRoll.clear();
                    continue;
                }

                if (activeQuestionPending && !recording) {
                    final long remMs = activeQuestionDeadlineMs - now;
                    if (remMs <= 0L) {
                        activeQuestionPending = false;
                        lastCountdownSec = -1;
                        Log.i(TAG, "Active question listen timed out (no speech detected)");
                        if (listener != null) listener.onActiveTimeout();
                    } else {
                        final int remSec = (int) Math.ceil(remMs / 1000.0);
                        if (remSec != lastCountdownSec) {
                            lastCountdownSec = remSec;
                            if (listener != null) listener.onActiveCountdown(remSec);
                        }
                    }
                }

                // If wake-word mode is off and no active question is pending, keep pre-roll warm quietly.
                if (!wakeWordEnabled && !activeQuestionPending && !recording) {
                    noiseFloor = noiseFloor * 0.95f + level * 0.05f;
                    preRoll.write(frame, 0, read);
                    continue;
                }

                if (!recording) {
                    noiseFloor = noiseFloor * 0.95f + level * 0.05f;
                }
                final float noise = Math.min(45.0f, Math.max(1.5f, noiseFloor));
                final float sensitivityMargin;
                switch (sensitivity) {
                    case JimmyConfig.SENSITIVITY_QUIET:
                        sensitivityMargin = 5.0f;
                        break;
                    case JimmyConfig.SENSITIVITY_LOUD:
                        sensitivityMargin = 13.0f;
                        break;
                    default:
                        sensitivityMargin = 8.0f;
                        break;
                }
                final float openAt = Math.max(8.0f, noise + sensitivityMargin);
                final float closeAt = Math.max(noise + 2.5f, openAt * 0.55f);

                if ((now - lastDiagLogMs) >= 5000L) {
                    lastDiagLogMs = now;
                    Log.d(TAG, String.format("Mic VAD idle: level=%d noise=%.1f openAt=%.1f activeWait=%s rec=%s",
                            level, noise, openAt, activeQuestionPending, recording));
                }

                if (now - startedAtMs < WARMUP_MS) {
                    preRoll.write(frame, 0, read);
                    continue;
                }

                final boolean voiceActive = level >= (recording ? closeAt : openAt);

                if (!recording) {
                    preRoll.write(frame, 0, read);
                    if (voiceActive) {
                        if (activeSinceMs == 0L) activeSinceMs = now;
                        if ((now - activeSinceMs) >= MIN_SPEECH_ONSET_MS) {
                            recording = true;
                            recordingWakeRequired = !activeQuestionPending;
                            activeQuestionPending = false;
                            speechStartMs = now;
                            silenceStartMs = 0L;
                            activeSinceMs = 0L;
                            recBytes = new ByteArrayOutputStream(SAMPLE_RATE * 4);
                            appendShorts(recBytes, preRoll.snapshot());
                            Log.i(TAG, String.format("Speech onset (wakeReq=%s level=%d noise=%.1f openAt=%.1f)",
                                    recordingWakeRequired, level, noise, openAt));
                            if (listener != null) listener.onSpeechStarted(recordingWakeRequired);
                        }
                    } else {
                        activeSinceMs = 0L;
                    }
                    continue;
                }

                // Currently recording an utterance
                appendShorts(recBytes, frame, read);
                final long utteranceMs = now - speechStartMs;
                final long maxMs = recordingWakeRequired ? MAX_WAKE_UTTERANCE_MS : MAX_QUESTION_UTTERANCE_MS;
                final long silenceLimitMs = recordingWakeRequired ? WAKE_SILENCE_MS : QUESTION_SILENCE_MS;

                if (voiceActive) {
                    silenceStartMs = 0L;
                } else {
                    if (silenceStartMs == 0L) silenceStartMs = now;
                }

                final boolean silenceExpired = silenceStartMs > 0L && (now - silenceStartMs) >= silenceLimitMs;
                if (silenceExpired || utteranceMs >= maxMs) {
                    final short[] captured = bytesToShorts(recBytes.toByteArray());
                    final boolean wakeReq = recordingWakeRequired;
                    recording = false;
                    recBytes = null;
                    silenceStartMs = 0L;
                    activeSinceMs = 0L;
                    preRoll.clear();

                    final short[] trimmed = JimmyAudioBuffer.trimTrailingSilence(
                            captured, SAMPLE_RATE, 0.010f, 200, 400);
                    final int trimmedMs = Math.round((trimmed.length * 1000.0f) / SAMPLE_RATE);
                    Log.i(TAG, "Utterance captured: trimmedMs=" + trimmedMs + " wakeReq=" + wakeReq);
                    if (trimmed.length >= (SAMPLE_RATE / 4) && listener != null) {
                        listener.onUtteranceCaptured(trimmed, trimmedMs, wakeReq);
                    }
                }
            }
        } catch (Throwable t) {
            if (running.get()) {
                Log.w(TAG, "Audio capture loop error: " + t.getMessage());
                if (listener != null) listener.onError("Mic error: " + t.getMessage());
            }
        } finally {
            synchronized (audioLifecycleLock) {
                releaseSafely(record, aec, ns);
            }
            Log.i(TAG, "Mic loop stopped");
        }
    }

    @SuppressLint("MissingPermission")
    private static AudioRecord createInitializedRecord(int bufBytes) {
        final int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int src : sources) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(
                        src,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufBytes);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    return candidate;
                }
                candidate.release();
            } catch (Throwable t) {
                if (candidate != null) {
                    try { candidate.release(); } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static void releaseSafely(AudioRecord record, AcousticEchoCanceler aec, NoiseSuppressor ns) {
        try { if (aec != null) aec.release(); } catch (Throwable ignored) {}
        try { if (ns != null) ns.release(); } catch (Throwable ignored) {}
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
            try { record.release(); } catch (Throwable ignored) {}
        }
    }

    private static void appendShorts(ByteArrayOutputStream out, short[] data) {
        if (data != null && data.length > 0) appendShorts(out, data, data.length);
    }

    private static void appendShorts(ByteArrayOutputStream out, short[] data, int count) {
        final ByteBuffer bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            bb.putShort(data[i]);
        }
        out.write(bb.array(), 0, count * 2);
    }

    private static short[] bytesToShorts(byte[] bytes) {
        final int count = bytes.length / 2;
        final short[] out = new short[count];
        ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }
}

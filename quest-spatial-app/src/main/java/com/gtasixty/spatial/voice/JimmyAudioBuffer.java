package com.gtasixty.spatial.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Pure-Java 16-bit PCM audio utilities (zero Android dependencies for JVM testing).
 * Adapted from FAImily-gaime-night's pre-roll circular buffer, silence trimmer, and WAV encoder.
 */
public final class JimmyAudioBuffer {

    public static final int SAMPLE_RATE_16K = 16000;
    public static final int SAMPLE_RATE_24K = 24000;

    private JimmyAudioBuffer() {}

    /**
     * Fixed-capacity circular buffer of 16-bit mono PCM samples used for rolling pre-roll capture.
     */
    public static final class Pcm16RingBuffer {
        private final short[] buffer;
        private int writeIndex = 0;
        private boolean isFull = false;

        public Pcm16RingBuffer(int capacitySamples) {
            this.buffer = new short[Math.max(1, capacitySamples)];
        }

        public void write(short[] samples, int offset, int length) {
            if (samples == null || length <= 0 || offset < 0 || offset + length > samples.length) {
                return;
            }
            final int cap = buffer.length;
            if (length >= cap) {
                System.arraycopy(samples, offset + (length - cap), buffer, 0, cap);
                writeIndex = 0;
                isFull = true;
                return;
            }
            final int spaceToEnd = cap - writeIndex;
            if (length <= spaceToEnd) {
                System.arraycopy(samples, offset, buffer, writeIndex, length);
                writeIndex += length;
                if (writeIndex == cap) {
                    writeIndex = 0;
                    isFull = true;
                }
            } else {
                System.arraycopy(samples, offset, buffer, writeIndex, spaceToEnd);
                final int remaining = length - spaceToEnd;
                System.arraycopy(samples, offset + spaceToEnd, buffer, 0, remaining);
                writeIndex = remaining;
                isFull = true;
            }
        }

        public short[] snapshot() {
            if (!isFull) {
                final short[] out = new short[writeIndex];
                System.arraycopy(buffer, 0, out, 0, writeIndex);
                return out;
            }
            final int cap = buffer.length;
            final short[] out = new short[cap];
            final int rightLen = cap - writeIndex;
            System.arraycopy(buffer, writeIndex, out, 0, rightLen);
            System.arraycopy(buffer, 0, out, rightLen, writeIndex);
            return out;
        }

        public void clear() {
            writeIndex = 0;
            isFull = false;
        }

        public int length() {
            return isFull ? buffer.length : writeIndex;
        }
    }

    /**
     * Computes perceptual normalized audio level mapped to [0, 100] from RMS and peak amplitude.
     * Tuned so normal conversational speech on Quest 3 mic reads ~35-75 while room silence reads ~1-6.
     */
    public static int computeLevel0To100(short[] samples, int offset, int length) {
        if (samples == null || length <= 0 || offset < 0 || offset + length > samples.length) {
            return 0;
        }
        double sumSq = 0.0;
        double peak = 0.0;
        final int end = offset + length;
        for (int i = offset; i < end; i++) {
            final double norm = Math.abs(samples[i] / 32768.0);
            sumSq += norm * norm;
            if (norm > peak) peak = norm;
        }
        final double rms = Math.sqrt(sumSq / length);
        final double scaled = Math.max(rms * 420.0, peak * 110.0);
        return Math.min(100, (int) Math.round(scaled));
    }

    /**
     * Trims trailing silence recorded during silence-countdown windows while keeping a short tail.
     */
    public static short[] trimTrailingSilence(
            short[] samples,
            int sampleRate,
            float rmsThreshold,
            int keepTailMs,
            int minDurationMs) {
        if (samples == null || samples.length == 0 || sampleRate <= 0) {
            return samples == null ? new short[0] : samples;
        }
        final int minSamples = Math.min(
                samples.length,
                Math.max(1, Math.round((sampleRate * (float) minDurationMs) / 1000.0f)));
        if (samples.length <= minSamples) {
            return samples;
        }

        final int windowSize = Math.max(1, Math.round(sampleRate * 0.02f));
        int lastActiveEnd = 0;

        for (int end = samples.length; end > 0; end -= windowSize) {
            final int start = Math.max(0, end - windowSize);
            double sumSq = 0.0;
            for (int i = start; i < end; i++) {
                final double norm = samples[i] / 32768.0;
                sumSq += norm * norm;
            }
            final double rms = Math.sqrt(sumSq / (end - start));
            if (rms >= rmsThreshold) {
                lastActiveEnd = end;
                break;
            }
        }

        final int keepTailSamples = Math.max(0, Math.round((sampleRate * (float) keepTailMs) / 1000.0f));
        final int cutEnd = lastActiveEnd == 0
                ? minSamples
                : Math.min(samples.length, Math.max(minSamples, lastActiveEnd + keepTailSamples));

        if (cutEnd >= samples.length) {
            return samples;
        }
        final short[] trimmed = new short[cutEnd];
        System.arraycopy(samples, 0, trimmed, 0, cutEnd);
        return trimmed;
    }

    /**
     * Encodes 16-bit mono PCM samples into a standard 44-byte RIFF/WAVE payload.
     */
    public static byte[] encodePcm16ToWav(short[] samples, int sampleRate) {
        final int len = samples == null ? 0 : samples.length;
        final int dataSize = len * 2;
        final ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);

        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));

        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);              // PCM subchunk size
        buf.putShort((short) 1);     // PCM format = 1
        buf.putShort((short) 1);     // Mono
        buf.putInt(sampleRate);      // Sample rate
        buf.putInt(sampleRate * 2);  // Byte rate
        buf.putShort((short) 2);     // Block align
        buf.putShort((short) 16);    // Bits per sample

        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataSize);

        for (int i = 0; i < len; i++) {
            buf.putShort(samples[i]);
        }
        return buf.array();
    }

    /**
     * Synthesizes a clean ~150 ms two-tone chime at 24 kHz mono 16-bit PCM (little-endian).
     */
    public static byte[] synthesizeChimePcm24k(boolean ascending) {
        final int sampleRate = SAMPLE_RATE_24K;
        final int noteSamples = (int) (sampleRate * 0.075);
        final int totalSamples = noteSamples * 2;
        final double f1 = ascending ? 587.33 : 698.46; // D5 -> A5 or F5 -> D5
        final double f2 = ascending ? 880.00 : 523.25;
        final ByteBuffer out = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < totalSamples; i++) {
            final boolean secondNote = i >= noteSamples;
            final int localIdx = secondNote ? (i - noteSamples) : i;
            final double freq = secondNote ? f2 : f1;
            final double envIn = Math.min(1.0, localIdx / (sampleRate * 0.010));
            final double envOut = Math.min(1.0, (noteSamples - localIdx) / (sampleRate * 0.025));
            final double env = Math.min(envIn, envOut);
            final double sample = Math.sin(2.0 * Math.PI * freq * localIdx / sampleRate) * env * 0.22;
            final short s = (short) Math.max(-32767, Math.min(32767, Math.round(sample * 32767.0)));
            out.putShort(s);
        }
        return out.array();
    }
}

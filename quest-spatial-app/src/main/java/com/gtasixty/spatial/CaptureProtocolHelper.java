package com.gtasixty.spatial;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-JVM helpers for GT60 wire configuration, codec CSD adaptation (H.264 / HEVC / AV1),
 * supersampled surface sizing, and adaptive sharpening parameters.
 */
final class CaptureProtocolHelper {

    static final int CODEC_H264 = 0;
    static final int CODEC_HEVC = 1;
    static final int CODEC_AV1 = 2;

    static final int CAPTURE_CODEC_HEVC_8 = 0;
    static final int CAPTURE_CODEC_HEVC_10 = 1;
    static final int CAPTURE_CODEC_AV1 = 2;
    static final int CAPTURE_CODEC_H264 = 3;
    static final int CAPTURE_CODEC_COUNT = 4;

    static final int PANEL_SCALE_1440P = 0;
    static final int PANEL_SCALE_1800P = 1;
    static final int PANEL_SCALE_2160P = 2;
    static final int DEFAULT_PANEL_SCALE = PANEL_SCALE_1800P;

    static final String[] CAPTURE_CODEC_LABELS = {"HEVC", "HEVC 10b", "AV1", "H264"};

    static int panelPxW(int panelScale) {
        if (panelScale == PANEL_SCALE_2160P) return 3840;
        if (panelScale == PANEL_SCALE_1440P) return 2560;
        return 3200;
    }

    static int panelPxH(int panelScale) {
        if (panelScale == PANEL_SCALE_2160P) return 2160;
        if (panelScale == PANEL_SCALE_1440P) return 1440;
        return 1800;
    }

    static int panelDpi(int panelScale) {
        if (panelScale == PANEL_SCALE_2160P) return 384;
        if (panelScale == PANEL_SCALE_1440P) return 256;
        return 320;
    }

    static String panelScaleLabel(int panelScale) {
        if (panelScale == PANEL_SCALE_2160P) return "4K Ultra";
        if (panelScale == PANEL_SCALE_1440P) return "1440p";
        return "1800p SR";
    }

    static final class PreparedCsd {
        final List<byte[]> units;
        final boolean queueConfigBufferAfterStart;

        PreparedCsd(List<byte[]> units, boolean queueConfigBufferAfterStart) {
            this.units = units;
            this.queueConfigBufferAfterStart = queueConfigBufferAfterStart;
        }
    }

    private CaptureProtocolHelper() {}

    static String mimeForCodec(int codecId) {
        if (codecId == CODEC_AV1) return "video/av01";
        if (codecId == CODEC_HEVC) return "video/hevc";
        return "video/avc";
    }

    static int wireCodecForChoice(int captureCodecChoice) {
        switch (captureCodecChoice) {
            case CAPTURE_CODEC_AV1:
                return CODEC_AV1;
            case CAPTURE_CODEC_H264:
                return CODEC_H264;
            case CAPTURE_CODEC_HEVC_10:
            case CAPTURE_CODEC_HEVC_8:
            default:
                return CODEC_HEVC;
        }
    }

    static boolean isTenBitForChoice(int captureCodecChoice) {
        return captureCodecChoice == CAPTURE_CODEC_HEVC_10;
    }

    /**
     * Builds the GT60 {@code TYPE_CLIENT_CONFIG (0x83)} payload.
     *
     * <p>When {@code captureCodecChoice} is {@code null} (PC mode), returns the legacy 12-byte
     * payload. When non-null (Capture LAN mode), appends 2 bytes ({@code u8 codecWire}, {@code u8 flags})
     * where {@code flags = 0x80 | (tenBit ? 0x01 : 0x00)}.
     */
    static byte[] buildClientConfigPayload(
            int width, int height, int fps, int bitrateMbps, Integer captureCodecChoice) {
        if (captureCodecChoice == null) {
            ByteBuffer bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(width);
            bb.putInt(height);
            bb.putShort((short) fps);
            bb.putShort((short) bitrateMbps);
            return bb.array();
        }
        ByteBuffer bb = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(width);
        bb.putInt(height);
        bb.putShort((short) fps);
        bb.putShort((short) bitrateMbps);
        bb.put((byte) wireCodecForChoice(captureCodecChoice));
        int flags = 0x80 | (isTenBitForChoice(captureCodecChoice) ? 0x01 : 0x00);
        bb.put((byte) flags);
        return bb.array();
    }

    static List<byte[]> splitAnnexBNalUnits(byte[] blob) {
        final List<Integer> starts = new ArrayList<>();
        if (blob == null || blob.length == 0) return new ArrayList<>();
        for (int i = 0; i + 3 <= blob.length; i++) {
            if (blob[i] != 0 || blob[i + 1] != 0) continue;
            if (blob[i + 2] == 1) {
                starts.add(i);
                i += 2;
            } else if (i + 4 <= blob.length && blob[i + 2] == 0 && blob[i + 3] == 1) {
                starts.add(i);
                i += 3;
            }
        }
        final List<byte[]> units = new ArrayList<>();
        if (starts.isEmpty()) {
            units.add(blob);
            return units;
        }
        if (starts.get(0) != 0) starts.add(0, 0);
        for (int k = 0; k < starts.size(); k++) {
            final int from = starts.get(k);
            final int to = (k + 1 < starts.size()) ? starts.get(k + 1) : blob.length;
            if (to > from) units.add(Arrays.copyOfRange(blob, from, to));
        }
        return units;
    }

    /**
     * Adapts raw CSD bytes from {@code VIDEO_CONFIG} into MediaCodec {@code csd-0..2} buffers.
     *
     * <p>For AV1 ({@code video/av01}), wraps a raw Sequence Header OBU into an ISOBMFF {@code av1C}
     * record if needed and instructs the caller NOT to re-queue it with {@code BUFFER_FLAG_CODEC_CONFIG}
     * after {@code MediaCodec.start()}.
     */
    static PreparedCsd prepareDecoderCsd(int codecId, byte[] rawCsd) {
        if (codecId == CODEC_AV1) {
            List<byte[]> units = new ArrayList<>(1);
            if (rawCsd != null && rawCsd.length > 0) {
                if ((rawCsd[0] & 0xFF) == 0x81) {
                    units.add(Arrays.copyOf(rawCsd, rawCsd.length));
                } else {
                    byte[] wrapped = new byte[4 + rawCsd.length];
                    wrapped[0] = (byte) 0x81;
                    wrapped[1] = (byte) 0x0D;
                    wrapped[2] = (byte) 0x0C;
                    wrapped[3] = (byte) 0x00;
                    System.arraycopy(rawCsd, 0, wrapped, 4, rawCsd.length);
                    units.add(wrapped);
                }
            }
            return new PreparedCsd(units, false);
        }
        return new PreparedCsd(splitAnnexBNalUnits(rawCsd), true);
    }

    /**
     * Matches the SurfaceView dimensions 1:1 to the Spatial panel swapchain when the panel is
     * supersampled above the incoming stream resolution, avoiding compositor double-resampling.
     */
    static int[] computeSurfaceDimensions(int streamW, int streamH, int panelPxW, int panelPxH) {
        if (streamW <= 0 || streamH <= 0) {
            return new int[]{Math.max(1, panelPxW), Math.max(1, panelPxH)};
        }
        if (panelPxW >= streamW && panelPxH >= streamH) {
            return new int[]{panelPxW, panelPxH};
        }
        return new int[]{Math.min(panelPxW, streamW), Math.min(panelPxH, streamH)};
    }

    static boolean shouldEnableSpatialSuperRes(
            int srcW, int srcH, int surfW, int surfH, boolean srModeSelected) {
        return srModeSelected && (surfW > srcW || surfH > srcH);
    }

    static float computeRcasSharpnessStops(float baseStops, boolean generated) {
        // Keep generated-frame RCAS sharpness closely matched to real frames (+0.10 stop ≈ -6.7% lobe
        // instead of +1.0 stop / -50%) to eliminate the >2x sharp/soft "breathing" oscillation.
        return generated ? (baseStops + 0.10f) : baseStops;
    }
}

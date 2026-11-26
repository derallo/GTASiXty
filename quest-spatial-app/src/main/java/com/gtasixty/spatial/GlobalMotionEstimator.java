package com.gtasixty.spatial;

/**
 * Lightweight Quest CPU affine camera-motion estimator over a downsampled flow grid.
 *
 * <p>Consumes an RGBA8 {@code w x h} flow buffer (from async PBO readback) where RG encode
 * {@code [-maxUv, +maxUv]} centered at {@code 128/255} and A encodes flow confidence {@code [0..1]}.
 * Fits {@code vx(u) = tx + sx * (u - 0.5)} and {@code vy(v) = ty + sy * (v - 0.5)} using a
 * 2-pass outlier-rejecting least-squares fit, masking HUD regions and resetting cleanly on scene cuts.
 * Zero heap allocations per call.
 */
final class GlobalMotionEstimator {

    private static final float CENTER_NORM = 128.0f / 255.0f;
    private static final float MIN_CONF = 0.25f;
    private static final float MAX_SCALE = 0.12f;
    private static final float SMOOTH_ALPHA = 0.25f;

    private float tx = 0f;
    private float ty = 0f;
    private float sx = 0f;
    private float sy = 0f;
    private boolean hasHistory = false;

    void reset() {
        tx = 0f;
        ty = 0f;
        sx = 0f;
        sy = 0f;
        hasHistory = false;
    }

    float getTx() { return tx; }
    float getTy() { return ty; }
    float getSx() { return sx; }
    float getSy() { return sy; }

    boolean estimate(byte[] rgbaBytes, int w, int h, float maxUv) {
        if (rgbaBytes == null || w <= 0 || h <= 0 || rgbaBytes.length < w * h * 4 || maxUv <= 0f) {
            reset();
            return false;
        }

        final int totalCells = w * h;
        final int minValid = Math.max(8, (totalCells * 15) / 100);

        // Pass 1: weighted affine fit across all non-HUD, high-confidence cells.
        double sw1 = 0, sDu = 0, sDv = 0, sDu2 = 0, sDv2 = 0;
        double sVx = 0, sVy = 0, sDuVx = 0, sDvVy = 0;
        int validCount = 0;

        for (int y = 0; y < h; y++) {
            final float v = (y + 0.5f) / h;
            final float dv = v - 0.5f;
            for (int x = 0; x < w; x++) {
                final float u = (x + 0.5f) / w;
                if (isHudZone(u, v)) continue;

                final int off = (y * w + x) * 4;
                final float conf = (rgbaBytes[off + 3] & 0xFF) / 255.0f;
                if (conf < MIN_CONF) continue;

                final float vx = (((rgbaBytes[off] & 0xFF) / 255.0f) - CENTER_NORM) * 2.0f * maxUv;
                final float vy = (((rgbaBytes[off + 1] & 0xFF) / 255.0f) - CENTER_NORM) * 2.0f * maxUv;
                final float du = u - 0.5f;

                validCount++;
                sw1 += conf;
                sDu += conf * du;
                sDv += conf * dv;
                sDu2 += conf * du * du;
                sDv2 += conf * dv * dv;
                sVx += conf * vx;
                sVy += conf * vy;
                sDuVx += conf * du * vx;
                sDvVy += conf * dv * vy;
            }
        }

        if (validCount < minValid || sw1 < 1e-4) {
            reset();
            return false;
        }

        final double meanDu1 = sDu / sw1;
        final double meanDv1 = sDv / sw1;
        final double meanVx1 = sVx / sw1;
        final double meanVy1 = sVy / sw1;
        final double varDu1 = Math.max(1e-5, (sDu2 / sw1) - meanDu1 * meanDu1);
        final double varDv1 = Math.max(1e-5, (sDv2 / sw1) - meanDv1 * meanDv1);
        final float sx1 = clamp((float) (((sDuVx / sw1) - meanDu1 * meanVx1) / varDu1), -MAX_SCALE, MAX_SCALE);
        final float sy1 = clamp((float) (((sDvVy / sw1) - meanDv1 * meanVy1) / varDv1), -MAX_SCALE, MAX_SCALE);
        final float tx1 = clamp((float) (meanVx1 - sx1 * meanDu1), -maxUv, maxUv);
        final float ty1 = clamp((float) (meanVy1 - sy1 * meanDv1), -maxUv, maxUv);

        // Compute weighted MAD of residuals against pass-1 affine model.
        double madSum = 0;
        for (int y = 0; y < h; y++) {
            final float v = (y + 0.5f) / h;
            final float dv = v - 0.5f;
            for (int x = 0; x < w; x++) {
                final float u = (x + 0.5f) / w;
                if (isHudZone(u, v)) continue;

                final int off = (y * w + x) * 4;
                final float conf = (rgbaBytes[off + 3] & 0xFF) / 255.0f;
                if (conf < MIN_CONF) continue;

                final float vx = (((rgbaBytes[off] & 0xFF) / 255.0f) - CENTER_NORM) * 2.0f * maxUv;
                final float vy = (((rgbaBytes[off + 1] & 0xFF) / 255.0f) - CENTER_NORM) * 2.0f * maxUv;
                final float du = u - 0.5f;
                final double res = Math.hypot(vx - (tx1 + sx1 * du), vy - (ty1 + sy1 * dv));
                madSum += conf * res;
            }
        }

        final double mad = madSum / sw1;
        final double inlierThresh = Math.max(0.015, 2.2 * mad);

        // Pass 2: outlier-rejected Huber-weighted least squares.
        double sw2 = 0, sDu_2 = 0, sDv_2 = 0, sDu2_2 = 0, sDv2_2 = 0;
        double sVx_2 = 0, sVy_2 = 0, sDuVx_2 = 0, sDvVy_2 = 0;
        int inliers = 0;

        for (int y = 0; y < h; y++) {
            final float v = (y + 0.5f) / h;
            final float dv = v - 0.5f;
            for (int x = 0; x < w; x++) {
                final float u = (x + 0.5f) / w;
                if (isHudZone(u, v)) continue;

                final int off = (y * w + x) * 4;
                final float conf = (rgbaBytes[off + 3] & 0xFF) / 255.0f;
                if (conf < MIN_CONF) continue;

                final float vx = (((rgbaBytes[off] & 0xFF) / 255.0f) - CENTER_NORM) * 2.0f * maxUv;
                final float vy = (((rgbaBytes[off + 1] & 0xFF) / 255.0f) - CENTER_NORM) * 2.0f * maxUv;
                final float du = u - 0.5f;
                final double res = Math.hypot(vx - (tx1 + sx1 * du), vy - (ty1 + sy1 * dv));
                if (res > inlierThresh) continue;

                final double rRatio = res / inlierThresh;
                final double weight = conf / (1.0 + rRatio * rRatio);
                inliers++;
                sw2 += weight;
                sDu_2 += weight * du;
                sDv_2 += weight * dv;
                sDu2_2 += weight * du * du;
                sDv2_2 += weight * dv * dv;
                sVx_2 += weight * vx;
                sVy_2 += weight * vy;
                sDuVx_2 += weight * du * vx;
                sDvVy_2 += weight * dv * vy;
            }
        }

        if (inliers < Math.max(6, minValid / 2) || sw2 < 1e-4) {
            reset();
            return false;
        }

        final double meanDu2 = sDu_2 / sw2;
        final double meanDv2 = sDv_2 / sw2;
        final double meanVx2 = sVx_2 / sw2;
        final double meanVy2 = sVy_2 / sw2;
        final double varDu2 = Math.max(1e-5, (sDu2_2 / sw2) - meanDu2 * meanDu2);
        final double varDv2 = Math.max(1e-5, (sDv2_2 / sw2) - meanDv2 * meanDv2);

        final float rawSx = clamp((float) (((sDuVx_2 / sw2) - meanDu2 * meanVx2) / varDu2), -MAX_SCALE, MAX_SCALE);
        final float rawSy = clamp((float) (((sDvVy_2 / sw2) - meanDv2 * meanVy2) / varDv2), -MAX_SCALE, MAX_SCALE);
        final float rawTx = clamp((float) (meanVx2 - rawSx * meanDu2), -maxUv, maxUv);
        final float rawTy = clamp((float) (meanVy2 - rawSy * meanDv2), -maxUv, maxUv);

        if (!hasHistory) {
            tx = rawTx;
            ty = rawTy;
            sx = rawSx;
            sy = rawSy;
            hasHistory = true;
        } else {
            tx = tx * SMOOTH_ALPHA + rawTx * (1.0f - SMOOTH_ALPHA);
            ty = ty * SMOOTH_ALPHA + rawTy * (1.0f - SMOOTH_ALPHA);
            sx = sx * SMOOTH_ALPHA + rawSx * (1.0f - SMOOTH_ALPHA);
            sy = sy * SMOOTH_ALPHA + rawSy * (1.0f - SMOOTH_ALPHA);
        }
        return true;
    }

    private static boolean isHudZone(float u, float v) {
        return (u < 0.24f && v < 0.28f) || (u > 0.77f && v > 0.78f);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}

package com.gtasixty.spatial;

/**
 * VSync-aligned sub-frame pacer for FG60/FG90/FG120 presentation on Quest 3.
 *
 * <p>Timing model (all values are {@code System.nanoTime()} / Choreographer timebase):
 * <ul>
 *   <li>{@link #nextVsyncEdgeNs(long)}: the earliest VSync edge a buffer submitted <em>now</em> can
 *       still latch on (edge {@code >= now + leadMargin}).</li>
 *   <li>{@link #firstSubFrameDueNs(long)}: the sub-frame slot that follows a frame which was just
 *       submitted, i.e. {@code nextVsyncEdge(now) + period}.</li>
 *   <li>{@link #advanceSubFrameDueNs(long, long)}: strict cadence for consecutive sub-frames,
 *       {@code prevDue + period} snapped to the <em>nearest</em> grid edge. The previous
 *       implementation re-snapped {@code now + period + leadMargin} with a ceiling after every
 *       sub-frame swap, which pushed each consecutive sub-frame two VSyncs (~16.7 ms) out instead
 *       of one (8.33 ms) and made the deferred real frame miss its slot.</li>
 * </ul>
 *
 * <p>Choreographer callbacks may arrive at 60 Hz when SurfaceFlinger applies a per-UID
 * frame-rate override (e.g. after a {@code Surface.setFrameRate(60)} vote). The pacer therefore
 * accepts callback deltas that are integer multiples of the target period and keeps the
 * target-rate grid instead of collapsing to the callback rate.
 */
final class FramePacer {

    static final long PERIOD_120HZ_NS = 8_333_333L;
    static final long PERIOD_90HZ_NS = 11_111_111L;
    static final long DEFAULT_LEAD_MARGIN_NS = 1_500_000L; // 1.5 ms ahead of VSync latch
    /** Upper bound for how early a sub-frame is rendered ahead of its presentation slot. */
    static final long MAX_RENDER_LEAD_NS = 5_000_000L;

    private volatile long vsyncAnchorNs = 0L;
    private volatile long vsyncPeriodNs = PERIOD_120HZ_NS;
    private volatile long callbackPeriodNs = PERIOD_120HZ_NS;

    void onVsync(long frameTimeNanos, int targetHz) {
        final long expectedPeriod = (targetHz == 90) ? PERIOD_90HZ_NS : PERIOD_120HZ_NS;
        final long prev = vsyncAnchorNs;
        if (prev > 0L) {
            final long delta = frameTimeNanos - prev;
            callbackPeriodNs = delta;
            vsyncPeriodNs = calibratePeriodNs(vsyncPeriodNs, delta, expectedPeriod);
        } else {
            vsyncPeriodNs = expectedPeriod;
        }
        vsyncAnchorNs = frameTimeNanos;
    }

    /**
     * Updates the calibrated period from a Choreographer delta. Deltas that are close to an
     * integer multiple (1..4) of {@code expectedPeriod} refine the estimate; anything else (missed
     * callbacks, pauses) resets to the nominal period.
     */
    static long calibratePeriodNs(long currentPeriod, long delta, long expectedPeriod) {
        if (delta <= 0L) return expectedPeriod;
        final long n = Math.max(1L, Math.round((double) delta / (double) expectedPeriod));
        if (n > 4L) return expectedPeriod;
        final long perVsync = delta / n;
        final long tol = expectedPeriod / 6L; // ~±1.4 ms at 120 Hz
        if (Math.abs(perVsync - expectedPeriod) > tol) return expectedPeriod;
        final long base = (Math.abs(currentPeriod - expectedPeriod) > tol) ? expectedPeriod : currentPeriod;
        return (base * 3L + perVsync) / 4L;
    }

    long getVsyncAnchorNs() {
        return vsyncAnchorNs;
    }

    long getVsyncPeriodNs() {
        return vsyncPeriodNs;
    }

    /** Raw delta between the last two Choreographer callbacks (diagnostics: 16.7 ms = 60 Hz override). */
    long getCallbackPeriodNs() {
        return callbackPeriodNs;
    }

    long nextVsyncEdgeNs(long nowNs) {
        return snapToNextVsyncNs(nowNs, vsyncAnchorNs, vsyncPeriodNs, DEFAULT_LEAD_MARGIN_NS);
    }

    long firstSubFrameDueNs(long nowNs) {
        return nextVsyncEdgeNs(nowNs) + vsyncPeriodNs;
    }

    long advanceSubFrameDueNs(long prevDueNs, long nowNs) {
        return advanceDueNs(prevDueNs, nowNs, vsyncAnchorNs, vsyncPeriodNs, DEFAULT_LEAD_MARGIN_NS);
    }

    /** Presentation slot for a real frame: never shares a VSync with the previous real frame. */
    long realFrameSlotNs(long nowNs, long lastRealSlotNs) {
        return realSlotNs(nowNs, lastRealSlotNs, vsyncAnchorNs, vsyncPeriodNs, DEFAULT_LEAD_MARGIN_NS);
    }

    /** How long before its slot a sub-frame should start rendering (it carries a presentation time). */
    long subFrameRenderLeadNs() {
        return renderLeadNs(vsyncPeriodNs);
    }

    /**
     * Legacy helper: VSync slot {@code stepsAhead} periods after the edge a just-submitted frame
     * latches on.
     */
    long snapNextSubFrameNs(long nowNs, int stepsAhead) {
        return nextVsyncEdgeNs(nowNs) + Math.max(1, stepsAhead) * vsyncPeriodNs;
    }

    static long subFramePeriodNs(int mode) {
        if (mode == CaptureCadenceController.MODE_FG90 || mode == CaptureCadenceController.MODE_FG90_SR) {
            return PERIOD_90HZ_NS;
        }
        return PERIOD_120HZ_NS;
    }

    static long renderLeadNs(long periodNs) {
        return Math.max(DEFAULT_LEAD_MARGIN_NS, Math.min(MAX_RENDER_LEAD_NS, periodNs / 2L));
    }

    /**
     * Snaps {@code desiredNs} onto the nearest future VSync multiple of {@code anchorNs + k * periodNs}
     * while preserving at least {@code leadMarginNs} before the target VSync edge.
     */
    static long snapToNextVsyncNs(long desiredNs, long anchorNs, long periodNs, long leadMarginNs) {
        if (anchorNs <= 0L || periodNs <= 0L) {
            return desiredNs + Math.max(0L, leadMarginNs);
        }
        final long minTarget = desiredNs + Math.max(0L, leadMarginNs);
        final long diff = minTarget - anchorNs;
        final long k = (diff <= 0L) ? 1L : ((diff + periodNs - 1L) / periodNs);
        return anchorNs + k * periodNs;
    }

    /** Rounds {@code tNs} to the nearest VSync grid edge (no lead margin, no ceiling). */
    static long snapToNearestVsyncNs(long tNs, long anchorNs, long periodNs) {
        if (anchorNs <= 0L || periodNs <= 0L) return tNs;
        final long diff = tNs - anchorNs;
        final long k = Math.round((double) diff / (double) periodNs);
        return anchorNs + k * periodNs;
    }

    /**
     * Strict-cadence successor of {@code prevDueNs}: one period later on the grid, but never earlier
     * than the first edge still reachable from {@code nowNs}.
     */
    static long advanceDueNs(long prevDueNs, long nowNs, long anchorNs, long periodNs, long leadMarginNs) {
        final long candidate = snapToNearestVsyncNs(prevDueNs + periodNs, anchorNs, periodNs);
        final long earliest = snapToNextVsyncNs(nowNs, anchorNs, periodNs, leadMarginNs);
        return Math.max(candidate, earliest);
    }

    static long realSlotNs(long nowNs, long lastRealSlotNs, long anchorNs, long periodNs, long leadMarginNs) {
        final long earliest = snapToNextVsyncNs(nowNs, anchorNs, periodNs, leadMarginNs);
        if (lastRealSlotNs <= 0L) return earliest;
        final long afterPrevReal = snapToNearestVsyncNs(lastRealSlotNs + periodNs, anchorNs, periodNs);
        return Math.max(earliest, afterPrevReal);
    }
}

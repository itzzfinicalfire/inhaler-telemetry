package dev.forensic.telemetry.ring;

import java.util.ArrayList;
import java.util.List;

/**
 * Dropped-tick accounting: turns ring loss into durable gap semantics.
 *
 * <p>The ring reports every dropped STATE tick here. Ranges coalesce (contiguous or overlapping
 * dropped ticks merge), so a 10-second outage is one range, not 200. The uploader drains sealed
 * ranges (everything below its flushed watermark) and emits them as GAP control records on the
 * next chunk: the loss becomes part of the stream itself, in band, where the cloud cannot miss
 * it. A gap that lives only in a log file is a rumor; a gap in the stream is data.
 *
 * <p>Threading: the writer (main thread) calls {@link #noteDropped}; the reader (uploader
 * thread) calls {@link #drainSealed}. Both are synchronized. The lock is uncontended in the
 * healthy case and held only for array copies, never I/O. Main-thread block risk: none in
 * practice (the uploader never holds it across a blocking call), measured in benchmarks.
 *
 * <p>Capacity: ranges are capped at {@link #MAX_RANGES}. Beyond that the oldest ranges merge
 * into one spanning range (from = oldest.from, to = newest.to). Merging widens the reported
 * hole rather than dropping it: over-reporting loss is safe (analysis skips), under-reporting
 * is fabrication. Merged ranges keep the highest-severity reason: a hole that contains an
 * internal error is reported as an internal error, not as routine overflow.
 */
public final class GapTracker {

    static final int MAX_RANGES = 256;

    private final List<Range> ranges = new ArrayList<>();
    private volatile int totalDroppedTicks;

    /**
     * Default reason for ring-overflow drops. The uploader thread flips this to SEND_FAILURE
     * while unhealthy, so drops caused by a failing transport are attributed to the transport,
     * not to burst volume. Volatile: set rarely, read on every drop.
     */
    private volatile int defaultReason = dev.forensic.telemetry.record.ControlKind.GAP_RING_OVERFLOW;

    public void setDefaultReason(int reason) {
        this.defaultReason = reason;
    }

    /** Records one dropped tick with the current default reason. */
    public synchronized void noteDropped(int tick) {
        noteDropped(tick, defaultReason);
    }

    /** Records one dropped tick with an explicit reason (e.g. control loss). */
    public synchronized void noteDropped(int tick, int reason) {
        noteDropped(tick, reason, 1);
    }

    private synchronized void noteDropped(int tick, int reason, int snapshots) {
        totalDroppedTicks++;
        // Merge with the last range when contiguous/overlapping. Ranges arrive
        // roughly in tick order (ring eviction is FIFO), so last-range merge
        // covers the overwhelmingly common case in O(1).
        if (!ranges.isEmpty()) {
            Range last = ranges.get(ranges.size() - 1);
            if (tick >= last.from - 1 && tick <= last.to + 1) {
                last.from = Math.min(last.from, tick);
                last.to = Math.max(last.to, tick);
                last.reason = worse(last.reason, reason);
                last.droppedSnapshots += snapshots;
                return;
            }
        }
        // Out-of-order tick: linear insert (rare; dropped-head case only).
        for (Range r : ranges) {
            if (tick >= r.from - 1 && tick <= r.to + 1) {
                r.from = Math.min(r.from, tick);
                r.to = Math.max(r.to, tick);
                r.reason = worse(r.reason, reason);
                r.droppedSnapshots += snapshots;
                coalesce();
                return;
            }
        }
        ranges.add(new Range(tick, tick, reason, snapshots));
        if (ranges.size() > MAX_RANGES) {
            mergeOldest();
        }
    }

    /**
     * Records a whole dropped span (e.g. a poison chunk the server rejected).
     *
     * @param snapshots state records known lost in the span; 0 when unknown (the span is
     *                  still reported with its ticks, which is what analysis gates on)
     */
    public synchronized void noteDroppedRange(int from, int to, int reason, int snapshots) {
        if (to < from) {
            return;
        }
        totalDroppedTicks += Math.max(0, to - from + 1);
        ranges.add(new Range(from, to, reason, snapshots));
        coalesce();
        while (ranges.size() > MAX_RANGES) {
            mergeOldest();
        }
    }

    private static int severity(int reason) {
        if (reason == dev.forensic.telemetry.record.ControlKind.GAP_INTERNAL_ERROR) {
            return 3;
        }
        if (reason == dev.forensic.telemetry.record.ControlKind.GAP_SEND_FAILURE) {
            return 2;
        }
        return 1;
    }

    private static int worse(int a, int b) {
        return severity(b) > severity(a) ? b : a;
    }

    private void coalesce() {
        ranges.sort((a, b) -> Integer.compare(a.from, b.from));
        List<Range> merged = new ArrayList<>(ranges.size());
        for (Range r : ranges) {
            if (!merged.isEmpty()) {
                Range last = merged.get(merged.size() - 1);
                if (r.from <= last.to + 1) {
                    last.to = Math.max(last.to, r.to);
                    last.reason = worse(last.reason, r.reason);
                    last.droppedSnapshots += r.droppedSnapshots;
                    continue;
                }
            }
            merged.add(r);
        }
        ranges.clear();
        ranges.addAll(merged);
    }

    private void mergeOldest() {
        ranges.sort((a, b) -> Integer.compare(a.from, b.from));
        Range first = ranges.get(0);
        Range second = ranges.get(1);
        first.to = Math.max(first.to, second.to);
        first.reason = worse(first.reason, second.reason);
        first.droppedSnapshots += second.droppedSnapshots;
        ranges.remove(1);
    }

    /**
     * Removes and returns all ranges fully below {@code watermarkTick} (exclusive upper bound
     * {@code to < watermarkTick}). Ranges at or above the watermark may still grow; returning
     * them early would split one outage into two gap records.
     */
    public synchronized List<Range> drainSealed(int watermarkTick) {
        List<Range> out = new ArrayList<>();
        ranges.removeIf(r -> {
            if (r.to < watermarkTick) {
                out.add(new Range(r.from, r.to, r.reason, r.droppedSnapshots));
                return true;
            }
            return false;
        });
        return out;
    }

    public synchronized int pendingRangeCount() {
        return ranges.size();
    }

    public int totalDroppedTicks() {
        return totalDroppedTicks;
    }

    /** Immutable snapshot of one dropped range. */
    public static final class Range {
        int from;
        int to;
        int reason;
        int droppedSnapshots;

        Range(int from, int to, int reason, int droppedSnapshots) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.droppedSnapshots = droppedSnapshots;
        }

        public int from() {
            return from;
        }

        public int to() {
            return to;
        }

        public int reason() {
            return reason;
        }

        public int droppedSnapshots() {
            return droppedSnapshots;
        }

        @Override
        public String toString() {
            return from + "-" + to + "(reason=" + reason + ",snapshots=" + droppedSnapshots + ")";
        }
    }
}

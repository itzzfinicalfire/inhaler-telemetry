package dev.forensic.telemetry.ring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.record.ControlKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GapTrackerTest {

    @Test
    @DisplayName("contiguous drops coalesce into one range")
    void coalesces() {
        GapTracker gaps = new GapTracker();
        for (int t = 100; t < 110; t++) {
            gaps.noteDropped(t);
        }
        List<GapTracker.Range> sealed = gaps.drainSealed(200);
        assertEquals(1, sealed.size());
        assertEquals(100, sealed.get(0).from());
        assertEquals(109, sealed.get(0).to());
        assertEquals(10, gaps.totalDroppedTicks());
    }

    @Test
    @DisplayName("disjoint drops stay disjoint")
    void disjoint() {
        GapTracker gaps = new GapTracker();
        gaps.noteDropped(10);
        gaps.noteDropped(50);
        List<GapTracker.Range> sealed = gaps.drainSealed(100);
        assertEquals(2, sealed.size());
    }

    @Test
    @DisplayName("ranges at/above the watermark do not drain early")
    void watermark() {
        GapTracker gaps = new GapTracker();
        gaps.noteDropped(10);
        gaps.noteDropped(90);
        gaps.noteDropped(91);
        gaps.noteDropped(95);
        // Ranges: [10,10], [90,91] (merged), [95,95] (disjoint: 5 apart).
        List<GapTracker.Range> first = gaps.drainSealed(90);
        assertEquals(1, first.size());
        assertEquals(10, first.get(0).from());
        assertEquals(2, gaps.pendingRangeCount());
        // to < watermark is strict: [90,91] stays at watermark 91...
        assertEquals(0, gaps.drainSealed(91).size());
        // ...and drains at 92.
        List<GapTracker.Range> second = gaps.drainSealed(92);
        assertEquals(1, second.size());
        assertEquals(90, second.get(0).from());
        assertEquals(91, second.get(0).to());
        List<GapTracker.Range> third = gaps.drainSealed(100);
        assertEquals(1, third.size());
        assertEquals(95, third.get(0).from());
    }

    @Test
    @DisplayName("merged ranges keep the worst reason")
    void severityWins() {
        GapTracker gaps = new GapTracker();
        gaps.noteDropped(10, ControlKind.GAP_RING_OVERFLOW);
        gaps.noteDropped(11, ControlKind.GAP_INTERNAL_ERROR);
        List<GapTracker.Range> sealed = gaps.drainSealed(100);
        assertEquals(1, sealed.size());
        assertEquals(ControlKind.GAP_INTERNAL_ERROR, sealed.get(0).reason());
    }

    @Test
    @DisplayName("default reason flips with uploader health")
    void defaultReasonFlips() {
        GapTracker gaps = new GapTracker();
        gaps.setDefaultReason(ControlKind.GAP_SEND_FAILURE);
        gaps.noteDropped(5);
        List<GapTracker.Range> sealed = gaps.drainSealed(100);
        assertEquals(ControlKind.GAP_SEND_FAILURE, sealed.get(0).reason());
    }

    @Test
    @DisplayName("range cap merges oldest instead of dropping")
    void capMerges() {
        GapTracker gaps = new GapTracker();
        // 300 isolated drops (step 2): forces the 256 cap.
        for (int t = 0; t < 600; t += 2) {
            gaps.noteDropped(t);
        }
        assertTrue(gaps.pendingRangeCount() <= GapTracker.MAX_RANGES);
        assertEquals(300, gaps.totalDroppedTicks());
        // Nothing silently vanished: drain everything and check span coverage.
        List<GapTracker.Range> all = gaps.drainSealed(Integer.MAX_VALUE);
        assertEquals(0, all.get(0).from());
        int covered = 0;
        for (GapTracker.Range r : all) {
            covered += r.to() - r.from() + 1;
        }
        // Merged ranges widen: covered span >= dropped ticks, never less.
        assertTrue(covered >= 300, "merged span must cover every dropped tick");
    }

    @Test
    @DisplayName("whole-span notation records exact counts")
    void spanNotation() {
        GapTracker gaps = new GapTracker();
        gaps.noteDroppedRange(1000, 1099, ControlKind.GAP_SEND_FAILURE, 200);
        List<GapTracker.Range> sealed = gaps.drainSealed(2000);
        assertEquals(1, sealed.size());
        assertEquals(1000, sealed.get(0).from());
        assertEquals(1099, sealed.get(0).to());
        assertEquals(200, sealed.get(0).droppedSnapshots());
        assertEquals(ControlKind.GAP_SEND_FAILURE, sealed.get(0).reason());
    }

    @Test
    @DisplayName("inverted span is ignored, not stored")
    void invertedSpanIgnored() {
        GapTracker gaps = new GapTracker();
        gaps.noteDroppedRange(50, 40, ControlKind.GAP_RING_OVERFLOW, 0);
        assertEquals(0, gaps.pendingRangeCount());
    }
}

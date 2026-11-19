package dev.forensic.telemetry.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordLayoutTest {

    @Test
    @DisplayName("state record is exactly 32 bytes with fixed offsets")
    void offsetsAreStable() {
        assertEquals(32, RecordLayout.RECORD_BYTES);
        assertEquals(0, RecordLayout.OFF_TICK);
        assertEquals(4, RecordLayout.OFF_ENTITY_ID);
        assertEquals(8, RecordLayout.OFF_X);
        assertEquals(28, RecordLayout.OFF_FLAGS);
        assertEquals(30, RecordLayout.OFF_PING);
    }

    @Test
    @DisplayName("any non-negative tick has a high byte below 0x80")
    void stateTicksNeverAliasControl() {
        // The entire kind-discrimination scheme rests on this: int32 ticks are
        // non-negative, so the high byte is 0x00-0x7F for every tick that can
        // ever exist, including Integer.MAX_VALUE (3.4 years of uptime).
        for (int tick : new int[] {0, 1, 72000, Integer.MAX_VALUE}) {
            ByteBuffer buf = ByteBuffer.allocate(32);
            buf.putInt(0, tick);
            assertFalse(
                    ControlKind.isControlByte(buf.get(0)),
                    "tick " + tick + " aliases a control marker");
        }
    }

    @Test
    @DisplayName("control markers are unique and inside 0x80-0xFF")
    void markersAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ControlKind kind : ControlKind.values()) {
            assertTrue(kind.marker() >= 0x80 && kind.marker() <= 0xFF);
            assertTrue(seen.add(kind.marker()), "duplicate marker for " + kind);
            assertEquals(kind, ControlKind.fromMarker(kind.marker()));
        }
    }

    @Test
    @DisplayName("unknown marker throws rather than decoding as something else")
    void unknownMarkerThrows() {
        assertThrows(IllegalArgumentException.class, () -> ControlKind.fromMarker(0x7F));
        assertThrows(IllegalArgumentException.class, () -> ControlKind.fromMarker(0xFF));
    }

    @Test
    @DisplayName("flag bits do not overlap")
    void flagsDoNotOverlap() {
        int[] flags = {
            RecordLayout.FLAG_GROUND,
            RecordLayout.FLAG_SPRINT,
            RecordLayout.FLAG_SNEAK,
            RecordLayout.FLAG_FLY,
            RecordLayout.FLAG_SWIM,
            RecordLayout.FLAG_GLIDE,
            RecordLayout.FLAG_CLIMB,
            RecordLayout.FLAG_RIPTIDE
        };
        int combined = 0;
        for (int f : flags) {
            assertEquals(0, combined & f, "overlapping flag bit");
            combined |= f;
        }
        assertTrue(combined <= 0xFFFF, "flags must fit uint16");
    }

    @Test
    @DisplayName("cloud gap ordinals match the documented order")
    void gapOrdinalsMatchCloud() {
        assertEquals(0, ControlKind.GAP_RING_OVERFLOW);
        assertEquals(1, ControlKind.GAP_SEND_FAILURE);
        assertEquals(2, ControlKind.GAP_SESSION_START);
        assertEquals(3, ControlKind.GAP_PLUGIN_DISABLE);
        assertEquals(4, ControlKind.GAP_QUEUE_DROPPED);
        assertEquals(5, ControlKind.GAP_INTERNAL_ERROR);
        assertEquals(6, ControlKind.GAP_EVENT_QUEUE_OVERFLOW);
    }
}

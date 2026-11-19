package dev.forensic.telemetry.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordEncoderTest {

    private static ByteBuffer slot() {
        return ByteBuffer.allocateDirect(32);
    }

    @Test
    @DisplayName("state record round-trips every field bit-exactly")
    void stateRoundTrip() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeState(buf, 0, 72000, 1234, 100.5, 64.0, -200.25, 90.0f, -45.0f,
                RecordLayout.FLAG_GROUND | RecordLayout.FLAG_SPRINT, 38);
        assertEquals(72000, buf.getInt(0));
        assertEquals(1234, buf.getInt(4));
        assertEquals(100.5f, buf.getFloat(8));
        assertEquals(64.0f, buf.getFloat(12));
        assertEquals(-200.25f, buf.getFloat(16));
        assertEquals(90.0f, buf.getFloat(20));
        assertEquals(-45.0f, buf.getFloat(24));
        assertEquals(RecordLayout.FLAG_GROUND | RecordLayout.FLAG_SPRINT,
                buf.getShort(28) & 0xFFFF);
        assertEquals(38, buf.getShort(30) & 0xFFFF);
    }

    @Test
    @DisplayName("ping clamps to uint16 and negative ping becomes 0")
    void pingClamps() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeState(buf, 0, 1, 1, 0, 0, 0, 0, 0, 0, 999999);
        assertEquals(0xFFFF, buf.getShort(30) & 0xFFFF);
        RecordEncoder.encodeState(buf, 0, 1, 1, 0, 0, 0, 0, 0, 0, -5);
        assertEquals(0, buf.getShort(30) & 0xFFFF);
    }

    @Test
    @DisplayName("position keeps float32 precision (no quantization)")
    void positionIsFullPrecision() {
        ByteBuffer buf = slot();
        double x = 123.456789;
        RecordEncoder.encodeState(buf, 0, 1, 1, x, 0, 0, 0, 0, 0, 0);
        // float32 of the double, exactly: transport narrows double->float once,
        // openly, and never quantizes below float32.
        assertEquals((float) x, buf.getFloat(8));
        assertTrue(buf.getFloat(8) != (float) ((int) x), "must not be block-quantized");
    }

    @Test
    @DisplayName("attack record layout")
    void attackLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeAttack(buf, 0, 500, 11, 22, 6.5f);
        assertEquals(ControlKind.ATTACK, ControlKind.fromMarker(buf.get(0)));
        assertEquals(500, buf.getInt(4));
        assertEquals(11, buf.getInt(8));
        assertEquals(22, buf.getInt(12));
        assertEquals(6.5f, buf.getFloat(16));
    }

    @Test
    @DisplayName("damage record layout with environmental damager")
    void damageLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeDamage(buf, 0, 501, 22, 4.0f, 3, -1);
        assertEquals(ControlKind.DAMAGE, ControlKind.fromMarker(buf.get(0)));
        assertEquals(501, buf.getInt(4));
        assertEquals(22, buf.getInt(8));
        assertEquals(4.0f, buf.getFloat(12));
        assertEquals(3, buf.get(16));
        assertEquals(-1, buf.getInt(20));
    }

    @Test
    @DisplayName("gap record layout")
    void gapLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeGap(buf, 0, 900, 800, 850, ControlKind.GAP_RING_OVERFLOW);
        assertEquals(ControlKind.GAP, ControlKind.fromMarker(buf.get(0)));
        assertEquals(900, buf.getInt(4));
        assertEquals(800, buf.getInt(8));
        assertEquals(850, buf.getInt(12));
        assertEquals(ControlKind.GAP_RING_OVERFLOW, buf.get(16));
    }

    @Test
    @DisplayName("control reserved bytes are zero")
    void controlReservedZero() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeDeath(buf, 0, 1, 9);
        assertEquals(0, buf.get(1));
        assertEquals(0, buf.get(2));
        assertEquals(0, buf.get(3));
        // Tail zeroed: no stale bytes from a previous record in a reused slot.
        for (int i = 12; i < 32; i++) {
            assertEquals(0, buf.get(i), "byte " + i + " not zeroed");
        }
    }

    @Test
    @DisplayName("encoding at a nonzero base offset works (ring slots)")
    void nonzeroBase() {
        ByteBuffer buf = ByteBuffer.allocateDirect(64);
        RecordEncoder.encodeState(buf, 32, 7, 7, 1, 2, 3, 0, 0, 0, 0);
        assertEquals(7, buf.getInt(32));
        assertEquals(1.0f, buf.getFloat(40));
        assertEquals(0, buf.getInt(0), "must not touch slot 0");
    }
}

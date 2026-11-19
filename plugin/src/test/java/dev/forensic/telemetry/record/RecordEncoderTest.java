package dev.forensic.telemetry.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        RecordEncoder.encodeAttack(buf, 0, 500, 7, 11, 22, 6.5f);
        assertEquals(ControlKind.ATTACK, ControlKind.fromMarker(buf.get(0)));
        assertEquals(7, buf.get(RecordLayout.OFF_CTRL_SEQ) & 0xFF);
        assertEquals(500, buf.getInt(4));
        assertEquals(11, buf.getInt(8));
        assertEquals(22, buf.getInt(12));
        assertEquals(6.5f, buf.getFloat(16));
    }

    @Test
    @DisplayName("damage record layout with environmental damager")
    void damageLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeDamage(buf, 0, 501, 0, 22, 4.0f, 3, -1);
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
        RecordEncoder.encodeGap(buf, 0, 900, 0, 800, 850, ControlKind.GAP_RING_OVERFLOW);
        assertEquals(ControlKind.GAP, ControlKind.fromMarker(buf.get(0)));
        assertEquals(900, buf.getInt(4));
        assertEquals(800, buf.getInt(8));
        assertEquals(850, buf.getInt(12));
        assertEquals(ControlKind.GAP_RING_OVERFLOW, buf.get(16));
    }

    @Test
    @DisplayName("held-slot record layout")
    void heldSlotLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeHeldSlot(buf, 0, 10, 3, 7, 0, 4,
                ItemCategory.SWORD, ItemCategory.MACE);
        assertEquals(ControlKind.HELD_SLOT, ControlKind.fromMarker(buf.get(0)));
        assertEquals(3, buf.get(1) & 0xFF);
        assertEquals(10, buf.getInt(4));
        assertEquals(7, buf.getInt(8));
        assertEquals(0, buf.get(12));
        assertEquals(4, buf.get(13));
        assertEquals(ItemCategory.SWORD, buf.get(14) & 0xFF);
        assertEquals(ItemCategory.MACE, buf.get(15) & 0xFF);
    }

    @Test
    @DisplayName("place record layout")
    void placeLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodePlace(buf, 0, 11, 0, 7, 10, 64, -3,
                ItemCategory.OBSIDIAN, ControlFlags.PLACE_CANCELLED);
        assertEquals(ControlKind.PLACE, ControlKind.fromMarker(buf.get(0)));
        assertEquals(11, buf.getInt(4));
        assertEquals(7, buf.getInt(8));
        assertEquals(10, buf.getInt(12));
        assertEquals(64, buf.getInt(16));
        assertEquals(-3, buf.getInt(20));
        assertEquals(ItemCategory.OBSIDIAN, buf.get(24) & 0xFF);
        assertEquals(ControlFlags.PLACE_CANCELLED, buf.get(25) & 0xFF);
    }

    @Test
    @DisplayName("interact record layout")
    void interactLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeInteract(buf, 0, 12, 0, 7, 10, 64, -3,
                InteractKind.ANCHOR_CHARGE, ItemCategory.GLOWSTONE,
                ControlFlags.INTERACT_CANCELLED | ControlFlags.INTERACT_OFF_HAND);
        assertEquals(ControlKind.INTERACT, ControlKind.fromMarker(buf.get(0)));
        assertEquals(12, buf.getInt(4));
        assertEquals(7, buf.getInt(8));
        assertEquals(10, buf.getInt(12));
        assertEquals(64, buf.getInt(16));
        assertEquals(-3, buf.getInt(20));
        assertEquals(InteractKind.ANCHOR_CHARGE, buf.get(24) & 0xFF);
        assertEquals(ItemCategory.GLOWSTONE, buf.get(25) & 0xFF);
        assertEquals(ControlFlags.INTERACT_CANCELLED | ControlFlags.INTERACT_OFF_HAND,
                buf.get(26) & 0xFF);
    }

    @Test
    @DisplayName("swing record layout")
    void swingLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeSwing(buf, 0, 13, 0, 7, 0, 0);
        assertEquals(ControlKind.SWING, ControlKind.fromMarker(buf.get(0)));
        assertEquals(13, buf.getInt(4));
        assertEquals(7, buf.getInt(8));
        assertEquals(0, buf.get(12));
    }

    @Test
    @DisplayName("crystal spawn and break layouts")
    void crystalLayouts() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeCrystalSpawn(buf, 0, 14, 1, 42, 10.5f, 64.0f, -3.25f, 7);
        assertEquals(ControlKind.CRYSTAL_SPAWN, ControlKind.fromMarker(buf.get(0)));
        assertEquals(14, buf.getInt(4));
        assertEquals(42, buf.getInt(8));
        assertEquals(10.5f, buf.getFloat(12));
        assertEquals(64.0f, buf.getFloat(16));
        assertEquals(-3.25f, buf.getFloat(20));
        assertEquals(7, buf.getInt(24));

        RecordEncoder.encodeCrystalBreak(buf, 0, 15, 2, 42, 7, 10.5f, 64.0f, -3.25f);
        assertEquals(ControlKind.CRYSTAL_BREAK, ControlKind.fromMarker(buf.get(0)));
        assertEquals(15, buf.getInt(4));
        assertEquals(42, buf.getInt(8));
        assertEquals(7, buf.getInt(12));
        assertEquals(10.5f, buf.getFloat(16));
        assertEquals(64.0f, buf.getFloat(20));
        assertEquals(-3.25f, buf.getFloat(24));
    }

    @Test
    @DisplayName("anchor explode, totem pop and wind charge layouts")
    void otherCombatLayouts() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeAnchorExplode(buf, 0, 16, 0, 10.5f, 64.5f, -2.5f, -1);
        assertEquals(ControlKind.ANCHOR_EXPLODE, ControlKind.fromMarker(buf.get(0)));
        assertEquals(16, buf.getInt(4));
        assertEquals(10.5f, buf.getFloat(8));
        assertEquals(64.5f, buf.getFloat(12));
        assertEquals(-2.5f, buf.getFloat(16));
        assertEquals(-1, buf.getInt(20));

        RecordEncoder.encodeTotemPop(buf, 0, 17, 0, 8);
        assertEquals(ControlKind.TOTEM_POP, ControlKind.fromMarker(buf.get(0)));
        assertEquals(8, buf.getInt(8));

        RecordEncoder.encodeWindCharge(buf, 0, 18, 0, 7, 91);
        assertEquals(ControlKind.WIND_CHARGE, ControlKind.fromMarker(buf.get(0)));
        assertEquals(7, buf.getInt(8));
        assertEquals(91, buf.getInt(12));
    }

    @Test
    @DisplayName("attack context layout carries the mace/crit inputs")
    void attackCtxLayout() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeAttackCtx(buf, 0, 19, 0, 7, 2.5f, 0.875f,
                ItemCategory.MACE,
                ControlFlags.ATTACK_VICTIM_PLAYER | ControlFlags.ATTACK_ATTACKER_SPRINTING,
                VictimCategory.PLAYER);
        assertEquals(ControlKind.ATTACK_CTX, ControlKind.fromMarker(buf.get(0)));
        assertEquals(19, buf.getInt(4));
        assertEquals(7, buf.getInt(8));
        assertEquals(2.5f, buf.getFloat(12));
        assertEquals(0.875f, buf.getFloat(16));
        assertEquals(ItemCategory.MACE, buf.get(20) & 0xFF);
        assertEquals(ControlFlags.ATTACK_VICTIM_PLAYER | ControlFlags.ATTACK_ATTACKER_SPRINTING,
                buf.get(21) & 0xFF);
        assertEquals(VictimCategory.PLAYER, buf.get(22) & 0xFF);
    }

    @Test
    @DisplayName("control reserved bytes and tail are zero")
    void controlReservedZero() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeDeath(buf, 0, 1, 0, 9);
        assertEquals(0, buf.get(1));
        assertEquals(0, buf.get(2));
        assertEquals(0, buf.get(3));
        // Tail zeroed: no stale bytes from a previous record in a reused slot.
        for (int i = 12; i < 32; i++) {
            assertEquals(0, buf.get(i), "byte " + i + " not zeroed");
        }
    }

    @Test
    @DisplayName("sequence wraps at 256 and is stored unsigned")
    void sequenceWraps() {
        ByteBuffer buf = slot();
        RecordEncoder.encodeSwing(buf, 0, 1, 255, 7, 0, 0);
        assertEquals(255, buf.get(1) & 0xFF);
        RecordEncoder.encodeSwing(buf, 0, 1, 256, 7, 0, 0);
        assertEquals(0, buf.get(1) & 0xFF);
    }

    @Test
    @DisplayName("unknown control markers are recognised as control but not mapped")
    void unknownMarker() {
        assertNull(ControlKind.fromMarkerOrNull(0x92));
        assertTrue(ControlKind.isControlByte(0x92));
        assertTrue(ControlKind.isControlByte(0xFF));
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

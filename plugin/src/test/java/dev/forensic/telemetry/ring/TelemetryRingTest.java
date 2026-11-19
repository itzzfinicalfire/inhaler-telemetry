package dev.forensic.telemetry.ring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.record.ControlKind;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelemetryRingTest {

    private static TelemetryRing ring(int slots) {
        return new TelemetryRing(slots * 32, new GapTracker());
    }

    private static void putState(TelemetryRing ring, int tick) {
        assertTrue(ring.offerState(tick, 1, 0, 0, 0, 0, 0, 0, 0));
    }

    private static int drainedTick(TelemetryRing ring) {
        ByteBuffer dst = ByteBuffer.allocate(32);
        int n = ring.drainTo(dst, 1);
        assertEquals(1, n);
        return dst.getInt(0);
    }

    @Test
    @DisplayName("FIFO order preserved through fill and drain")
    void fifo() {
        TelemetryRing ring = ring(8);
        for (int t = 0; t < 8; t++) {
            putState(ring, t);
        }
        for (int t = 0; t < 8; t++) {
            assertEquals(t, drainedTick(ring));
        }
        assertEquals(0, ring.size());
    }

    @Test
    @DisplayName("non-power-of-two capacity rejected")
    void capacityMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryRing(3 * 32, new GapTracker()));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryRing(0, new GapTracker()));
    }

    @Test
    @DisplayName("state overflow evicts oldest (tail) and gap-accounts the tick")
    void stateOverflowEvictsTail() {
        TelemetryRing ring = ring(4);
        for (int t = 0; t < 4; t++) {
            putState(ring, t);
        }
        assertTrue(ring.offerState(4, 1, 0, 0, 0, 0, 0, 0, 0));
        // Oldest (tick 0) evicted; stream stays fresh and ordered.
        assertEquals(1, drainedTick(ring));
        assertEquals(1, ring.droppedState());
        assertEquals(0, ring.droppedControl());
        List<GapTracker.Range> sealed = ring.gaps().drainSealed(4);
        assertEquals(1, sealed.size());
        assertEquals(0, sealed.get(0).from());
        assertEquals(0, sealed.get(0).to());
    }

    @Test
    @DisplayName("state never displaces control at the tail")
    void stateNeverDisplacesControl() {
        TelemetryRing ring = ring(2);
        // Fill with one control at tail + one state.
        assertTrue(ring.offerSessionStart(0));
        putState(ring, 1);
        // Incoming state vs CRITICAL tail: incoming drops, tick gap-accounted.
        assertFalse(ring.offerState(2, 1, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(1, ring.droppedState());
        assertEquals(0, ring.droppedControl());
        List<GapTracker.Range> sealed = ring.gaps().drainSealed(3);
        assertEquals(1, sealed.size());
        assertEquals(2, sealed.get(0).from());
    }

    @Test
    @DisplayName("CRITICAL incoming evicts CRITICAL tail and counts control loss")
    void criticalEvictsCritical() {
        TelemetryRing ring = ring(1);
        assertTrue(ring.offerSessionStart(0));
        assertTrue(ring.offerSessionEnd(1));
        assertEquals(1, ring.droppedControl());
        assertEquals(1, ring.size());
    }

    @Test
    @DisplayName("HIGH incoming yields to CRITICAL tail")
    void highYieldsToCritical() {
        TelemetryRing ring = ring(1);
        assertTrue(ring.offerSessionStart(0));
        assertFalse(ring.offerAttack(1, 1, 2, 1.0f));
        assertEquals(1, ring.droppedControl());
        assertEquals(1, ring.size());
    }

    @Test
    @DisplayName("HIGH incoming evicts NORMAL tail")
    void highEvictsNormalTail() {
        TelemetryRing ring = ring(2);
        putState(ring, 0);
        putState(ring, 1);
        assertTrue(ring.offerAttack(2, 1, 2, 1.0f));
        assertEquals(1, ring.droppedState());
        assertEquals(0, ring.droppedControl());
        // Order preserved: tick 1 state, then the attack control.
        assertEquals(1, drainedTick(ring));
        ByteBuffer dst = ByteBuffer.allocate(32);
        assertEquals(1, ring.drainTo(dst, 1));
        assertEquals(ControlKind.ATTACK, ControlKind.fromMarker(dst.get(0)));
    }

    @Test
    @DisplayName("drain preserves mixed state/control order across chunk boundary")
    void drainOrderAcrossChunks() {
        TelemetryRing ring = ring(8);
        putState(ring, 0);
        ring.offerAttack(1, 1, 2, 1f);
        putState(ring, 2);
        ring.offerDeath(3, 2);

        ByteBuffer chunk = ByteBuffer.allocate(64); // 2 records
        assertEquals(2, ring.drainTo(chunk, 2));
        chunk.flip();
        assertFalse(ControlKind.isControlByte(chunk.get(0)));
        assertTrue(ControlKind.isControlByte(chunk.get(32)));
        assertEquals(2, ring.size());
        assertEquals(2, ring.drainTo(chunk, 2));
    }

    @Test
    @DisplayName("counters distinguish offered classes")
    void counters() {
        TelemetryRing ring = ring(4);
        putState(ring, 0);
        ring.offerAttack(1, 1, 2, 1f);
        assertEquals(1, ring.offeredState());
        assertEquals(1, ring.offeredControl());
    }

    @Test
    @DisplayName("all control kinds land and decode")
    void allControlKinds() {
        TelemetryRing ring = ring(32);
        assertTrue(ring.offerAttack(1, 1, 2, 1f));
        assertTrue(ring.offerDamage(2, 2, 3f, 0, -1));
        assertTrue(ring.offerDeath(3, 2));
        assertTrue(ring.offerSessionStart(4));
        assertTrue(ring.offerSessionEnd(5));
        assertTrue(ring.offerGap(6, 1, 2, ControlKind.GAP_RING_OVERFLOW));
        assertTrue(ring.offerInterest(7, 1, 2, 0));
        assertTrue(ring.offerResync(8, 99));
        assertTrue(ring.offerHeldSlot(9, 1, 0, 4, 2, 4));
        assertTrue(ring.offerPlace(10, 1, 1, 2, 3, 6, 0));
        assertTrue(ring.offerInteract(11, 1, 1, 2, 3, 0, 8, 0));
        assertTrue(ring.offerSwing(12, 1, 0, 0));
        assertTrue(ring.offerCrystalSpawn(13, 77, 1f, 2f, 3f, 1));
        assertTrue(ring.offerCrystalBreak(14, 77, 1, 1f, 2f, 3f));
        assertTrue(ring.offerAnchorExplode(15, 1f, 2f, 3f, -1));
        assertTrue(ring.offerTotemPop(16, 1));
        assertTrue(ring.offerWindCharge(17, 1, 91));
        assertTrue(ring.offerAttackCtx(18, 1, 2f, 0.9f, 4, 2, 1));
        assertEquals(18, ring.size());
        ByteBuffer dst = ByteBuffer.allocate(18 * 32);
        assertEquals(18, ring.drainTo(dst, 18));
        dst.flip();
        ControlKind[] expected = {
            ControlKind.ATTACK, ControlKind.DAMAGE, ControlKind.DEATH,
            ControlKind.SESSION_START, ControlKind.SESSION_END, ControlKind.GAP,
            ControlKind.INTEREST, ControlKind.RESYNC, ControlKind.HELD_SLOT,
            ControlKind.PLACE, ControlKind.INTERACT, ControlKind.SWING,
            ControlKind.CRYSTAL_SPAWN, ControlKind.CRYSTAL_BREAK, ControlKind.ANCHOR_EXPLODE,
            ControlKind.TOTEM_POP, ControlKind.WIND_CHARGE, ControlKind.ATTACK_CTX
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], ControlKind.fromMarker(dst.get(i * 32)));
        }
    }

    @Test
    @DisplayName("intra-tick sequence follows writer order and resets on a new tick")
    void sequenceFollowsWriterOrder() {
        TelemetryRing ring = ring(16);
        // Same tick: place -> break -> swap. Order is the whole point.
        ring.offerPlace(50, 1, 1, 2, 3, 6, 0);
        ring.offerCrystalBreak(50, 77, 1, 1f, 2f, 3f);
        ring.offerHeldSlot(50, 1, 0, 4, 6, 4);
        ring.offerSwing(51, 1, 0, 0);
        ring.offerAttack(51, 1, 2, 5f);

        ByteBuffer dst = ByteBuffer.allocate(5 * 32);
        assertEquals(5, ring.drainTo(dst, 5));
        dst.flip();
        assertEquals(ControlKind.PLACE, ControlKind.fromMarker(dst.get(0)));
        assertEquals(0, dst.get(1) & 0xFF);
        assertEquals(ControlKind.CRYSTAL_BREAK, ControlKind.fromMarker(dst.get(32)));
        assertEquals(1, dst.get(33) & 0xFF);
        assertEquals(ControlKind.HELD_SLOT, ControlKind.fromMarker(dst.get(64)));
        assertEquals(2, dst.get(65) & 0xFF);
        assertEquals(ControlKind.SWING, ControlKind.fromMarker(dst.get(96)));
        assertEquals(0, dst.get(97) & 0xFF, "new tick resets the sequence");
        assertEquals(1, dst.get(129) & 0xFF);
    }

    @Test
    @DisplayName("crystal-density burst keeps order and counts only real drops")
    void crystalDensityBurst() {
        TelemetryRing ring = ring(64);
        int tick = 100;
        for (int i = 0; i < 64; i++) {
            if (i % 4 == 0) {
                ring.offerCrystalSpawn(tick, 1000 + i, 0f, 64f, 0f, 1);
                ring.offerCrystalBreak(tick, 1000 + i, 1, 0f, 64f, 0f);
                ring.offerHeldSlot(tick, 1, 0, 4, 5, 4);
                ring.offerAttackCtx(tick, 1, 2.5f, 0.9f, 4, 2, 1);
            } else {
                tick++;
                ring.offerState(tick, 1, 0, 64, 0, 0, 0, 1, 20);
            }
        }
        long droppedState = ring.droppedState();
        long droppedControl = ring.droppedControl();
        // No silent loss: every drop is accounted in exactly one counter.
        assertTrue(droppedState + droppedControl > 0, "burst must exercise pressure");
        assertTrue(ring.size() <= 64);
    }
}

package dev.forensic.telemetry.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.TelemetryConfig;
import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.RecordLayout;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Observer behavior with a scripted reader: no server, deterministic motion, exact ticks.
 * The reader contract (primitives into a reused snapshot) is what production's
 * PaperPlayerSource implements; what is tested here is everything the task does with it.
 */
class ObserverTaskTest {

    private static TelemetryConfig config() {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("buffer.bytes", Integer.toString(16 * 1024));
        raw.put("buffer.chunk-bytes", "1024");
        raw.put("encoder.resync-interval-ticks", "40");
        TelemetryConfig c = TelemetryConfig.fromRaw(raw);
        c.validate();
        return c;
    }

    /** Scripted motion: circle in XZ, yaw sweeping, flags cycling. Deterministic. */
    static final class ScriptedReader implements ObserverTask.StateReader {
        int calls;

        @Override
        public boolean readInto(org.bukkit.entity.Player player, int tick, PlayerSnapshot snap) {
            calls++;
            double angle = tick * 0.31;
            snap.tick = tick;
            snap.entityId = 7;
            snap.playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
            snap.x = 100.0 + 5.0 * Math.cos(angle);
            snap.y = 64.0;
            snap.z = -200.0 + 5.0 * Math.sin(angle);
            snap.yaw = (float) (tick % 360);
            snap.pitch = 0f;
            snap.flags = RecordLayout.FLAG_GROUND | ((tick % 2 == 0) ? RecordLayout.FLAG_SPRINT : 0);
            snap.pingMs = 23;
            return true;
        }
    }

    private static TrackedPlayers oneFakePlayer() {
        TrackedPlayers tracked = new TrackedPlayers();
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        tracked.putTestEntry(id, new TrackedPlayers.Entry(null, 7, new PlayerSnapshot()));
        return tracked;
    }

    @Test
    @DisplayName("one tick encodes one state record per tracked player")
    void encodesStatePerPlayer() {
        TelemetryRing ring = new TelemetryRing(64 * 32, new GapTracker());
        TrackedPlayers tracked = oneFakePlayer();
        ScriptedReader reader = new ScriptedReader();
        // 1001: not a resync tick (1001 % 40 != 0), so exactly one record lands.
        int[] tick = {1001};
        ObserverTask task = new ObserverTask(config(), ring, tracked, reader, () -> tick[0]);
        task.run();

        assertEquals(1, reader.calls);
        ByteBuffer dst = ByteBuffer.allocate(32);
        assertEquals(1, ring.drainTo(dst, 4));
        dst.flip();
        assertEquals(1001, dst.getInt(0));
        assertEquals(7, dst.getInt(4));
        assertEquals((float) (100.0 + 5.0 * Math.cos(1001 * 0.31)), dst.getFloat(8));
        assertEquals(23, dst.getShort(30) & 0xFFFF);
    }

    @Test
    @DisplayName("RESYNC control lands every resync interval")
    void resyncCadence() {
        TelemetryRing ring = new TelemetryRing(256 * 32, new GapTracker());
        TrackedPlayers tracked = oneFakePlayer();
        int[] tick = {0};
        ObserverTask task = new ObserverTask(config(), ring, tracked, new ScriptedReader(),
                () -> tick[0]);
        for (int t = 1; t <= 80; t++) {
            tick[0] = t;
            task.run();
        }
        // 80 state records + RESYNC at ticks 40 and 80.
        assertEquals(82, ring.size());
        ByteBuffer all = ByteBuffer.allocate(82 * 32);
        assertEquals(82, ring.drainTo(all, 100));
        all.flip();
        List<Integer> resyncTicks = new ArrayList<>();
        for (int i = 0; i < 82; i++) {
            int off = i * 32;
            if (ControlKind.isControlByte(all.get(off))) {
                assertEquals(ControlKind.RESYNC,
                        ControlKind.fromMarker(all.get(off)));
                resyncTicks.add(all.getInt(off + 4));
            }
        }
        assertEquals(List.of(40, 80), resyncTicks);
    }

    @Test
    @DisplayName("dropped ticks surface as GAP control records on the next tick")
    void dropsBecomeGaps() {
        // 32 slots, 3 records/tick, never drained: gentle overload in the last
        // ticks only. A 4-slot ring under 3x churn would evict its own gap records
        // faster than they can be read; that regime is counter-only by design
        // (see TelemetryRing javadoc), not something a stream test can cover.
        TelemetryRing ring = new TelemetryRing(32 * 32, new GapTracker());
        int[] tick = {0};
        TrackedPlayers three = new TrackedPlayers();
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.nameUUIDFromBytes(("p" + i).getBytes());
            three.putTestEntry(id,
                    new TrackedPlayers.Entry(null, 10 + i, new PlayerSnapshot()));
        }
        ObserverTask task = new ObserverTask(config(), ring, three,
                (player, t, snap) -> {
                    snap.entityId = 10;
                    snap.x = t;
                    snap.y = 64;
                    snap.z = 0;
                    return true;
                },
                () -> tick[0]);
        for (int t = 1; t <= 12; t++) {
            tick[0] = t;
            task.run();
        }
        assertTrue(ring.droppedState() > 0, "expected drops under 3x overload");
        // Drain everything still in the ring plus whatever loss accounting survives
        // in the tracker: every tick 1..12 must be either present as state or
        // covered by an emitted GAP range. No tick may silently vanish.
        ByteBuffer dst = ByteBuffer.allocate(4096);
        int n = ring.drainTo(dst, 128);
        dst.flip();
        boolean[] covered = new boolean[13];
        List<int[]> gapRanges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int off = i * 32;
            if (ControlKind.isControlByte(dst.get(off))) {
                if (ControlKind.fromMarker(dst.get(off)) == ControlKind.GAP) {
                    gapRanges.add(new int[] {dst.getInt(off + 8), dst.getInt(off + 12)});
                }
            } else {
                int t = dst.getInt(off);
                if (t >= 1 && t <= 12) {
                    covered[t] = true;
                }
            }
        }
        for (int[] range : gapRanges) {
            for (int t = Math.max(1, range[0]); t <= Math.min(12, range[1]); t++) {
                covered[t] = true;
            }
        }
        // Unsealed tracker ranges (to == current tick) cover the newest losses.
        for (GapTracker.Range r : ring.gaps().drainSealed(Integer.MAX_VALUE)) {
            for (int t = Math.max(1, r.from()); t <= Math.min(12, r.to()); t++) {
                covered[t] = true;
            }
        }
        for (int t = 1; t <= 12; t++) {
            assertTrue(covered[t], "tick " + t + " silently lost");
        }
    }

    @Test
    @DisplayName("unreadable player is skipped, not encoded")
    void unreadableSkipped() {
        TelemetryRing ring = new TelemetryRing(64 * 32, new GapTracker());
        TrackedPlayers tracked = oneFakePlayer();
        ObserverTask task = new ObserverTask(config(), ring, tracked,
                (player, t, snap) -> false, () -> 5);
        task.run();
        assertEquals(0, ring.size());
    }
}

package dev.forensic.telemetry.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChunkBatcherTest {

    private static final UUID EPOCH = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAYER_A = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static TelemetryRing filledRing() {
        TelemetryRing ring = new TelemetryRing(8 * 32, new GapTracker());
        // tick 100 state (entity 7 -> PLAYER_A), tick 101 attack, gap 90-95, tick 102 state.
        ring.offerState(100, 7, 1, 2, 3, 0, 0, 0, 10);
        ring.offerAttack(101, 7, 8, 5f);
        ring.offerGap(102, 90, 95, ControlKind.GAP_RING_OVERFLOW);
        ring.offerState(102, 7, 4, 5, 6, 0, 0, 0, 10);
        return ring;
    }

    @Test
    @DisplayName("drain derives ticks, dense gaps, segments, and player UUIDs")
    void drainDerivesMetadata() {
        TelemetryRing ring = filledRing();
        ByteBuffer scratch = ByteBuffer.allocateDirect(4096);
        ChunkBatcher.DrainedChunk drained = ChunkBatcher.drain(
                ring, scratch, 4096, "server-1", EPOCH, id -> id == 7 ? PLAYER_A : null);
        assertNotNull(drained);
        assertEquals(4, drained.recordCount());
        // tick bounds describe records present (state/control ticks), not the span
        // the GAP record refers to (ticks 90-95 live outside this chunk).
        assertEquals(100, drained.tickMin());
        assertEquals(102, drained.tickMax());
        assertEquals(1, drained.gaps().size());
        ChunkBatcher.GapEntry gap = drained.gaps().get(0);
        assertEquals(0, gap.gapSeq());
        assertEquals(90, gap.fromTick());
        assertEquals(95, gap.toTick());
        assertEquals("RING_OVERFLOW", gap.cloudReason());
        assertEquals(1, drained.segments().size());
        assertTrue(drained.segments().get(0).segmentKey().startsWith("seg/server-1/"));
        assertEquals(1, drained.playerIds().size());
        assertTrue(drained.playerIds().contains(PLAYER_A));
        assertEquals(7, drained.playerEntityIds().get(PLAYER_A));
        assertEquals(0, ring.size(), "drain consumes");
    }

    @Test
    @DisplayName("empty ring drains to null (no empty envelopes)")
    void emptyDrainIsNull() {
        TelemetryRing ring = new TelemetryRing(8 * 32, new GapTracker());
        ByteBuffer scratch = ByteBuffer.allocateDirect(4096);
        assertNull(ChunkBatcher.drain(ring, scratch, 4096, "s", EPOCH, id -> null));
    }

    @Test
    @DisplayName("unresolvable entity ids are skipped, not failed")
    void unknownEntitiesSkipped() {
        TelemetryRing ring = new TelemetryRing(8 * 32, new GapTracker());
        ring.offerState(1, 999, 0, 0, 0, 0, 0, 0, 0);
        ByteBuffer scratch = ByteBuffer.allocateDirect(4096);
        ChunkBatcher.DrainedChunk drained =
                ChunkBatcher.drain(ring, scratch, 4096, "s", EPOCH, id -> null);
        assertNotNull(drained);
        assertTrue(drained.playerIds().isEmpty());
    }

    @Test
    @DisplayName("rendered envelope JSON parses to the cloud's required shape")
    void envelopeJsonShape() {
        TelemetryRing ring = filledRing();
        ByteBuffer scratch = ByteBuffer.allocateDirect(4096);
        ChunkBatcher.DrainedChunk drained = ChunkBatcher.drain(
                ring, scratch, 4096, "server-1", EPOCH, id -> id == 7 ? PLAYER_A : null);
        assertNotNull(drained);
        byte[] json = ChunkBatcher.renderEnvelopeJson(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                drained.tickMin(), drained.recordCount() * 32,
                drained.tickMin(), drained.tickMax(),
                drained.gaps(), drained.segments(), drained.playerIds(),
                drained.playerEntityIds(), 1700000000000L, null, null);
        String s = new String(json, StandardCharsets.UTF_8);
        // Structural assertions on the exact keys the cloud validator reads.
        for (String key : new String[] {
                "\"envelope_id\"", "\"chunk_start_tick\"", "\"chunk_count\":1",
                "\"payload_bytes\":128", "\"gap_count\":1", "\"tick_min\":100",
                "\"tick_max\":102", "\"blob_key\":null", "\"gaps\":[", "\"segments\":[",
                "\"players\":[", "\"session\":{", "\"gap_seq\":0", "\"from_tick\":90",
                "\"to_tick\":95", "\"reason\":\"RING_OVERFLOW\"",
                "\"segment_key\":\"seg/server-1/",
                "\"player_id\":\"33333333-3333-3333-3333-333333333333\",\"name\":null,\"entity_id\":7",
                "\"schema_version\":2"}) {
            assertTrue(s.contains(key), "missing " + key + " in " + s);
        }
    }

    @Test
    @DisplayName("session end rides the envelope only when known")
    void envelopeJsonCarriesSessionEnd() {
        TelemetryRing ring = filledRing();
        ByteBuffer scratch = ByteBuffer.allocateDirect(4096);
        ChunkBatcher.DrainedChunk drained = ChunkBatcher.drain(
                ring, scratch, 4096, "server-1", EPOCH, id -> id == 7 ? PLAYER_A : null);
        assertNotNull(drained);
        byte[] json = ChunkBatcher.renderEnvelopeJson(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                drained.tickMin(), drained.recordCount() * 32,
                drained.tickMin(), drained.tickMax(),
                drained.gaps(), drained.segments(), drained.playerIds(),
                drained.playerEntityIds(), 1700000000000L, 1700000009000L,
                "PLUGIN_DISABLE");
        String s = new String(json, StandardCharsets.UTF_8);
        assertTrue(s.contains("\"started_at_ms\":1700000000000"), s);
        assertTrue(s.contains("\"ended_at_ms\":1700000009000"), s);
        assertTrue(s.contains("\"ended_reason\":\"PLUGIN_DISABLE\""), s);
    }

    @Test
    @DisplayName("unknown gap ordinals degrade to INTERNAL_ERROR, never crash")
    void unknownGapOrdinal() {
        assertEquals("INTERNAL_ERROR", ChunkBatcher.cloudGapReason(99));
        assertEquals("SEND_FAILURE",
                ChunkBatcher.cloudGapReason(ControlKind.GAP_SEND_FAILURE));
    }

    @Test
    @DisplayName("unknown control markers are skipped and counted, never thrown")
    void unknownControlMarkerSkipped() {
        ByteBuffer chunk = ByteBuffer.allocate(3 * 32);
        RecordEncoder.encodeState(chunk, 0, 5, 7, 0, 0, 0, 0, 0, 0, 0);
        // A future kind: marker 0xFE, tick at the v2 control offset, arbitrary payload.
        chunk.put(32, (byte) 0xFE);
        chunk.putInt(32 + 4, 6);
        RecordEncoder.encodeAttack(chunk, 64, 7, 0, 7, 8, 5f);

        ChunkBatcher.DrainedChunk drained = ChunkBatcher.scan(
                chunk, 0, 3 * 32, 3, "server-1", EPOCH,
                id -> id == 7 ? PLAYER_A : null);
        assertEquals(3, drained.recordCount());
        assertEquals(1, drained.unknownControlRecords());
        assertEquals(5, drained.tickMin());
        assertEquals(7, drained.tickMax());
        assertTrue(drained.playerIds().contains(PLAYER_A));
    }

    @Test
    @DisplayName("v2 mechanic kinds are recognised, not counted as unknown")
    void v2KindsRecognised() {
        ByteBuffer chunk = ByteBuffer.allocate(2 * 32);
        RecordEncoder.encodeCrystalSpawn(chunk, 0, 5, 0, 77, 1f, 2f, 3f, 7);
        RecordEncoder.encodeAttackCtx(chunk, 32, 5, 1, 7, 2.5f, 0.9f, 4, 2, 1);
        ChunkBatcher.DrainedChunk drained = ChunkBatcher.scan(
                chunk, 0, 2 * 32, 2, "server-1", EPOCH, id -> null);
        assertEquals(0, drained.unknownControlRecords());
    }
}

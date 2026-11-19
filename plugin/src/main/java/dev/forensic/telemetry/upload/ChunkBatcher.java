package dev.forensic.telemetry.upload;

import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.RecordLayout;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles one upload envelope from drained ring bytes.
 *
 * <p>A drain takes up to {@code flush.max-bytes} of FIFO ring records into a direct chunk
 * buffer. This class then derives the envelope METADATA the cloud requires by scanning those
 * bytes once, linearly, on the uploader thread:
 *
 * <ul>
 *   <li>{@code tick_min}/{@code tick_max} over every record (state and control ticks alike).
 *   <li>{@code gaps[]} from GAP control records in drain order, re-sequenced densely from 0
 *       (the cloud rejects non-dense gap sequences: a missing gap_seq is itself treated as a
 *       gap in the gap record).
 *   <li>{@code segments[]} with one descriptor per drain: the segment key names the future
 *       object-storage object ({@code seg/<server>/<epoch>/<min>-<max>.zst}); the BYTES wait
 *       in the bounded pending queue until object storage exists. Metadata now, bytes later,
 *       never bytes in Postgres.
 *   <li>{@code players[]} as distinct UUIDs resolved from entity ids seen in state records.
 *       Entity ids that no longer resolve (quit between encode and drain) are skipped: presence
 *       is derived cloud-side from the state stream anyway; this array is an upsert
 *       convenience, not the source of truth.
 * </ul>
 *
 * <p>The binary chunk bytes themselves are returned to the caller for the pending-bytes queue;
 * this class never stores them. Allocation here is fine (uploader thread, ~0.5 Hz cadence):
 * the zero-alloc discipline applies to the main thread only.
 */
public final class ChunkBatcher {

    /** Bytes reserved per envelope for JSON framing overhead in field sizing. Not a limit. */
    public static final int SEGMENT_SCHEMA_VERSION = 2;

    private ChunkBatcher() {}

    public record DrainedChunk(
            ByteBuffer bytes,
            int recordCount,
            int tickMin,
            int tickMax,
            List<GapEntry> gaps,
            List<SegmentDescriptor> segments,
            Set<UUID> playerIds,
            /**
             * First-seen entity id per player UUID in drain order. The envelope
             * {@code players[]} array carries this as {@code entity_id}: the only exact
             * entity-to-UUID linkage the cloud ever gets, and encounter participant
             * resolution depends on it. First-seen wins (entity ids are stable per
             * session; a reused id after quit resolves to the earlier holder within
             * one chunk, which the cloud treats as this chunk's observation).
             */
            Map<UUID, Integer> playerEntityIds,
            /** Control records whose marker this build does not know. Skipped, never thrown. */
            int unknownControlRecords) {}

    public record GapEntry(
            int gapSeq, int fromTick, int toTick, int reason, String cloudReason,
            int droppedSnapshots) {}

    public record SegmentDescriptor(String segmentKey, int tickStart, int tickEnd) {}

    /**
     * Drains one chunk and derives its metadata.
     *
     * @param entityResolver maps session entity ids to player UUIDs (null when unknown)
     * @return null when the ring is empty (nothing to send, no envelope)
     */
    public static DrainedChunk drain(
            TelemetryRing ring,
            ByteBuffer chunk,
            int maxBytes,
            String serverId,
            UUID sessionEpoch,
            java.util.function.IntFunction<UUID> entityResolver) {
        chunk.clear();
        int maxRecords = Math.min(maxBytes / RecordLayout.RECORD_BYTES, ring.slotCount());
        int startPos = chunk.position();
        int drained = ring.drainTo(chunk, maxRecords);
        if (drained == 0) {
            return null;
        }
        int endPos = chunk.position();
        return scan(chunk, startPos, endPos, drained, serverId, sessionEpoch, entityResolver);
    }

    /**
     * Derives envelope metadata from already-drained bytes. Package-private so the scanner can
     * be tested with synthetic slots (unknown markers, odd gaps) without a ring.
     */
    static DrainedChunk scan(
            ByteBuffer chunk,
            int startPos,
            int endPos,
            int drained,
            String serverId,
            UUID sessionEpoch,
            java.util.function.IntFunction<UUID> entityResolver) {

        List<GapEntry> gaps = new ArrayList<>();
        Set<UUID> players = new LinkedHashSet<>();
        Map<UUID, Integer> playerEntityIds = new LinkedHashMap<>();
        int tickMin = Integer.MAX_VALUE;
        int tickMax = Integer.MIN_VALUE;
        int gapSeq = 0;
        int unknownControlRecords = 0;

        for (int i = 0; i < drained; i++) {
            int off = startPos + i * RecordLayout.RECORD_BYTES;
            int first = chunk.get(off) & 0xFF;
            if (ControlKind.isControlByte(first)) {
                int tick = chunk.getInt(off + RecordLayout.OFF_CTRL_TICK);
                tickMin = Math.min(tickMin, tick);
                tickMax = Math.max(tickMax, tick);
                ControlKind kind = ControlKind.fromMarkerOrNull(first);
                if (kind == null) {
                    // Fixed-size slots make an unknown kind skippable: the next record
                    // starts at the next 32-byte boundary regardless of payload meaning.
                    unknownControlRecords++;
                    continue;
                }
                if (kind == ControlKind.GAP) {
                    int from = chunk.getInt(off + 8);
                    int to = chunk.getInt(off + 12);
                    int reason = chunk.get(off + 16) & 0xFF;
                    // Snapshot counts for drained ranges are unknown here (the tracker
                    // holds them, the stream carries ticks). Report the tick span width
                    // as a lower bound; analysis gates on ticks, not on the count.
                    int lowerBound = Math.max(0, to - from + 1);
                    gaps.add(new GapEntry(
                            gapSeq++, from, to, reason, cloudGapReason(reason), lowerBound));
                }
            } else {
                int tick = chunk.getInt(off + RecordLayout.OFF_TICK);
                tickMin = Math.min(tickMin, tick);
                tickMax = Math.max(tickMax, tick);
                int entityId = chunk.getInt(off + RecordLayout.OFF_ENTITY_ID);
                UUID uuid = entityResolver.apply(entityId);
                if (uuid != null) {
                    players.add(uuid);
                    playerEntityIds.putIfAbsent(uuid, entityId);
                }
            }
        }

        String segmentKey =
                "seg/" + serverId + "/" + sessionEpoch + "/" + tickMin + "-" + tickMax + ".zst";
        List<SegmentDescriptor> segments =
                List.of(new SegmentDescriptor(segmentKey, tickMin, tickMax));

        chunk.position(startPos);
        chunk.limit(endPos);
        return new DrainedChunk(
                chunk, drained, tickMin, tickMax, gaps, segments, players, playerEntityIds,
                unknownControlRecords);
    }

    static String cloudGapReason(int reason) {
        return switch (reason) {
            case ControlKind.GAP_RING_OVERFLOW -> "RING_OVERFLOW";
            case ControlKind.GAP_SEND_FAILURE -> "SEND_FAILURE";
            case ControlKind.GAP_SESSION_START -> "SESSION_START";
            case ControlKind.GAP_PLUGIN_DISABLE -> "PLUGIN_DISABLE";
            case ControlKind.GAP_QUEUE_DROPPED -> "QUEUE_DROPPED";
            case ControlKind.GAP_INTERNAL_ERROR -> "INTERNAL_ERROR";
            case ControlKind.GAP_EVENT_QUEUE_OVERFLOW -> "EVENT_QUEUE_OVERFLOW";
            default -> "INTERNAL_ERROR";
        };
    }

    /** Minimal JSON string escaping for the one free-form field (session end reason). */
    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Renders the envelope JSON body the cloud validates. No pretty-printing: bytes cost. */
    public static byte[] renderEnvelopeJson(
            UUID envelopeId,
            int chunkStartTick,
            int payloadBytes,
            int tickMin,
            int tickMax,
            List<GapEntry> gaps,
            List<SegmentDescriptor> segments,
            Set<UUID> players,
            Map<UUID, Integer> playerEntityIds,
            Long sessionStartedAtMs,
            Long sessionEndedAtMs,
            String sessionEndedReason) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"envelope_id\":\"").append(envelopeId).append('"');
        sb.append(",\"chunk_start_tick\":").append(chunkStartTick);
        sb.append(",\"chunk_count\":1");
        sb.append(",\"payload_bytes\":").append(payloadBytes);
        sb.append(",\"gap_count\":").append(gaps.size());
        sb.append(",\"tick_min\":").append(tickMin);
        sb.append(",\"tick_max\":").append(tickMax);
        sb.append(",\"blob_key\":null");
        sb.append(",\"gaps\":[");
        for (int i = 0; i < gaps.size(); i++) {
            GapEntry g = gaps.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"gap_seq\":").append(g.gapSeq())
                    .append(",\"from_tick\":").append(g.fromTick())
                    .append(",\"to_tick\":").append(g.toTick())
                    .append(",\"from_nanos\":null,\"to_nanos\":null")
                    .append(",\"reason\":\"").append(g.cloudReason()).append('"')
                    .append(",\"dropped_snapshots\":").append(g.droppedSnapshots())
                    // Event-loss granularity is not tracked in Phase 1 (event drops
                    // surface via ring.droppedControl, not per-range). Zero here means
                    // "untracked", never "none lost".
                    .append(",\"dropped_events\":0}");
        }
        sb.append("],\"segments\":[");
        for (int i = 0; i < segments.size(); i++) {
            SegmentDescriptor s = segments.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"segment_key\":\"").append(s.segmentKey()).append('"')
                    .append(",\"tick_start\":").append(s.tickStart())
                    .append(",\"tick_end\":").append(s.tickEnd())
                    .append(",\"schema_version\":").append(SEGMENT_SCHEMA_VERSION).append('}');
        }
        sb.append("],\"players\":[");
        int pi = 0;
        for (UUID uuid : players) {
            if (pi++ > 0) {
                sb.append(',');
            }
            sb.append("{\"player_id\":\"").append(uuid).append("\",\"name\":null");
            Integer entityId = playerEntityIds.get(uuid);
            if (entityId != null) {
                sb.append(",\"entity_id\":").append(entityId);
            }
            sb.append('}');
        }
        sb.append(']');
        if (sessionStartedAtMs != null) {
            sb.append(",\"session\":{\"started_at_ms\":").append(sessionStartedAtMs)
                    .append(",\"ended_at_ms\":")
                    .append(sessionEndedAtMs == null ? "null" : sessionEndedAtMs.toString())
                    .append(",\"ended_reason\":");
            if (sessionEndedReason == null) {
                sb.append("null");
            } else {
                sb.append('"').append(jsonEscape(sessionEndedReason)).append('"');
            }
            sb.append(",\"peak_players\":null,\"tick_count\":null}");
        } else {
            sb.append(",\"session\":null");
        }
        sb.append('}');
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Gap ranges the GapTracker holds, for direct envelope inclusion (disable path). */
    public static List<GapTracker.Range> pendingRanges(GapTracker gaps) {
        return gaps.drainSealed(Integer.MAX_VALUE);
    }
}

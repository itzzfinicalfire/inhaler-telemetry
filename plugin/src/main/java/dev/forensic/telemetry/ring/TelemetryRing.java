package dev.forensic.telemetry.ring;

import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.RecordEncoder;
import java.nio.ByteBuffer;

/**
 * Bounded off-heap SPSC ring of 32-byte records.
 *
 * <p>Single writer (main server thread), single reader (uploader thread). No locks on the data
 * path: {@code writeSeq}/{@code readSeq} are volatile sequence counters and the buffer stores
 * go before the sequence store. The writer never blocks, never grows, never allocates.
 *
 * <h2>Drop policy (total: every full-ring case is specified)</h2>
 *
 * <p>Drops always happen at the TAIL (oldest unread) so the stream stays fresh and FIFO order
 * is preserved, except one case:
 *
 * <pre>
 * tail \ incoming | NORMAL (state)            | HIGH / CRITICAL (control)
 * ----------------+---------------------------+--------------------------------
 * NORMAL          | evict tail, count gap     | evict tail, count gap
 * HIGH            | drop incoming, count loss | evict tail, count gap/loss
 * CRITICAL        | drop incoming, count loss | evict tail, count loss
 * </pre>
 *
 * <p>Rationale: state is redundant at 20 Hz (the next tick recovers a dropped snapshot), so
 * state never displaces control. Control displaces anything because new evidence outranks old
 * under pressure. Evicting a CRITICAL tail is the nightmare case (it usually describes loss
 * accounting itself); it is counted as control loss, which the uploader turns into an
 * INTERNAL_ERROR gap. Loud, never silent.
 *
 * <p>Saturation backstop: gap records live in this same ring and compete fairly as CRITICAL.
 * Under saturation so extreme that control backlog alone exceeds capacity, gap records can
 * themselves be evicted before the reader drains them. What survives that regime is the
 * counters ({@link #droppedState}, {@link #droppedControl}, tracker's total): in-memory,
 * logged at disable, and visible to uploader stats. Production rings (2M slots, drained at
 * 0.5 Hz) cannot reach that regime short of a dead uploader, in which case every tick is a
 * SEND_FAILURE gap anyway.
 *
 * <p>Evicted or dropped STATE ticks go to {@link GapTracker} as tick ranges. Evicted/dropped
 * CONTROL records increment {@link #droppedControl()}; ticks are meaningless for control loss
 * since control has no tick-series continuity to break.
 */
public final class TelemetryRing {

    /** Bounded scan for tail-kind inspection. The tail slot alone decides; no scan needed. */
    private final ByteBuffer buffer;
    private final int slotMask;
    private final int slotCount;
    private final GapTracker gaps;

    private volatile long writeSeq;
    private volatile long readSeq;

    private volatile long offeredState;
    private volatile long offeredControl;
    private volatile long droppedState;
    private volatile long droppedControl;

    public TelemetryRing(int bufferBytes, GapTracker gaps) {
        if (bufferBytes % 32 != 0) {
            throw new IllegalArgumentException("buffer must be a multiple of 32");
        }
        int slots = bufferBytes / 32;
        if (slots <= 0 || (slots & (slots - 1)) != 0) {
            throw new IllegalArgumentException("slot count must be a positive power of two");
        }
        this.buffer = ByteBuffer.allocateDirect(bufferBytes);
        this.slotMask = slots - 1;
        this.slotCount = slots;
        this.gaps = gaps;
    }

    public int slotCount() {
        return slotCount;
    }

    public long size() {
        return writeSeq - readSeq;
    }

    public long offeredState() {
        return offeredState;
    }

    public long offeredControl() {
        return offeredControl;
    }

    public long droppedState() {
        return droppedState;
    }

    public long droppedControl() {
        return droppedControl;
    }

    public GapTracker gaps() {
        return gaps;
    }

    /** Absolute buffer offset of a sequence number. */
    int offsetOf(long seq) {
        return (int) ((seq & slotMask) * 32L);
    }

    ByteBuffer buffer() {
        return buffer;
    }

    private boolean isControlSlot(long seq) {
        return ControlKind.isControlByte(buffer.get(offsetOf(seq)));
    }

    private int tickOf(long seq) {
        return buffer.getInt(offsetOf(seq));
    }

    /**
     * Offers a state record. All fields travel as parameters (never a capturing lambda) so
     * the hot path allocates nothing: the encoder runs inside, at the slot, exactly once on
     * success and zero times on drop. The tick ALSO feeds gap accounting when dropped: a loss
     * record without a tick is a rumor, not data.
     *
     * @return true if the record landed, false if it was dropped (counted)
     */
    public boolean offerState(
            int tick, int entityId, double x, double y, double z, float yaw, float pitch,
            int flags, int pingMs) {
        offeredState++;
        if (size() >= slotCount) {
            if (isControlSlot(readSeq)) {
                droppedState++;
                gaps.noteDropped(tick);
                return false;
            }
            evictTail();
        }
        long seq = writeSeq;
        RecordEncoder.encodeState(
                buffer, offsetOf(seq), tick, entityId, x, y, z, yaw, pitch, flags, pingMs);
        writeSeq = seq + 1;
        return true;
    }

    public boolean offerAttack(int tick, int attackerId, int victimId, float damage) {
        return offerControl(false, tick, (buf, off) ->
                RecordEncoder.encodeAttack(buf, off, tick, attackerId, victimId, damage));
    }

    public boolean offerDamage(
            int tick, int victimId, float damage, int causeOrdinal, int damagerId) {
        return offerControl(false, tick, (buf, off) ->
                RecordEncoder.encodeDamage(buf, off, tick, victimId, damage, causeOrdinal,
                        damagerId));
    }

    public boolean offerDeath(int tick, int victimId) {
        return offerControl(true, tick, (buf, off) ->
                RecordEncoder.encodeDeath(buf, off, tick, victimId));
    }

    public boolean offerSessionStart(int tick) {
        return offerControl(true, tick, (buf, off) ->
                RecordEncoder.encodeSessionStart(buf, off, tick));
    }

    public boolean offerSessionEnd(int tick) {
        return offerControl(true, tick, (buf, off) ->
                RecordEncoder.encodeSessionEnd(buf, off, tick));
    }

    public boolean offerGap(int sealTick, int fromTick, int toTick, int reason) {
        return offerControl(true, sealTick, (buf, off) ->
                RecordEncoder.encodeGap(buf, off, sealTick, fromTick, toTick, reason));
    }

    public boolean offerInterest(int tick, int entityA, int entityB, int trigger) {
        return offerControl(false, tick, (buf, off) ->
                RecordEncoder.encodeInterest(buf, off, tick, entityA, entityB, trigger));
    }

    public boolean offerResync(int tick, int configHash) {
        return offerControl(false, tick, (buf, off) ->
                RecordEncoder.encodeResync(buf, off, tick, configHash));
    }

    /**
     * Control offer core. Lambdas here are cold-path only (events, resync, gaps: Hz-scale,
     * never the 20 Hz snapshot loop), so capture allocation is irrelevant. The snapshot
     * loop uses {@link #offerState}, which takes plain parameters.
     */
    private boolean offerControl(boolean critical, int tick, SlotWriter writer) {
        offeredControl++;
        if (size() >= slotCount) {
            // CRITICAL tail yields only to CRITICAL incoming.
            if (isControlSlot(readSeq) && !critical) {
                droppedControl++;
                return false;
            }
            evictTail();
        }
        long seq = writeSeq;
        writer.writeAt(buffer, offsetOf(seq));
        writeSeq = seq + 1;
        return true;
    }

    private void evictTail() {
        long tail = readSeq;
        if (isControlSlot(tail)) {
            droppedControl++;
        } else {
            droppedState++;
            gaps.noteDropped(tickOf(tail));
        }
        readSeq = tail + 1;
    }

    /**
     * Drains up to {@code maxRecords} whole records in FIFO order into {@code dst} at its
     * current position. Reader side (uploader thread).
     *
     * @return number of records drained
     */
    public int drainTo(ByteBuffer dst, int maxRecords) {
        int drained = 0;
        while (drained < maxRecords && readSeq < writeSeq) {
            int src = offsetOf(readSeq);
            int dstPos = dst.position();
            for (int i = 0; i < 32; i++) {
                dst.put(dstPos + i, buffer.get(src + i));
            }
            dst.position(dstPos + 32);
            readSeq++;
            drained++;
        }
        return drained;
    }

    /**
     * Functional interface for zero-alloc slot encoding. Runs at most once per offer, on the
     * writer thread. Hot paths must hoist values into locals before the lambda: capturing
     * lambdas allocate per offer, so capture primitives and effectively-final locals only,
     * never objects that outlive the call.
     */
    @FunctionalInterface
    public interface SlotWriter {
        void writeAt(ByteBuffer buffer, int bufferOffset);
    }
}

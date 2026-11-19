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
 *
 * <p>Intra-tick ordering: every control offer stamps a sequence byte from a per-tick counter.
 * The writer is the main thread, so this sequence is event order. Offers that are rejected for
 * pressure do not consume a sequence number; the gap in the sequence is itself honest, but
 * readers must not assume consecutive values.
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

    /** Writer-thread only. No synchronisation: only the main thread offers. */
    private int lastSequenceTick = Integer.MIN_VALUE;
    private int tickSequence;

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
     * Offers a state record. All fields travel as parameters (never a capturing lambda) so the
     * hot path allocates nothing: the encoder runs inside, at the slot, exactly once on success
     * and zero times on drop. The tick ALSO feeds gap accounting when dropped: a loss record
     * without a tick is a rumor, not data.
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
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeAttack(buffer, off, tick, nextSequence(tick),
                attackerId, victimId, damage);
        commitControl();
        return true;
    }

    public boolean offerDamage(
            int tick, int victimId, float damage, int causeOrdinal, int damagerId) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeDamage(buffer, off, tick, nextSequence(tick),
                victimId, damage, causeOrdinal, damagerId);
        commitControl();
        return true;
    }

    public boolean offerDeath(int tick, int victimId) {
        int off = beginControl(true);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeDeath(buffer, off, tick, nextSequence(tick), victimId);
        commitControl();
        return true;
    }

    public boolean offerSessionStart(int tick) {
        int off = beginControl(true);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeSessionStart(buffer, off, tick, nextSequence(tick));
        commitControl();
        return true;
    }

    public boolean offerSessionEnd(int tick) {
        int off = beginControl(true);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeSessionEnd(buffer, off, tick, nextSequence(tick));
        commitControl();
        return true;
    }

    public boolean offerGap(int sealTick, int fromTick, int toTick, int reason) {
        int off = beginControl(true);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeGap(buffer, off, sealTick, nextSequence(sealTick),
                fromTick, toTick, reason);
        commitControl();
        return true;
    }

    public boolean offerInterest(int tick, int entityA, int entityB, int trigger) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeInterest(buffer, off, tick, nextSequence(tick),
                entityA, entityB, trigger);
        commitControl();
        return true;
    }

    public boolean offerResync(int tick, int configHash) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeResync(buffer, off, tick, nextSequence(tick), configHash);
        commitControl();
        return true;
    }

    public boolean offerHeldSlot(int tick, int entityId, int fromSlot, int toSlot,
            int previousCategory, int newCategory) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeHeldSlot(buffer, off, tick, nextSequence(tick),
                entityId, fromSlot, toSlot, previousCategory, newCategory);
        commitControl();
        return true;
    }

    public boolean offerPlace(int tick, int entityId, int blockX, int blockY, int blockZ,
            int blockCategory, int flags) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodePlace(buffer, off, tick, nextSequence(tick),
                entityId, blockX, blockY, blockZ, blockCategory, flags);
        commitControl();
        return true;
    }

    public boolean offerInteract(int tick, int entityId, int blockX, int blockY, int blockZ,
            int interactKind, int itemCategory, int flags) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeInteract(buffer, off, tick, nextSequence(tick),
                entityId, blockX, blockY, blockZ, interactKind, itemCategory, flags);
        commitControl();
        return true;
    }

    public boolean offerSwing(int tick, int entityId, int hand, int flags) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeSwing(buffer, off, tick, nextSequence(tick),
                entityId, hand, flags);
        commitControl();
        return true;
    }

    public boolean offerCrystalSpawn(int tick, int crystalEntityId, float x, float y, float z,
            int ownerEntityId) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeCrystalSpawn(buffer, off, tick, nextSequence(tick),
                crystalEntityId, x, y, z, ownerEntityId);
        commitControl();
        return true;
    }

    public boolean offerCrystalBreak(int tick, int crystalEntityId, int breakerEntityId,
            float x, float y, float z) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeCrystalBreak(buffer, off, tick, nextSequence(tick),
                crystalEntityId, breakerEntityId, x, y, z);
        commitControl();
        return true;
    }

    public boolean offerAnchorExplode(int tick, float x, float y, float z, int triggerEntityId) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeAnchorExplode(buffer, off, tick, nextSequence(tick),
                x, y, z, triggerEntityId);
        commitControl();
        return true;
    }

    public boolean offerTotemPop(int tick, int entityId) {
        int off = beginControl(true);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeTotemPop(buffer, off, tick, nextSequence(tick), entityId);
        commitControl();
        return true;
    }

    public boolean offerWindCharge(int tick, int shooterEntityId, int projectileEntityId) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeWindCharge(buffer, off, tick, nextSequence(tick),
                shooterEntityId, projectileEntityId);
        commitControl();
        return true;
    }

    public boolean offerAttackCtx(int tick, int attackerEntityId, float fallDistance,
            float attackCooldown, int heldCategory, int flags, int victimCategory) {
        int off = beginControl(false);
        if (off < 0) {
            return false;
        }
        RecordEncoder.encodeAttackCtx(buffer, off, tick, nextSequence(tick),
                attackerEntityId, fallDistance, attackCooldown, heldCategory, flags,
                victimCategory);
        commitControl();
        return true;
    }

    /**
     * Reserves the next control slot, applying the documented pressure policy. Returns the
     * buffer offset, or -1 when the incoming record was dropped.
     */
    private int beginControl(boolean critical) {
        offeredControl++;
        if (size() >= slotCount) {
            // CRITICAL tail yields only to CRITICAL incoming.
            if (isControlSlot(readSeq) && !critical) {
                droppedControl++;
                return -1;
            }
            evictTail();
        }
        return offsetOf(writeSeq);
    }

    private void commitControl() {
        writeSeq = writeSeq + 1;
    }

    /** Per-tick writer order. Wraps at 256; readers treat order as monotonic modulo wrap. */
    private int nextSequence(int tick) {
        if (tick != lastSequenceTick) {
            lastSequenceTick = tick;
            tickSequence = 0;
        }
        return tickSequence++ & 0xFF;
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
}

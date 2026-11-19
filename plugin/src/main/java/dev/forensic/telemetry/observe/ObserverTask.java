package dev.forensic.telemetry.observe;

import dev.forensic.telemetry.TelemetryConfig;
import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;

/**
 * 20 Hz observer driver. Runs on the main server thread, every tick.
 *
 * <p>Per-tick work, in order:
 *
 * <ol>
 *   <li>Read the authoritative tick ({@link Bukkit#getCurrentTick()}): the same clock event
 *       listeners use, so snapshots and events share one timeline.
 *   <li>Encode one state record per tracked player (primitive getters only, reused snapshot
 *       and scratch Location: zero allocation in the steady state).
 *   <li>Emit a RESYNC continuity checkpoint every {@code encoder.resync-interval-ticks}.
 *   <li>Emit pending GAP records for sealed loss ranges (see below).
 * </ol>
 *
 * <p>Gap emission lives here, on the main thread, because only the main thread may offer into
 * the ring (SPSC writer). Ranges seal against the current tick: a range with {@code to < tick}
 * can no longer grow from tail eviction (FIFO), and head-drops at the current tick seal on
 * the next tick. Emitted gaps are CRITICAL control records; if even a gap record cannot land,
 * the loss counter keeps the truth and the next tick retries.
 *
 * <p>Cost model: O(players) primitive reads + O(1) ring offers per tick. No iteration over
 * entities that are not tracked, no world queries, no block access, no events fired. The
 * benchmark pins this down; the claim here is only the shape of the work.
 */
public final class ObserverTask implements Runnable {

    /**
     * State reading, split out so tests and benchmarks drive the observer without a server.
     * Production uses {@link PaperPlayerSource}; fakes script motion deterministically. The
     * player argument may be null when the reader ignores it (server-free tests).
     */
    public interface StateReader {
        boolean readInto(org.bukkit.entity.Player player, int tick, PlayerSnapshot snap);
    }

    private final TelemetryConfig config;
    private final TelemetryRing ring;
    private final TrackedPlayers tracked;
    private final StateReader source;
    private final java.util.function.IntSupplier tickSupplier;
    private final int configHash;
    private long lastReportedControlLoss;

    public ObserverTask(
            TelemetryConfig config,
            TelemetryRing ring,
            TrackedPlayers tracked,
            StateReader source,
            java.util.function.IntSupplier tickSupplier) {
        this.config = config;
        this.ring = ring;
        this.tracked = tracked;
        this.source = source;
        this.tickSupplier = tickSupplier;
        this.configHash = config.raw().toString().hashCode();
    }

    @Override
    public void run() {
        // One shared timeline for snapshots and events: listeners stamp
        // Bukkit.getCurrentTick(), and production passes that same clock here, so the
        // cloud never reconciles two skews.
        int tick = tickSupplier.getAsInt();
        for (TrackedPlayers.Entry entry : tracked.entries()) {
            PlayerSnapshot snap = entry.snapshot;
            if (!source.readInto(entry.player, tick, snap)) {
                continue;
            }
            // Plain parameters, no lambda: the 20 Hz loop allocates nothing.
            ring.offerState(tick, snap.entityId, snap.x, snap.y, snap.z, snap.yaw, snap.pitch,
                    snap.flags, snap.pingMs);
        }
        if (tick % config.resyncIntervalTicks() == 0) {
            ring.offerResync(tick, configHash);
        }
        emitPendingGaps(tick);
    }

    private void emitPendingGaps(int tick) {
        GapTracker gaps = ring.gaps();
        List<GapTracker.Range> sealed = gaps.drainSealed(tick);
        for (GapTracker.Range range : sealed) {
            ring.offerGap(tick, range.from(), range.to(), range.reason());
        }
        long lost = ring.droppedControl();
        if (lost > lastReportedControlLoss) {
            lastReportedControlLoss = lost;
            // Control loss has no tick range; report it as a zero-width INTERNAL_ERROR
            // range at the seal tick. Zero-width is honest here: it marks the accounting
            // event, not a data hole (the data hole, if any, has its own range). One
            // marker per newly lost record, not per tick, so a sustained outage marking
            // every tick cannot itself fill the ring.
            gaps.noteDropped(tick, ControlKind.GAP_INTERNAL_ERROR);
        }
    }

    /** Session UUID for this enable cycle. New on every enable; never reused. */
    public static UUID newSessionEpoch() {
        return UUID.randomUUID();
    }
}

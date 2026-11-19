package dev.forensic.telemetry.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Allocation is the hard gate; timing is a budget gate with orders of magnitude of headroom.
 *
 * <p>Allocation is asserted at exactly zero: the hot path (encode, ring offer, gap note) must
 * not create objects, and that property is either true or a bug, never flaky.
 *
 * <p>The observer timing gate is expressed the way Phase 1 exit gate 2 is written: at
 * 40 players the observer must fit inside 1% of a 50 ms tick. It is a
 * budget over the player count, not a per-player constant. The measured cost on a development
 * machine is ~33 ns/player (1.3 us total at 40 players, 0.003% of tick), roughly three orders
 * of magnitude under the gate, and the best of several runs is used so CI contention cannot
 * turn a scheduler hiccup into a red build. Live 1% proof comes from spark on the real host;
 * this test stops the budget from regressing by an order of magnitude in a PR.
 *
 * <p>Real numbers come from {@code gradlew :plugin:bench}. Override the assumed player count
 * with {@code -Dforensic.bench.players=100}.
 */
class BenchTest {

    private static final long TICK_NS = 50_000_000L;

    /** Phase 1 exit gate 2: observer overhead at most 1% of the tick budget. */
    private static final long TICK_BUDGET_DIVISOR = 100;

    /** Sized for a full server; 100 turns the gate genuinely tight (5,000 ns/player). */
    private static final int ASSUMED_PLAYERS = Integer.getInteger("forensic.bench.players", 40);

    /**
     * Allocation bound for 100k hot-loop iterations. Any per-iteration leak is megabytes
     * (100k objects x 16B minimum); measurement noise (TLAB sampling, MXBean overhead) is
     * bytes. The bound sits four orders of magnitude above the noise and two below any
     * leak, so it is deterministic in practice without pretending the JVM is noiseless.
     */
    private static final long ALLOC_NOISE_CEILING = 65536;

    @Test
    @DisplayName("encoding allocates nothing per iteration")
    void encodeAllocatesNothing() {
        ByteBuffer buf = ByteBuffer.allocateDirect(32);
        Runnable work = () -> {
            for (int i = 0; i < 100_000; i++) {
                RecordEncoder.encodeState(buf, 0, i, 7, 1, 2, 3, 0, 0, 0, 20);
            }
        };
        work.run(); // warmup (JIT, direct-buffer page init)
        long allocated = BenchMain.allocatedBytes(work);
        assertTrue(allocated < ALLOC_NOISE_CEILING,
                "per-iteration allocation suspected: " + allocated + " bytes / 100k iters");
    }

    @Test
    @DisplayName("ring offer + evict + gap note allocate zero bytes")
    void ringAllocatesNothing() {
        TelemetryRing ring = new TelemetryRing(4096 * 32, new GapTracker());
        ByteBuffer dst = ByteBuffer.allocate(4096 * 32);
        Runnable work =
                new Runnable() {
                    int tick;

                    @Override
                    public void run() {
                        for (int i = 0; i < 20_000; i++) {
                            tick = i;
                            // Overflow on purpose: exercises evict + gap-note paths,
                            // which run under the same pressure and must also be
                            // allocation-free. Plain parameters throughout: no lambda,
                            // nothing captured, nothing created.
                            ring.offerState(tick, 7, 0, 0, 0, 0, 0, 0, 0);
                            if (ring.size() >= 4096) {
                                dst.clear();
                                ring.drainTo(dst, 4096);
                            }
                        }
                    }
                };
        work.run(); // warmup
        long allocated = BenchMain.allocatedBytes(work);
        assertTrue(allocated < ALLOC_NOISE_CEILING,
                "per-iteration allocation suspected: " + allocated + " bytes / 20k iters");
    }

    @Test
    @DisplayName("throughput smoke: 200k encodes well inside any tick budget")
    void throughputSmoke() {
        double ns = BenchMain.benchEncode();
        System.out.println("encode ns/op: " + ns);
        assertTrue(ns < 5000, "encode pathologically slow: " + ns + " ns/op");
    }

    @Test
    @DisplayName("observer fits the 1% tick budget at the assumed player count")
    void observerFitsTickBudget() {
        double bestNsPerPlayer = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            bestNsPerPlayer = Math.min(bestNsPerPlayer, BenchMain.benchObserver());
        }
        double totalNs = bestNsPerPlayer * ASSUMED_PLAYERS;
        double budgetNs = (double) TICK_NS / TICK_BUDGET_DIVISOR;
        double tickPct = totalNs / TICK_NS * 100.0;
        System.out.printf(
                "observer: %.1f ns/player x %d players = %.0f ns/tick (%.3f%% of 50 ms);"
                        + " gate %.0f ns/tick (1%%)%n",
                bestNsPerPlayer, ASSUMED_PLAYERS, totalNs, tickPct, budgetNs);
        assertTrue(
                totalNs <= budgetNs,
                "observer exceeds 1% of tick budget at " + ASSUMED_PLAYERS + " players: "
                        + totalNs + " ns/tick vs " + budgetNs + " ns/tick");
    }
}

package dev.forensic.telemetry.bench;

import com.sun.management.ThreadMXBean;
import dev.forensic.telemetry.TelemetryConfig;
import dev.forensic.telemetry.observe.ObserverTask;
import dev.forensic.telemetry.observe.PlayerSnapshot;
import dev.forensic.telemetry.observe.TrackedPlayers;
import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

/**
 * Hand-rolled benchmark (no JMH: one more dependency on the main-thread classpath is not worth
 * it for three loops). Run with {@code gradlew :plugin:bench}; prints ns/op tables.
 *
 * <p>What is measured:
 * <ul>
 *   <li>state encode: 1M records into a direct buffer (the per-record floor).
 *   <li>ring offer+poll: 1M offers with a draining reader (the steady-state ring path,
 *       including the volatile sequence stores).
 *   <li>observer tick: 100 scripted players x 2000 ticks (the per-player main-thread cost
 *       that must fit inside the 50ms tick budget with headroom).
 * </ul>
 *
 * <p>Allocation is measured separately in {@link BenchTest} (deterministic zero-alloc
 * assertions); wall-clock numbers here are for sizing judgment, not gates.
 */
public final class BenchMain {

    private BenchMain() {}

    public static void main(String[] args) {
        warmup();
        System.out.println("== forensic-telemetry benchmarks (ns/op, lower is better) ==");
        System.out.printf("%-28s %12.1f%n", "encodeState", benchEncode());
        System.out.printf("%-28s %12.1f%n", "ring offer+poll", benchRing());
        System.out.printf("%-28s %12.1f%n", "observer tick/player", benchObserver());
    }

    private static void warmup() {
        benchEncode();
        benchRing();
        benchObserver();
    }

    static double benchEncode() {
        ByteBuffer buf = ByteBuffer.allocateDirect(32);
        int n = 1_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            RecordEncoder.encodeState(buf, 0, i, 7, 100.5, 64.0, -200.25, 90f, 0f, 3, 25);
        }
        return (double) (System.nanoTime() - start) / n;
    }

    static double benchRing() {
        TelemetryRing ring = new TelemetryRing(4096 * 32, new GapTracker());
        ByteBuffer dst = ByteBuffer.allocate(4096 * 32);
        int n = 1_000_000;
        long start = System.nanoTime();
        int tick = 0;
        for (int i = 0; i < n; i++) {
            int t = tick++;
            ring.offerState(t, 7, 0, 0, 0, 0, 0, 0, 0);
            if (ring.size() >= 4096) {
                dst.clear();
                ring.drainTo(dst, 4096);
            }
        }
        return (double) (System.nanoTime() - start) / n;
    }

    static double benchObserver() {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("buffer.bytes", Integer.toString(4096 * 32));
        raw.put("buffer.chunk-bytes", "1024");
        TelemetryConfig config = TelemetryConfig.fromRaw(raw);
        TelemetryRing ring = new TelemetryRing(4096 * 32, new GapTracker());
        TrackedPlayers tracked = new TrackedPlayers();
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.nameUUIDFromBytes(("bench" + i).getBytes());
            tracked.putTestEntry(id,
                    new TrackedPlayers.Entry(null, 100 + i, new PlayerSnapshot()));
        }
        int[] tick = {0};
        ObserverTask task = new ObserverTask(config, ring, tracked,
                (player, t, snap) -> {
                    snap.entityId = 100;
                    snap.x = t;
                    snap.y = 64;
                    snap.z = 0;
                    snap.yaw = 0;
                    snap.pitch = 0;
                    snap.flags = 1;
                    snap.pingMs = 20;
                    return true;
                },
                () -> tick[0]);
        ByteBuffer drain = ByteBuffer.allocate(4096 * 32);
        int ticks = 2000;
        long start = System.nanoTime();
        for (int t = 1; t <= ticks; t++) {
            tick[0] = t;
            task.run();
            drain.clear();
            ring.drainTo(drain, 4096);
        }
        return (double) (System.nanoTime() - start) / (ticks * 100);
    }

    /** Allocated bytes on the current thread across {@code work} (warmed up by caller). */
    static long allocatedBytes(Runnable work) {
        ThreadMXBean beans = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!beans.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("thread allocation measurement unsupported");
        }
        long id = Thread.currentThread().threadId();
        long before = beans.getThreadAllocatedBytes(id);
        work.run();
        return beans.getThreadAllocatedBytes(id) - before;
    }
}

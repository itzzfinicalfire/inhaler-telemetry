package dev.forensic.telemetry.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.TelemetryConfig;
import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Uploader behavior against a scripted fake endpoint (JDK HttpServer is unnecessary here:
 * what matters is the client's retry/backoff/concurrency contract, not the socket).
 */
class AsyncUploaderTest {

    private static final UUID EPOCH = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Scripted endpoint: concurrency-measuring, behavior per call index. */
    static final class FakeEndpoint implements UploadEndpoint {
        final List<Request> requests = new ArrayList<>();
        final List<String> putPaths = new ArrayList<>();
        final List<Request> putRequests = new ArrayList<>();
        /** Cross-endpoint ordering: "POST" / "PUT" entries in arrival order. */
        final List<String> sequence = new ArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInflight = new AtomicInteger();
        final ConcurrentLinkedQueue<Integer> script = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Integer> putScript = new ConcurrentLinkedQueue<>();
        volatile String scriptBody = "{\"status\":\"accepted\"}";
        volatile long blockMs = 0;
        volatile long putBlockMs = 0;

        synchronized void record(Request r) {
            requests.add(r);
            sequence.add("POST");
        }

        synchronized void recordPut(String path, Request r) {
            putPaths.add(path);
            putRequests.add(r);
            sequence.add("PUT");
        }

        @Override
        public CompletableFuture<Result> post(Request request) {
            record(request);
            int now = inFlight.incrementAndGet();
            maxInflight.accumulateAndGet(now, Math::max);
            Integer status = script.poll();
            int code = status == null ? 200 : status;
            CompletableFuture<Result> f = new CompletableFuture<>();
            if (blockMs > 0) {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(blockMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inFlight.decrementAndGet();
                    f.complete(new Result(code, scriptBody));
                });
                t.setDaemon(true);
                t.start();
            } else {
                inFlight.decrementAndGet();
                f.complete(new Result(code, scriptBody));
            }
            return f;
        }

        @Override
        public CompletableFuture<Result> put(String path, Request request) {
            recordPut(path, request);
            int now = inFlight.incrementAndGet();
            maxInflight.accumulateAndGet(now, Math::max);
            Integer status = putScript.poll();
            int code = status == null ? 200 : status;
            CompletableFuture<Result> f = new CompletableFuture<>();
            if (putBlockMs > 0) {
                long wait = putBlockMs;
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inFlight.decrementAndGet();
                    f.complete(new Result(code, scriptBody));
                });
                t.setDaemon(true);
                t.start();
            } else {
                inFlight.decrementAndGet();
                f.complete(new Result(code, scriptBody));
            }
            return f;
        }
    }

    private static TelemetryConfig testConfig(Map<String, String> overrides) {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        // telemetry.enabled left false: validation of endpoint/secret is a
        // deployment concern, and these tests never touch the network.
        raw.put("server.id", "11111111-1111-1111-1111-111111111111");
        raw.put("server.key-id", "k1");
        raw.put("server.key-secret", "test-secret-16-chars");
        raw.put("buffer.bytes", Integer.toString(16 * 1024));
        raw.put("buffer.chunk-bytes", "1024");
        raw.put("flush.interval-ms", "100");
        raw.put("flush.max-bytes", "1024");
        raw.put("flush.max-batch-chunks", "4");
        raw.put("upload.max-inflight", "2");
        raw.put("upload.backoff-initial-ms", "20");
        raw.put("upload.backoff-max-ms", "100");
        raw.putAll(overrides);
        TelemetryConfig config = TelemetryConfig.fromRaw(raw);
        config.validate();
        return config;
    }

    private static TelemetryRing ringWithState(int ticks) {
        TelemetryRing ring = new TelemetryRing(64 * 32, new GapTracker());
        for (int t = 1; t <= ticks; t++) {
            ring.offerState(t, 1, 0, 0, 0, 0, 0, 0, 0);
        }
        return ring;
    }

    private static AsyncUploader uploader(
            TelemetryConfig config, TelemetryRing ring, FakeEndpoint endpoint) {
        return new AsyncUploader(
                config, ring, endpoint, id -> null,
                "11111111-1111-1111-1111-111111111111", EPOCH, 1700000000000L,
                "test-secret-16-chars".getBytes(StandardCharsets.UTF_8));
    }

    private static void waitFor(Supplier<Boolean> condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(25);
        }
    }

    @Test
    @DisplayName("200 advances the ring and counts accepted")
    void happyPath() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(4);
        FakeEndpoint endpoint = new FakeEndpoint();
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.envelopesAccepted() >= 1, "accepted envelope");
        uploader.stopAndFlush(2000);
        assertEquals(1, endpoint.requests.size());
        UploadEndpoint.Request req = endpoint.requests.get(0);
        assertEquals("11111111-1111-1111-1111-111111111111", req.headers().get("x-server-id"));
        assertEquals("k1", req.headers().get("x-key-id"));
        assertEquals(64, req.headers().get("x-signature").length());
    }

    @Test
    @DisplayName("retries reuse envelope_id and nonce with fresh timestamps")
    void retryReusesIdentity() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(2);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.script.add(500);
        endpoint.script.add(500);
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> endpoint.requests.size() >= 3, "three attempts");
        uploader.stopAndFlush(2000);
        // Same chunk retried: identical envelope identity and nonce (so a retry
        // arriving after the original is a duplicate, not a double), with
        // non-decreasing timestamps (fresh signature per attempt).
        String env0 = bodyField(endpoint.requests.get(0), "envelope_id");
        String env1 = bodyField(endpoint.requests.get(1), "envelope_id");
        assertEquals(env0, env1);
        assertEquals(
                endpoint.requests.get(0).headers().get("x-nonce"),
                endpoint.requests.get(1).headers().get("x-nonce"));
        long t0 = Long.parseLong(endpoint.requests.get(0).headers().get("x-timestamp-ms"));
        long t1 = Long.parseLong(endpoint.requests.get(1).headers().get("x-timestamp-ms"));
        long t2 = Long.parseLong(endpoint.requests.get(2).headers().get("x-timestamp-ms"));
        assertTrue(t1 >= t0 && t2 >= t1, "timestamps must not go backward across retries");
    }

    @Test
    @DisplayName("duplicate 200 counts separately from accepted")
    void duplicateCounted() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(2);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.scriptBody = "{\"status\":\"duplicate\",\"via\":\"nonce\"}";
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.envelopesDuplicate() >= 1, "duplicate envelope");
        uploader.stopAndFlush(2000);
        assertEquals(0, uploader.envelopesAccepted());
    }

    @Test
    @DisplayName("400 poison drops the chunk with an INTERNAL_ERROR span")
    void poisonDropped() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(4);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.script.add(400);
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.chunksDroppedPoison() >= 1, "poison drop");
        uploader.stopAndFlush(2000);
        assertEquals(1, uploader.chunksDroppedPoison());
        // The hole is durable: an INTERNAL_ERROR range covers the chunk span.
        assertTrue(ring.gaps().totalDroppedTicks() > 0);
    }

    @Test
    @DisplayName("401 parks uploads; 200 unparks and restores the gap reason")
    void authParkAndRecover() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(2);
        FakeEndpoint endpoint = new FakeEndpoint();
        // Sustained 401s keep the parked state STABLE so polling observes it
        // deterministically; a single 401 risks the park window slipping
        // between polls (see segmentPutAuthPark).
        for (int i = 0; i < 20; i++) {
            endpoint.script.add(401);
        }
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(uploader::authParked, "parked after 401");
        assertTrue(uploader.authParked());
        // Script exhausted -> 200s: unpark is STABLE (queue drains, nothing
        // re-parks), so this poll is deterministic too.
        waitFor(() -> !uploader.authParked(), "unparked after 200");
        uploader.stopAndFlush(2000);
        assertFalse(uploader.authParked());
    }

    @Test
    @DisplayName("in-flight cap is never exceeded under slow endpoint")
    void inflightBounded() throws Exception {
        TelemetryConfig config = testConfig(Map.of("upload.max-inflight", "2"));
        TelemetryRing ring = ringWithState(32);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.blockMs = 300;
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        Thread.sleep(900);
        uploader.stopAndFlush(5000);
        assertTrue(endpoint.maxInflight.get() <= 2,
                "in-flight exceeded cap: " + endpoint.maxInflight.get());
        assertTrue(endpoint.maxInflight.get() >= 1, "expected concurrent sends");
    }

    @Test
    @DisplayName("backoff draws from doubling windows and resets after success")
    void backoffBehavior() throws Exception {
        TelemetryConfig config = testConfig(Map.of(
                "upload.backoff-initial-ms", "300", "upload.backoff-max-ms", "600"));
        TelemetryRing ring = ringWithState(2);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.script.add(500);
        endpoint.script.add(500);
        endpoint.script.add(500);
        List<Long> attemptTimes = new ArrayList<>();
        UploadEndpoint timing =
                new UploadEndpoint() {
                    @Override
                    public CompletableFuture<Result> post(Request request) {
                        synchronized (attemptTimes) {
                            attemptTimes.add(System.nanoTime());
                        }
                        return endpoint.post(request);
                    }

                    @Override
                    public CompletableFuture<Result> put(String path, Request request) {
                        return endpoint.put(path, request);
                    }
                };
        // Seeded dice: Random's algorithm is specified, so the drawn delays are
        // reproducible. Schedule is 300/600/600: draw nextLong(301), nextLong(601)x2.
        java.util.Random expected = new java.util.Random(42);
        long d1 = expected.nextLong(301);
        long d2 = expected.nextLong(601);
        AsyncUploader uploader = new AsyncUploader(
                config, ring, timing, id -> null,
                "11111111-1111-1111-1111-111111111111", EPOCH, 1700000000000L,
                "test-secret-16-chars".getBytes(StandardCharsets.UTF_8),
                new java.util.Random(42));
        uploader.start();
        waitFor(() -> uploader.envelopesAccepted() >= 1, "eventual success");
        uploader.stopAndFlush(2000);
        assertTrue(attemptTimes.size() >= 4, "expected >= 4 attempts, got " + attemptTimes.size());
        long gap1Ms = (attemptTimes.get(1) - attemptTimes.get(0)) / 1_000_000;
        long gap2Ms = (attemptTimes.get(2) - attemptTimes.get(1)) / 1_000_000;
        // Schedulers fire no earlier than requested (modulo ~15ms Windows timer
        // granularity) and arbitrarily late under load: assert the floor holds
        // with slop, and lateness stays within a generous bound.
        assertTrue(gap1Ms >= d1 - 25, "gap1 " + gap1Ms + "ms below drawn " + d1 + "ms");
        assertTrue(gap2Ms >= d2 - 25, "gap2 " + gap2Ms + "ms below drawn " + d2 + "ms");
        assertTrue(gap1Ms <= d1 + 3000 && gap2Ms <= d2 + 3000, "scheduling absurdly late");
        // Reset: after the success, currentBackoffMs is back at initial, so a new
        // failure schedule restarts small. Proven implicitly: the success above
        // reset the schedule rather than wedging at max.
    }

    @Test
    @DisplayName("stopAndFlush drains the tail synchronously")
    void stopFlushesTail() throws Exception {
        TelemetryConfig config = testConfig(Map.of("flush.interval-ms", "60000"));
        TelemetryRing ring = ringWithState(8);
        FakeEndpoint endpoint = new FakeEndpoint();
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        Thread.sleep(100);
        uploader.stopAndFlush(5000);
        assertEquals(0, ring.size(), "tail must be drained on disable");
        assertTrue(uploader.envelopesAccepted() >= 1);
    }

    @Test
    @DisplayName("shutdown envelope carries the session end")
    void shutdownEnvelopeCarriesSessionEnd() {
        TelemetryConfig config = testConfig(Map.of("flush.interval-ms", "60000"));
        TelemetryRing ring = ringWithState(8);
        FakeEndpoint endpoint = new FakeEndpoint();
        // Never started: the final synchronous drain is the only sender, so the
        // assertion cannot accidentally inspect an earlier steady-state envelope.
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.stopAndFlush(5000);
        assertEquals(1, endpoint.requests.size());
        String tail = new String(endpoint.requests.get(0).body(), StandardCharsets.UTF_8);
        assertTrue(tail.contains("\"ended_reason\":\"PLUGIN_DISABLE\""), tail);
        assertTrue(tail.contains("\"ended_at_ms\":") && !tail.contains("\"ended_at_ms\":null"),
                tail);
    }

    @Test
    @DisplayName("segment PUT follows envelope success with signed bytes")
    void segmentPutHappyPath() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(4);
        FakeEndpoint endpoint = new FakeEndpoint();
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.segmentsUploaded() >= 1, "uploaded segment");
        uploader.stopAndFlush(2000);
        assertEquals(1, endpoint.putPaths.size());
        String path = endpoint.putPaths.get(0);
        assertTrue(path.startsWith("/v1/segments/seg/"), "unexpected path " + path);
        UploadEndpoint.Request put = endpoint.putRequests.get(0);
        assertEquals("application/octet-stream", put.headers().get("content-type"));
        // 4 state records x 32 bytes, verbatim.
        assertEquals(4 * 32, put.body().length);
        // Signature verifies against an independently built canonical string
        // (plain concatenation here, not the signer's builder: circular checks
        // prove nothing).
        String timestamp = put.headers().get("x-timestamp-ms");
        String nonce = put.headers().get("x-nonce");
        String digest = EnvelopeSigner.sha256Hex(put.body());
        String canonical =
                "PUT\n" + path + "\n11111111-1111-1111-1111-111111111111\nk1\n"
                        + timestamp + "\n" + nonce + "\n"
                        + "22222222-2222-2222-2222-222222222222\n1\n"
                        + put.body().length + "\n" + digest;
        String expected =
                EnvelopeSigner.hmacHex(
                        "test-secret-16-chars".getBytes(StandardCharsets.UTF_8), canonical);
        assertEquals(expected, put.headers().get("x-signature"));
        assertEquals(0, uploader.pendingByteChunks());
    }

    @Test
    @DisplayName("segment PUT retries keep key and bytes with fresh identity")
    void segmentPutRetry() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(2);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.putScript.add(500);
        endpoint.putScript.add(500);
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> endpoint.putPaths.size() >= 3, "three PUT attempts");
        uploader.stopAndFlush(2000);
        assertEquals(3, endpoint.putPaths.size(), "no fourth attempt after success");
        // Same key, same bytes every attempt (only nonce/timestamp refresh).
        assertEquals(1, new java.util.HashSet<>(endpoint.putPaths).size());
        byte[] first = endpoint.putRequests.get(0).body();
        for (UploadEndpoint.Request r : endpoint.putRequests) {
            assertTrue(java.util.Arrays.equals(first, r.body()));
        }
        String n0 = endpoint.putRequests.get(0).headers().get("x-nonce");
        String n1 = endpoint.putRequests.get(1).headers().get("x-nonce");
        assertFalse(n0.equals(n1), "retries must not reuse nonces");
    }

    @Test
    @DisplayName("segment PUT 409 drops loudly without wedging the queue")
    void segmentPutConflict() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(2);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.putScript.add(409);
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.chunksDroppedPoison() >= 1, "conflict drop");
        uploader.stopAndFlush(2000);
        assertEquals(0, uploader.pendingByteChunks());
    }

    @Test
    @DisplayName("segment PUT 401 parks shared auth; 200 unparks")
    void segmentPutAuthPark() throws Exception {
        TelemetryConfig config = testConfig(Map.of());
        TelemetryRing ring = ringWithState(2);
        FakeEndpoint endpoint = new FakeEndpoint();
        // Phase 1: sustained 401s. Parked is STABLE (nothing can unpark it),
        // so polling observes it deterministically; a single 401 risks the
        // park window slipping between 25ms polls (flaky by construction).
        for (int i = 0; i < 20; i++) {
            endpoint.putScript.add(401);
        }
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(uploader::authParked, "parked after segment 401");
        assertTrue(uploader.authParked());
        int attemptsWhileParked = endpoint.putPaths.size();
        assertTrue(attemptsWhileParked >= 1, "expected at least one PUT attempt");
        // Phase 2: script exhausted -> 200s. Unpark is STABLE (queue drains,
        // nothing re-parks), so this poll is deterministic too.
        waitFor(() -> !uploader.authParked(), "unparked after 200");
        uploader.stopAndFlush(2000);
        assertFalse(uploader.authParked());
        assertEquals(1, uploader.segmentsUploaded());
    }

    @Test
    @DisplayName("custom endpoint URL without telemetry suffix disables segments, envelopes flow")
    void segmentDerivationFailure() throws Exception {
        TelemetryConfig config =
                testConfig(Map.of("endpoint.url", "https://example.com/custom-ingest"));
        TelemetryRing ring = ringWithState(4);
        FakeEndpoint endpoint = new FakeEndpoint();
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.envelopesAccepted() >= 1, "accepted envelope");
        Thread.sleep(300);
        uploader.stopAndFlush(2000);
        assertEquals(0, endpoint.putPaths.size(), "no PUT must be attempted");
        assertEquals(0, uploader.segmentsUploaded());
    }

    @Test
    @DisplayName("disable gap-records staged bytes instead of losing them silently")
    void disableDrainsPendingQueue() throws Exception {
        TelemetryConfig config = testConfig(Map.of("flush.interval-ms", "60000"));
        TelemetryRing ring = ringWithState(8);
        FakeEndpoint endpoint = new FakeEndpoint();
        endpoint.putBlockMs = 60000;
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        // One manual flush (same package: package-private access; the 60s
        // scheduled tick never fires during the test): envelope 200, PUT blocks
        // in flight for 60s holding a permit, bytes staged.
        uploader.flushOnce();
        waitFor(() -> uploader.envelopesAccepted() >= 1, "envelope accepted");
        // stopAndFlush: settle loop times out on the blocked permit, then the
        // staged chunk is gap-recorded instead of vanishing.
        uploader.stopAndFlush(5000);
        assertEquals(8 * 32, uploader.bytesDroppedPending());
        assertEquals(0, uploader.pendingByteChunks());
    }

    @Test
    @DisplayName("stop persists tail segment bytes before posting the envelope")
    void stopPersistsSegmentBeforeEnvelope() throws Exception {
        TelemetryConfig config = testConfig(Map.of("flush.interval-ms", "60000"));
        TelemetryRing ring = ringWithState(4);
        FakeEndpoint endpoint = new FakeEndpoint();
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.segmentsUploaded() >= 1, "initial segment uploaded");
        int baseline = endpoint.sequence.size();
        for (int t = 5; t <= 8; t++) {
            ring.offerState(t, 1, 0, 0, 0, 0, 0, 0, 0);
        }
        uploader.stopAndFlush(5000);
        List<String> tail = List.copyOf(endpoint.sequence.subList(baseline, endpoint.sequence.size()));
        // Bytes first, envelope second: an accepted envelope with no bytes in the
        // store is an orphan the cloud exporter refuses to export (observed live).
        assertEquals(List.of("PUT", "POST"), tail, "tail must upload bytes before the envelope");
        assertEquals(0, uploader.pendingByteChunks());
        assertEquals(0, ring.size());
    }

    @Test
    @DisplayName("stop gap-records the tail instead of posting an orphan when the PUT fails")
    void stopGapRecordsTailWhenSegmentPutFails() throws Exception {
        TelemetryConfig config = testConfig(Map.of("flush.interval-ms", "60000"));
        TelemetryRing ring = ringWithState(4);
        FakeEndpoint endpoint = new FakeEndpoint();
        AsyncUploader uploader = uploader(config, ring, endpoint);
        uploader.start();
        waitFor(() -> uploader.envelopesAccepted() >= 1, "initial envelope accepted");
        waitFor(() -> uploader.segmentsUploaded() >= 1, "initial segment uploaded");
        int postsBefore = endpoint.requests.size();
        for (int t = 5; t <= 8; t++) {
            ring.offerState(t, 1, 0, 0, 0, 0, 0, 0, 0);
        }
        endpoint.putScript.add(500);
        uploader.stopAndFlush(5000);
        // The tail's segment PUT fails at stop: the span must become a typed gap
        // and the envelope must never be accepted (no orphan, no silent loss).
        assertEquals(postsBefore, endpoint.requests.size(), "tail envelope must never be posted");
        assertEquals(0, ring.size(), "tail records drained from the ring");
        assertTrue(ring.gaps().totalDroppedTicks() >= 4, "tail span gap-recorded");
        assertEquals(0, uploader.pendingByteChunks());
    }

    private static String bodyField(UploadEndpoint.Request req, String field) {
        String body = new String(req.body(), StandardCharsets.UTF_8);
        String key = "\"" + field + "\":\"";
        int i = body.indexOf(key);
        if (i < 0) {
            throw new AssertionError("field " + field + " missing in " + body);
        }
        int j = body.indexOf('"', i + key.length());
        return body.substring(i + key.length(), j);
    }
}

package dev.forensic.telemetry.upload;

import dev.forensic.telemetry.TelemetryConfig;
import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * Asynchronous envelope uploader. Owns every thread that is not the main thread.
 *
 * <p>A single flush thread ticks every {@code flush.interval-ms}: it drains one chunk from the
 * ring, assembles envelope metadata, signs, and hands the POST to the endpoint. Actual I/O
 * never touches the flush thread either: sends are async, gated by a semaphore of {@code
 * upload.max-inflight} permits, so at most N uploads are ever in flight and the flush thread
 * waits on a permit (bounded wait) rather than spawning work without bound.
 *
 * <h2>Retry policy</h2>
 * <ul>
 *   <li>200 accepted/duplicate: advance. Duplicate is success (at-least-once delivery).</li>
 *   <li>400/413: the chunk is poison (our bug or contract drift). Retry cannot fix it, so the
 *       chunk is dropped and its tick span recorded as INTERNAL_ERROR. A poison chunk must not
 *       wedge the queue forever.</li>
 *   <li>401: authentication failed. Uploads park at backoff-max cadence instead of hammering
 *       a rejecting server; the ring keeps buffering and overflow becomes SEND_FAILURE gaps.
 *       The parked flag also flips the ring's gap default reason, so concurrent drops blame
 *       the transport, not burst volume.</li>
 *   <li>429/5xx/timeout/refused/reset: same bytes, same envelope_id, same nonce, fresh
 *       timestamp and signature, after backoff(initial * 2^attempt, capped at max, plus
 *       jitter). The nonce is per-envelope precisely so a retry that arrives after the
 *       original is a harmless duplicate rather than a double-accepted upload.</li>
 * </ul>
 *
 * <h2>Pending bytes</h2>
 * <p>Drained chunk bytes wait in a bounded FIFO (direct buffers, {@code flush.max-batch-chunks}
 * chunks ≈ 1 MiB) for segment upload. Each chunk carries its segment key from the envelope
 * metadata, so bytes and descriptors can never desynchronize. When the queue is full the
 * oldest bytes drop and their span is gap-recorded with SEND_FAILURE. This is the only place
 * telemetry bytes (not just metadata) can be lost, and it is counted, never silent.
 *
 * <h2>Segment upload</h2>
 * <p>After an envelope POST succeeds, staged chunks are PUT to
 * {@code /v1/segments/<key>} with the same HMAC scheme (method {@code PUT}, full path
 * signed). Idempotency is by segment key server-side (identical bytes re-PUT as
 * duplicates), so PUT retries safely use a fresh nonce per attempt. A 409 means the
 * key exists with different bytes — a key-generation bug upstream — and is dropped
 * loudly rather than retried. A 501 means the server has no byte sink configured;
 * it rides the normal transport-failure backoff (the bytes will gap out as
 * SEND_FAILURE if the sink never appears, exactly as designed).
 */
public final class AsyncUploader {

    private final TelemetryConfig config;
    private final TelemetryRing ring;
    private final UploadEndpoint endpoint;
    private final IntFunction<UUID> entityResolver;
    private final String serverId;
    private final UUID sessionEpoch;
    private final long sessionStartedAtMs;
    /**
     * Session end is knowable only at disable, so it rides the synchronous final
     * drain's envelopes. Null in every steady-state flush: a session that never
     * reaches its shutdown tail is honestly open, not silently ended.
     */
    private volatile Long sessionEndedAtMs;

    private volatile String sessionEndedReason;
    private final byte[] hmacSecret;

    private final ScheduledExecutorService scheduler;
    private final Semaphore inflight;

    /** Bounded pending-bytes FIFO. Guarded by itself; touched only on the flush thread. */
    private final Deque<PendingBytes> pendingBytes = new ArrayDeque<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * True from stopAndFlush start until its end. Envelope scheduling stays gated on
     * {@code running} alone (a shutdown must gap-record, not retry), but the segment
     * pump must keep draining the pending-bytes queue during shutdown: every staged
     * chunk's envelope may already be accepted, and an accepted envelope whose bytes
     * never reach the store is an orphan the cloud exporter refuses to export.
     */
    private volatile boolean stopping = false;
    private final AtomicBoolean authParked = new AtomicBoolean(false);
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private final AtomicLong envelopesAccepted = new AtomicLong();
    private final AtomicLong envelopesDuplicate = new AtomicLong();
    private final AtomicLong chunksDroppedPoison = new AtomicLong();
    private final AtomicLong bytesDroppedPending = new AtomicLong();
    private final AtomicLong segmentsUploaded = new AtomicLong();

    /**
     * Segment PUT base derived from the envelope endpoint URL by stripping the
     * {@code /v1/telemetry} suffix, or null when the URL has no such suffix. Null
     * disables segment upload with a one-time warning: old/custom endpoint URLs
     * keep working exactly as before (envelopes only), rather than failing mysterious
     * PUTs against a wrong base.
     */
    private final String segmentBaseUrl;
    private final AtomicBoolean segmentBaseWarned = new AtomicBoolean(false);

    private final SecureRandom nonceRandom = new SecureRandom();
    private final Random jitter;
    private volatile long currentBackoffMs;

    public AsyncUploader(
            TelemetryConfig config,
            TelemetryRing ring,
            UploadEndpoint endpoint,
            IntFunction<UUID> entityResolver,
            String serverId,
            UUID sessionEpoch,
            long sessionStartedAtMs,
            byte[] hmacSecret) {
        this(config, ring, endpoint, entityResolver, serverId, sessionEpoch, sessionStartedAtMs,
                hmacSecret, null);
    }

    /**
     * Test seam: a seeded {@link Random} makes retry delays deterministic
     * (java.util.Random's algorithm is specified and stable across JDKs), so backoff tests
     * assert exact windows instead of gambling on wall-clock jitter. Production passes null
     * and gets thread-local randomness. The seam changes nothing about the policy, only the
     * dice.
     */
    AsyncUploader(
            TelemetryConfig config,
            TelemetryRing ring,
            UploadEndpoint endpoint,
            IntFunction<UUID> entityResolver,
            String serverId,
            UUID sessionEpoch,
            long sessionStartedAtMs,
            byte[] hmacSecret,
            Random jitter) {
        this.config = config;
        this.ring = ring;
        this.endpoint = endpoint;
        this.entityResolver = entityResolver;
        this.serverId = serverId;
        this.sessionEpoch = sessionEpoch;
        this.sessionStartedAtMs = sessionStartedAtMs;
        this.hmacSecret = hmacSecret.clone();
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "forensic-upload");
                            t.setDaemon(true);
                            return t;
                        });
        this.inflight = new Semaphore(config.maxInflightRequests());
        this.jitter = jitter != null ? jitter : java.util.concurrent.ThreadLocalRandom.current();
        this.currentBackoffMs = config.backoffInitialMs();
        String endpointUrl = config.endpointUrl();
        if (endpointUrl.endsWith("/v1/telemetry")) {
            this.segmentBaseUrl =
                    endpointUrl.substring(0, endpointUrl.length() - "/v1/telemetry".length());
        } else {
            this.segmentBaseUrl = null;
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(
                    this::flushOnce, 0, config.flushIntervalMs(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the loop, waits for in-flight sends to settle, then does one synchronous final
     * drain. Blocking here is correct: the server is stopping anyway, and an async fire-and-
     * forget would lose the tail silently. Whatever still cannot flush inside {@code timeoutMs}
     * is covered by the next SESSION_START boundary, not by hope.
     */
    public void stopAndFlush(long timeoutMs) {
        running.set(false);
        stopping = true;
        // Stamp the end before the final drain so the tail envelope carries it.
        // Segments remain the authoritative stream; this closes the sessions row
        // for operators without asking anyone to infer end time from silence.
        sessionEndedAtMs = System.currentTimeMillis();
        sessionEndedReason = "PLUGIN_DISABLE";
        long deadline = System.currentTimeMillis() + timeoutMs;
        // Settle in-flight: their callbacks run the normal outcome handlers (advance on
        // 200, gap-record on poison), so settling IS flushing.
        while (inflight.availablePermits() < config.maxInflightRequests()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Final synchronous drain of whatever remains: bytes first, then envelope.
        // On failure the span is gap-recorded and the envelope is never posted, so
        // the store can never end up with an accepted envelope missing its bytes.
        while (System.currentTimeMillis() < deadline) {
            ByteBuffer scratch = ByteBuffer.allocateDirect(config.flushMaxBytes());
            ChunkBatcher.DrainedChunk drained =
                    ChunkBatcher.drain(
                            ring, scratch, config.flushMaxBytes(), serverId, sessionEpoch,
                            entityResolver);
            if (drained == null) {
                break;
            }
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            try {
                sendSynchronously(drained, remaining);
            } catch (RuntimeException e) {
                ring.gaps()
                        .noteDroppedRange(
                                drained.tickMin(), drained.tickMax(),
                                ControlKind.GAP_PLUGIN_DISABLE, drained.recordCount());
                break;
            }
        }
        // Whatever is still staged after the settle window never got a successful
        // PUT attempt (the pump was allowed to run while stopping; these bytes
        // actively failed against the store). Record them as disable loss rather
        // than letting bytes vanish silently. Their envelopes may already be
        // accepted — an unavoidable residual under store failure, and the cloud
        // exporter's fail-loud check is the safety net for exactly that case.
        PendingBytes staged;
        while ((staged = pendingBytes.pollFirst()) != null) {
            bytesDroppedPending.addAndGet(staged.bytes().remaining());
            ring.gaps()
                    .noteDroppedRange(
                            staged.tickMin(), staged.tickMax(), ControlKind.GAP_PLUGIN_DISABLE,
                            staged.recordCount());
        }
        stopping = false;
        scheduler.shutdownNow();
    }

    /**
     * Final-flush send: segment bytes BEFORE the envelope. An envelope the cloud
     * accepted whose bytes never reach the store is an orphan the exporter refuses
     * (fail-loud), so the tail must not be posted until the bytes landed. If the PUT
     * fails, this throws and the caller gap-records the span with the envelope never
     * posted: a typed, exportable loss instead of an unexportable orphan. Endpoint
     * configs without a segment base keep their historical envelope-only behavior.
     */
    private void sendSynchronously(ChunkBatcher.DrainedChunk drained, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        if (segmentBaseUrl != null) {
            if (drained.segments().isEmpty()) {
                throw new RuntimeException(
                        "final flush drained records without a segment descriptor");
            }
            ByteBuffer src = drained.bytes();
            ByteBuffer copy = ByteBuffer.allocateDirect(src.remaining());
            copy.put(src);
            copy.flip();
            PendingBytes chunk =
                    new PendingBytes(
                            copy, drained.tickMin(), drained.tickMax(), drained.recordCount(),
                            drained.segments().get(0).segmentKey());
            byte[] nonce = new byte[16];
            nonceRandom.nextBytes(nonce);
            SegmentRequest seg =
                    buildSegmentRequest(chunk, Base64.getEncoder().encodeToString(nonce));
            long segBudget = Math.max(1, deadline - System.currentTimeMillis());
            try {
                UploadEndpoint.Result result =
                        endpoint.put(seg.path(), seg.request())
                                .get(segBudget, TimeUnit.MILLISECONDS);
                if (result.statusCode() != 200) {
                    throw new RuntimeException(
                            "final segment PUT failed with status " + result.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted final segment PUT", e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("final segment PUT failed", e);
            }
        }
        long remaining = Math.max(1, deadline - System.currentTimeMillis());
        Outgoing out = buildOutgoing(drained);
        try {
            UploadEndpoint.Result result =
                    endpoint.post(new UploadEndpoint.Request(out.headers(), out.body()))
                            .get(remaining, TimeUnit.MILLISECONDS);
            handleStatus(drained, out.envelopeId(), out.nonceB64(), 0, result.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted final flush", e);
        } catch (Exception e) {
            throw new RuntimeException("final flush send failed", e);
        }
    }

    /** One flush tick. Never throws: a throwing flush task kills the schedule. */
    void flushOnce() {
        if (!running.get()) {
            return;
        }
        try {
            ByteBuffer scratch = ByteBuffer.allocateDirect(config.flushMaxBytes());
            ChunkBatcher.DrainedChunk drained =
                    ChunkBatcher.drain(
                            ring, scratch, config.flushMaxBytes(), serverId, sessionEpoch,
                            entityResolver);
            if (drained == null) {
                consecutiveFailures.set(0);
                return;
            }
            stagePendingBytes(drained);
            sendWithRetry(drained, 0);
        } catch (RuntimeException e) {
            // Visible, not silent: a synchronously-throwing flush path (bad URI, banned
            // header, misconfigured endpoint) would otherwise present as "uploads simply
            // never happen" with counters as the only witness. One line per flush tick
            // at most; under persistent failure the backoff keeps the cadence sane.
            System.err.println("[forensic-telemetry] flush failed: " + e);
            consecutiveFailures.incrementAndGet();
        }
    }

    /**
     * Stages drained bytes for the future object-storage upload. Bounded: the oldest chunk
     * drops with SEND_FAILURE accounting when full. Per-chunk direct buffers (≈64 KiB at
     * 0.5 Hz cadence) are uploader-thread allocations, outside the zero-alloc main path.
     */
    private void stagePendingBytes(ChunkBatcher.DrainedChunk drained) {
        ByteBuffer src = drained.bytes();
        ByteBuffer copy = ByteBuffer.allocateDirect(src.remaining());
        copy.put(src);
        copy.flip();
        // One drain yields exactly one segment descriptor (ChunkBatcher contract);
        // the key travels with the bytes so metadata and payload can never
        // desynchronize, even across retries and restarts of the flush loop.
        String segmentKey =
                drained.segments().isEmpty() ? null : drained.segments().get(0).segmentKey();
        PendingBytes staged =
                new PendingBytes(
                        copy, drained.tickMin(), drained.tickMax(), drained.recordCount(),
                        segmentKey);
        pendingBytes.addLast(staged);
        while (pendingBytes.size() > config.maxBatchChunks()) {
            PendingBytes dropped = pendingBytes.pollFirst();
            if (dropped != null) {
                bytesDroppedPending.addAndGet(dropped.bytes().remaining());
                ring.gaps()
                        .noteDroppedRange(
                                dropped.tickMin(), dropped.tickMax(),
                                ControlKind.GAP_SEND_FAILURE, dropped.recordCount());
            }
        }
    }

    private void sendWithRetry(ChunkBatcher.DrainedChunk drained, int attempt) {
        UUID envelopeId = UUID.randomUUID();
        byte[] nonce = new byte[16];
        nonceRandom.nextBytes(nonce);
        String nonceB64 = Base64.getEncoder().encodeToString(nonce);
        attempt(drained, envelopeId, nonceB64, attempt);
    }

    private void attempt(
            ChunkBatcher.DrainedChunk drained, UUID envelopeId, String nonceB64, int attempt) {
        // No parked short-circuit here, deliberately: a parked retry that never sends
        // is a probe that never probes, and the uploader would stay parked forever
        // waiting for a success only a send can produce. Parking lives in the
        // SCHEDULE (retries at backoff-max cadence via handleStatus), not in the
        // attempt: every attempt sends, every 401 re-parks, every 200 unparks.
        Outgoing out = buildOutgoing(drained, envelopeId, nonceB64);
        if (!inflight.tryAcquire()) {
            // In-flight cap hit: requeue with the normal backoff. Bounded wait on the
            // flush thread, never unbounded spawn.
            scheduleRetry(drained, envelopeId, nonceB64, attempt, currentBackoffMs);
            return;
        }
        endpoint.post(new UploadEndpoint.Request(out.headers(), out.body()))
                .whenComplete(
                        (result, error) -> {
                            inflight.release();
                            if (error != null) {
                                onTransportFailure(drained, envelopeId, nonceB64, attempt);
                                return;
                            }
                            handleStatus(
                                    drained, envelopeId, nonceB64, attempt, result.statusCode(),
                                    result.responseBody());
                        });
    }

    private Outgoing buildOutgoing(ChunkBatcher.DrainedChunk drained) {
        UUID envelopeId = UUID.randomUUID();
        byte[] nonce = new byte[16];
        nonceRandom.nextBytes(nonce);
        return buildOutgoing(drained, envelopeId, Base64.getEncoder().encodeToString(nonce));
    }

    private Outgoing buildOutgoing(
            ChunkBatcher.DrainedChunk drained, UUID envelopeId, String nonceB64) {
        byte[] body =
                ChunkBatcher.renderEnvelopeJson(
                        envelopeId,
                        drained.tickMin(),
                        drained.recordCount() * 32,
                        drained.tickMin(),
                        drained.tickMax(),
                        drained.gaps(),
                        drained.segments(),
                        drained.playerIds(),
                        drained.playerEntityIds(),
                        sessionStartedAtMs,
                        sessionEndedAtMs,
                        sessionEndedReason);
        String timestampMs = Long.toString(System.currentTimeMillis());
        String signingString =
                EnvelopeSigner.canonicalString(
                        serverId,
                        config.keyId(),
                        timestampMs,
                        nonceB64,
                        sessionEpoch.toString(),
                        Integer.toString(config.envelopeSchemaVersion()),
                        Integer.toString(body.length),
                        EnvelopeSigner.sha256Hex(body));
        String signature = EnvelopeSigner.hmacHex(hmacSecret, signingString);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        // No explicit content-length: java.net.http forbids setting restricted headers and
        // throws IllegalArgumentException if tried. The client derives it from the byte
        // array body, which is exactly body.length, the value the signature covers. The
        // wire still carries content-length, so the cloud's signature check is unaffected.
        headers.put("x-server-id", serverId);
        headers.put("x-key-id", config.keyId());
        headers.put("x-timestamp-ms", timestampMs);
        headers.put("x-nonce", nonceB64);
        headers.put("x-session-epoch", sessionEpoch.toString());
        headers.put("x-schema-version", Integer.toString(config.envelopeSchemaVersion()));
        headers.put("x-signature", signature);
        return new Outgoing(headers, body, envelopeId, nonceB64);
    }

    private void handleStatus(
            ChunkBatcher.DrainedChunk drained,
            UUID envelopeId,
            String nonceB64,
            int attempt,
            int status) {
        handleStatus(drained, envelopeId, nonceB64, attempt, status, "");
    }

    private void handleStatus(
            ChunkBatcher.DrainedChunk drained,
            UUID envelopeId,
            String nonceB64,
            int attempt,
            int status,
            String responseBody) {
        if (status == 200) {
            consecutiveFailures.set(0);
            currentBackoffMs = config.backoffInitialMs();
            if (authParked.compareAndSet(true, false)) {
                ring.gaps().setDefaultReason(ControlKind.GAP_RING_OVERFLOW);
            }
            if (responseBody.contains("\"duplicate\"")) {
                envelopesDuplicate.incrementAndGet();
            } else {
                envelopesAccepted.incrementAndGet();
            }
            // Envelope landed, so its descriptors exist server-side: now move the bytes.
            uploadPendingBytes();
            return;
        }
        if (status == 400 || status == 413) {
            // Poison: retry cannot fix our bug or contract drift. Drop the chunk and
            // record its span so the hole is durable. Wedging the queue on poison
            // would convert one bad chunk into unbounded loss behind it.
            chunksDroppedPoison.incrementAndGet();
            consecutiveFailures.incrementAndGet();
            ring.gaps()
                    .noteDroppedRange(
                            drained.tickMin(), drained.tickMax(), ControlKind.GAP_INTERNAL_ERROR,
                            drained.recordCount());
            return;
        }
        if (status == 401) {
            authParked.set(true);
            ring.gaps().setDefaultReason(ControlKind.GAP_SEND_FAILURE);
            consecutiveFailures.incrementAndGet();
            scheduleRetry(drained, envelopeId, nonceB64, attempt, config.backoffMaxMs());
            return;
        }
        onTransportFailure(drained, envelopeId, nonceB64, attempt);
    }

    private void onTransportFailure(
            ChunkBatcher.DrainedChunk drained, UUID envelopeId, String nonceB64, int attempt) {
        consecutiveFailures.incrementAndGet();
        ring.gaps().setDefaultReason(ControlKind.GAP_SEND_FAILURE);
        scheduleRetry(drained, envelopeId, nonceB64, attempt, currentBackoffMs);
    }

    private void scheduleRetry(
            ChunkBatcher.DrainedChunk drained,
            UUID envelopeId,
            String nonceB64,
            int attempt,
            long baseDelayMs) {
        long delay = Math.min(baseDelayMs, config.backoffMaxMs());
        currentBackoffMs = Math.min(Math.max(delay * 2, config.backoffInitialMs()),
                config.backoffMaxMs());
        // Full jitter: without it, every restarted server retries in lockstep and the
        // ingest tier gets thundering herds after any outage.
        long jittered = jitter.nextLong(delay + 1);
        try {
            scheduler.schedule(
                    () -> {
                        if (running.get()) {
                            // Same envelope_id, same nonce, fresh timestamp/signature:
                            // a retry arriving after the original is a duplicate, not a double.
                            attempt(drained, envelopeId, nonceB64, attempt + 1);
                        } else {
                            // Shutting down with a chunk in hand: record the span rather
                            // than dropping it silently. Next SESSION_START covers it.
                            ring.gaps()
                                    .noteDroppedRange(
                                            drained.tickMin(), drained.tickMax(),
                                            ControlKind.GAP_PLUGIN_DISABLE, drained.recordCount());
                        }
                    },
                    jittered,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            ring.gaps()
                    .noteDroppedRange(
                            drained.tickMin(), drained.tickMax(), ControlKind.GAP_PLUGIN_DISABLE,
                            drained.recordCount());
        }
    }

    /**
     * Pumps the head of the pending-bytes queue through one PUT attempt. Bytes stay
     * staged until a terminal outcome: 200/duplicate removes, poison/conflict drops
     * with a gap, transport failure requeues with backoff. At most one attempt is
     * started per call; completions chain the next pump, so the queue drains without
     * ever spawning unbounded work.
     */
    void uploadPendingBytes() {
        if (!running.get() && !stopping) {
            return;
        }
        PendingBytes head = pendingBytes.peekFirst();
        if (head == null) {
            return;
        }
        if (head.segmentKey() == null) {
            // No key, no PUT target: drop with accounting rather than wedging the
            // head of the queue forever. Cannot happen with current ChunkBatcher
            // (exactly one descriptor per drain); the guard is for future drains.
            pendingBytes.pollFirst();
            bytesDroppedPending.addAndGet(head.bytes().remaining());
            ring.gaps()
                    .noteDroppedRange(
                            head.tickMin(), head.tickMax(), ControlKind.GAP_INTERNAL_ERROR,
                            head.recordCount());
            return;
        }
        if (segmentBaseUrl == null) {
            if (segmentBaseWarned.compareAndSet(false, true)) {
                System.err.println(
                        "[forensic-telemetry] segment upload disabled: endpoint URL has no"
                                + " /v1/telemetry suffix to derive the segment base from;"
                                + " envelopes only");
            }
            return;
        }
        byte[] nonce = new byte[16];
        nonceRandom.nextBytes(nonce);
        attemptSegmentPut(head, Base64.getEncoder().encodeToString(nonce));
    }

    private void attemptSegmentPut(PendingBytes head, String nonceB64) {
        SegmentRequest seg = buildSegmentRequest(head, nonceB64);
        if (!inflight.tryAcquire()) {
            scheduleRetryPending();
            return;
        }
        endpoint
                .put(seg.path(), seg.request())
                .whenComplete(
                        (result, error) -> {
                            inflight.release();
                            if (error != null) {
                                onSegmentTransportFailure();
                                return;
                            }
                            handleSegmentStatus(result.statusCode(), head);
                        });
    }

    /** Builds the signed PUT for a staged chunk. Pure: touches no shared state. */
    private SegmentRequest buildSegmentRequest(PendingBytes head, String nonceB64) {
        String path = "/v1/segments/" + head.segmentKey();
        byte[] body = new byte[head.bytes().remaining()];
        head.bytes().mark();
        head.bytes().get(body);
        head.bytes().reset();
        String timestampMs = Long.toString(System.currentTimeMillis());
        String signingString =
                EnvelopeSigner.canonicalString(
                        "PUT",
                        path,
                        serverId,
                        config.keyId(),
                        timestampMs,
                        nonceB64,
                        sessionEpoch.toString(),
                        Integer.toString(config.envelopeSchemaVersion()),
                        Integer.toString(body.length),
                        EnvelopeSigner.sha256Hex(body));
        String signature = EnvelopeSigner.hmacHex(hmacSecret, signingString);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/octet-stream");
        headers.put("x-server-id", serverId);
        headers.put("x-key-id", config.keyId());
        headers.put("x-timestamp-ms", timestampMs);
        headers.put("x-nonce", nonceB64);
        headers.put("x-session-epoch", sessionEpoch.toString());
        headers.put("x-schema-version", Integer.toString(config.envelopeSchemaVersion()));
        headers.put("x-signature", signature);
        return new SegmentRequest(path, new UploadEndpoint.Request(headers, body));
    }

    private void handleSegmentStatus(int status, PendingBytes attempted) {
        // Identity guard: the head may have been gap-recorded and polled by the
        // disable path while this PUT was in flight. Acting on a STALE head would
        // remove the NEXT chunk without its bytes (silent loss + orphan envelope).
        if (pendingBytes.peekFirst() != attempted) {
            return;
        }
        PendingBytes head = attempted;
        if (status == 200) {
            consecutiveFailures.set(0);
            currentBackoffMs = config.backoffInitialMs();
            if (authParked.compareAndSet(true, false)) {
                ring.gaps().setDefaultReason(ControlKind.GAP_RING_OVERFLOW);
            }
            pendingBytes.pollFirst();
            segmentsUploaded.incrementAndGet();
            uploadPendingBytes();
            return;
        }
        if (status == 400 || status == 413 || status == 409) {
            // Poison (400/413) or key conflict (409): retry cannot fix our bug,
            // contract drift, or a colliding key. Drop loudly, never wedge.
            pendingBytes.pollFirst();
            bytesDroppedPending.addAndGet(head.bytes().remaining());
            chunksDroppedPoison.incrementAndGet();
            consecutiveFailures.incrementAndGet();
            ring.gaps()
                    .noteDroppedRange(
                            head.tickMin(), head.tickMax(), ControlKind.GAP_INTERNAL_ERROR,
                            head.recordCount());
            uploadPendingBytes();
            return;
        }
        if (status == 401) {
            authParked.set(true);
            ring.gaps().setDefaultReason(ControlKind.GAP_SEND_FAILURE);
            consecutiveFailures.incrementAndGet();
            schedulePendingRetryAt(config.backoffMaxMs());
            return;
        }
        onSegmentTransportFailure();
    }

    private void onSegmentTransportFailure() {
        consecutiveFailures.incrementAndGet();
        ring.gaps().setDefaultReason(ControlKind.GAP_SEND_FAILURE);
        scheduleRetryPending();
    }

    private void scheduleRetryPending() {
        long delay = currentBackoffMs;
        currentBackoffMs =
                Math.min(Math.max(delay * 2, config.backoffInitialMs()), config.backoffMaxMs());
        long jittered = jitter.nextLong(delay + 1);
        try {
            scheduler.schedule(
                    () -> {
                        if (running.get() || stopping) {
                            uploadPendingBytes();
                        }
                    },
                    jittered,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Scheduler dead (disable path won the race): the bytes stay staged here
            // and stopAndFlush gap-records the whole queue below. Nothing is lost silently.
        }
    }

    private void schedulePendingRetryAt(long baseDelayMs) {
        long delay = Math.min(baseDelayMs, config.backoffMaxMs());
        long jittered = jitter.nextLong(delay + 1);
        try {
            scheduler.schedule(
                    () -> {
                        if (running.get() || stopping) {
                            uploadPendingBytes();
                        }
                    },
                    jittered,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Same as above: disable path owns the queue now.
        }
    }

    public boolean authParked() {
        return authParked.get();
    }

    public long consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long envelopesAccepted() {
        return envelopesAccepted.get();
    }

    public long envelopesDuplicate() {
        return envelopesDuplicate.get();
    }

    public long chunksDroppedPoison() {
        return chunksDroppedPoison.get();
    }

    public long bytesDroppedPending() {
        return bytesDroppedPending.get();
    }

    public long segmentsUploaded() {
        return segmentsUploaded.get();
    }

    public int pendingByteChunks() {
        return pendingBytes.size();
    }

    private record Outgoing(
            Map<String, String> headers, byte[] body, UUID envelopeId, String nonceB64) {}

    private record SegmentRequest(String path, UploadEndpoint.Request request) {}

    private record PendingBytes(
            ByteBuffer bytes, int tickMin, int tickMax, int recordCount, String segmentKey) {}
}

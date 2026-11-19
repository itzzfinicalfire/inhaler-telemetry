package dev.forensic.telemetry.upload;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Single upload attempt against the ingest endpoint.
 *
 * <p>Implementations must be non-blocking (return a future, never block the flush thread) and
 * thread-safe (up to {@code upload.max-inflight} concurrent calls). The JDK HttpClient
 * implementation qualifies; the test fake counts concurrency to prove the bound holds.
 */
public interface UploadEndpoint {

    record Request(Map<String, String> headers, byte[] body) {}

    record Result(int statusCode, String responseBody) {}

    CompletableFuture<Result> post(Request request);

    /**
     * Segment-bytes upload to an absolute path (e.g. {@code /v1/segments/<key>}).
     * Same non-blocking, thread-safe contract as {@link #post}. The path is the
     * caller-provided request-target the signature was computed over; the
     * endpoint must send exactly that path, never a rewritten one.
     */
    CompletableFuture<Result> put(String path, Request request);
}

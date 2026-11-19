package dev.forensic.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Telemetry configuration.
 *
 * <p>Defaults match the approved design: 64 MiB off-heap ring, 64 KiB chunks, 40-tick absolute
 * resync, 32-byte per-tick record. Every value is validated at load so a bad config fails at
 * startup on the server rather than midway through a fight.
 */
public record TelemetryConfig(
        String endpointUrl,
        String serverId,
        String keyId,
        String keySecret,
        int bufferBytes,
        int chunkBytes,
        int resyncIntervalTicks,
        int flushIntervalMs,
        int flushMaxBytes,
        int maxBatchChunks,
        int maxInflightRequests,
        int backoffInitialMs,
        int backoffMaxMs,
        int timestampSkewToleranceMs,
        int recordSchemaVersion,
        int envelopeSchemaVersion,
        boolean enabled,
        boolean consoleReportEnabled,
        int consoleReportIntervalSeconds,
        String consoleReportUrl,
        String consoleReportToken,
        Map<String, String> raw) {

    /** Per-tick record size: 32 B (§D.1). Asserted against by the encoder. */
    public static final int RECORD_BYTES = 32;

    /** Absolute resync every 2.0 s. Bounds quantization drift to ~0.005 blocks (§D.1). */
    public static final int DEFAULT_RESYNC_INTERVAL_TICKS = 40;

    public static final int DEFAULT_BUFFER_BYTES = 64 * 1024 * 1024;
    public static final int DEFAULT_CHUNK_BYTES = 64 * 1024;

    /**
     * Keys whose presence would imply the plugin intends to act on players rather than observe
     * them. Presence is enough: the plugin has no use for them, so their appearance means the
     * config was written against a different design.
     */
    private static final List<String> FORBIDDEN_KEYS =
            List.of(
                    "actions.punish",
                    "actions.kick",
                    "actions.ban",
                    "actions.setback",
                    "actions.cancel-damage",
                    "actions.diagnostic");

    public static TelemetryConfig load(Path configFile) {
        if (!Files.exists(configFile)) {
            return defaults();
        }
        try (InputStream in = Files.newInputStream(configFile)) {
            return fromRaw(readFlatYaml(in));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + configFile, e);
        }
    }

    public static TelemetryConfig defaults() {
        return fromRaw(Map.of());
    }

    public static TelemetryConfig fromRaw(Map<String, String> raw) {
        Map<String, String> v = new LinkedHashMap<>(raw);
        String endpointUrl = str(v, "endpoint.url", "https://ingest.invalid/v1/telemetry");
        return new TelemetryConfig(
                endpointUrl,
                str(v, "server.id", "00000000-0000-0000-0000-000000000000"),
                str(v, "server.key-id", "unset"),
                // Empty by default: a fresh install must fail closed (refuse to enable)
                // rather than sign with a placeholder secret every install shares.
                str(v, "server.key-secret", ""),
                i(v, "buffer.bytes", DEFAULT_BUFFER_BYTES),
                i(v, "buffer.chunk-bytes", DEFAULT_CHUNK_BYTES),
                i(v, "encoder.resync-interval-ticks", DEFAULT_RESYNC_INTERVAL_TICKS),
                i(v, "flush.interval-ms", 2000),
                i(v, "flush.max-bytes", 64 * 1024),
                i(v, "flush.max-batch-chunks", 16),
                i(v, "upload.max-inflight", 2),
                i(v, "upload.backoff-initial-ms", 1000),
                i(v, "upload.backoff-max-ms", 60000),
                i(v, "upload.timestamp-skew-tolerance-ms", 30000),
                i(v, "schema.record-version", 1),
                i(v, "schema.envelope-version", 1),
                bool(v, "telemetry.enabled", false),
                bool(v, "console-report.enabled", false),
                i(v, "console-report.interval-seconds", 30),
                str(v, "console-report.url", reportUrlFrom(endpointUrl)),
                str(v, "console-report.token", ""),
                Map.copyOf(v));
    }

    /**
     * Derive the read-only report URL from the ingest URL, so an operator only
     * configures one endpoint. {@code console-report.url} overrides this when a
     * proxy exposes the two paths on different hosts.
     */
    static String reportUrlFrom(String endpointUrl) {
        int query = endpointUrl.indexOf('?');
        String base = (query >= 0 ? endpointUrl.substring(0, query) : endpointUrl).replaceAll("/+$", "");
        String suffix = "/telemetry";
        if (base.endsWith(suffix)) {
            return base.substring(0, base.length() - suffix.length()) + "/report";
        }
        return base + "/v1/report";
    }

    /** Configuration keys implying player-facing action. Must stay empty. */
    public List<String> reportOnlyViolations() {
        List<String> hits = new ArrayList<>();
        for (String key : FORBIDDEN_KEYS) {
            String v = raw.get(key);
            if (v != null && !v.isBlank() && !"false".equalsIgnoreCase(v.trim())) {
                hits.add(key);
            }
        }
        return hits;
    }

    public String rawValue(String key) {
        return raw.get(key);
    }

    public void validate() {
        if (enabled) {
            require(!endpointUrl.contains("ingest.invalid"),
                    "telemetry.enabled requires a real endpoint.url");
            require(!keySecret.isBlank(), "telemetry.enabled requires server.key-secret");
            require(keySecret.length() >= 16, "server.key-secret must be at least 16 characters");
            require(!serverId.equals("00000000-0000-0000-0000-000000000000"),
                    "telemetry.enabled requires a real server.id");
            require(!keyId.equals("unset"), "telemetry.enabled requires server.key-id");
        }
        require(bufferBytes >= chunkBytes * 16L, "buffer.bytes must hold at least 16 chunks");
        require(isPowerOfTwo(chunkBytes), "buffer.chunk-bytes must be a power of two");
        require(bufferBytes % chunkBytes == 0, "buffer.bytes must be a multiple of chunk size");
        require(resyncIntervalTicks >= 1 && resyncIntervalTicks <= 400,
                "encoder.resync-interval-ticks must be 1..400");
        require(flushIntervalMs >= 100, "flush.interval-ms must be >= 100");
        require(backoffInitialMs < backoffMaxMs, "upload backoff initial must be < max");
        require(maxInflightRequests >= 1 && maxInflightRequests <= 4,
                "upload.max-inflight must be 1..4");
        require(timestampSkewToleranceMs >= 1000, "upload timestamp tolerance must be >= 1000ms");
        require(recordSchemaVersion >= 1, "schema.record-version must be >= 1");
        if (consoleReportEnabled) {
            require(!consoleReportUrl.contains("ingest.invalid"),
                    "console-report.enabled requires a real endpoint.url or console-report.url");
            require(consoleReportToken.length() >= 16,
                    "console-report.enabled requires console-report.token (>= 16 chars)");
            require(consoleReportIntervalSeconds >= 5 && consoleReportIntervalSeconds <= 600,
                    "console-report.interval-seconds must be 5..600");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("invalid telemetry config: " + message);
        }
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    // ── minimal dotted-key YAML reader ───────────────────────────────────────
    // Avoids a YAML dependency in a plugin where every added dependency is main-thread risk.
    static Map<String, String> readFlatYaml(InputStream in) {
        try {
            return parseYaml(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read config stream", e);
        }
    }

    private static Map<String, String> parseYaml(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Entry> stack = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).split("#", 2)[0].strip();
            while (!stack.isEmpty() && indent <= stack.getLast().depth()) {
                stack.removeLast();
            }
            String prefix = stack.isEmpty() ? "" : stack.getLast().path() + ".";
            if (value.isEmpty()) {
                stack.add(new Entry(indent, prefix + key));
            } else {
                out.put((prefix + key).toLowerCase(Locale.ROOT), unquote(value));
            }
        }
        return out;
    }

    private record Entry(int depth, String path) {}

    private static String unquote(String v) {
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String str(Map<String, String> m, String k, String fallback) {
        String v = m.get(k);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int i(Map<String, String> m, String k, int fallback) {
        String v = m.get(k);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("config key " + k + " is not an integer: " + v);
        }
    }

    private static boolean bool(Map<String, String> m, String k, boolean fallback) {
        String v = m.get(k);
        return v == null || v.isBlank() ? fallback : Boolean.parseBoolean(v.trim());
    }
}
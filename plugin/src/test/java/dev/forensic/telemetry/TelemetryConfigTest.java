package dev.forensic.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelemetryConfigTest {

    @Test
    @DisplayName("defaults match the approved design")
    void defaultsMatchDesign() {
        TelemetryConfig c = TelemetryConfig.defaults();
        assertEquals(64 * 1024 * 1024, c.bufferBytes());
        assertEquals(64 * 1024, c.chunkBytes());
        assertEquals(32, TelemetryConfig.RECORD_BYTES);
        assertEquals(40, c.resyncIntervalTicks());
        assertTrue(c.reportOnlyViolations().isEmpty());
        c.validate();
    }

    @Test
    @DisplayName("telemetry is off by default")
    void telemetryOffByDefault() {
        assertEquals(false, TelemetryConfig.defaults().enabled());
    }

    @Test
    @DisplayName("nested YAML flattens to dotted keys")
    void parsesNestedYaml() {
        String yaml =
                """
                # comment
                telemetry:
                  enabled: true
                buffer:
                  bytes: 16777216
                  chunk-bytes: 32768
                upload:
                  backoff-initial-ms: 500
                  backoff-max-ms: 30000
                """;
        Map<String, String> raw = TelemetryConfig.readFlatYaml(stream(yaml));
        TelemetryConfig c = TelemetryConfig.fromRaw(raw);

        assertTrue(c.enabled());
        assertEquals(16 * 1024 * 1024, c.bufferBytes());
        assertEquals(32768, c.chunkBytes());
        assertEquals(500, c.backoffInitialMs());
    }

    @Test
    @DisplayName("inline comments and quotes are stripped")
    void handlesCommentsAndQuotes() {
        String yaml =
                """
                endpoint:
                  url: "https://a.example/v1" # primary
                server:
                  key-id: 'k1'
                """;
        TelemetryConfig c = TelemetryConfig.fromRaw(TelemetryConfig.readFlatYaml(stream(yaml)));
        assertEquals("https://a.example/v1", c.endpointUrl());
        assertEquals("k1", c.keyId());
    }

    @Test
    @DisplayName("non-numeric numeric key fails loudly at load, not at validate")
    void rejectsNonNumeric() {
        String yaml =
                """
                buffer:
                  bytes: lots
                """;
        // Fail-fast is deliberate: a typo in a numeric key must surface at startup
        // rather than silently falling back to a default that hides the typo.
        assertThrows(
                IllegalArgumentException.class,
                () -> TelemetryConfig.fromRaw(TelemetryConfig.readFlatYaml(stream(yaml))));
    }

    @Test
    @DisplayName("buffer must be a multiple of chunk size")
    void rejectsBadBufferGeometry() {
        TelemetryConfig c =
                TelemetryConfig.fromRaw(Map.of("buffer.chunk-bytes", "65536", "buffer.bytes", "100000"));
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    @DisplayName("chunk size must be a power of two")
    void rejectsNonPowerOfTwoChunk() {
        TelemetryConfig c =
                TelemetryConfig.fromRaw(Map.of("buffer.chunk-bytes", "50000", "buffer.bytes", "4000000"));
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    @DisplayName("resync interval above 400 ticks is rejected (drift bound)")
    void rejectsExcessiveResyncInterval() {
        TelemetryConfig c = TelemetryConfig.fromRaw(Map.of("encoder.resync-interval-ticks", "1000"));
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    @DisplayName("inflight requests are capped")
    void capsInflight() {
        TelemetryConfig c = TelemetryConfig.fromRaw(Map.of("upload.max-inflight", "16"));
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    @DisplayName("action keys are reported as report-only violations")
    void detectsActionKeys() {
        TelemetryConfig c =
                TelemetryConfig.fromRaw(Map.of("actions.punish", "true", "actions.kick", "ban"));
        assertEquals(
                java.util.List.of("actions.punish", "actions.kick"), c.reportOnlyViolations());
    }

    @Test
    @DisplayName("action keys disabled explicitly are not violations")
    void disabledActionKeysAreFine() {
        TelemetryConfig c =
                TelemetryConfig.fromRaw(Map.of("actions.punish", "false", "actions.ban", ""));
        assertTrue(c.reportOnlyViolations().isEmpty());
    }

    @Test
    @DisplayName("console report URL is derived from the ingest URL")
    void derivesConsoleReportUrl() {
        assertEquals(
                "https://a.example/v1/report",
                TelemetryConfig.reportUrlFrom("https://a.example/v1/telemetry"));
        assertEquals(
                "https://a.example/v1/report",
                TelemetryConfig.reportUrlFrom("https://a.example/v1/telemetry/"));
    }

    @Test
    @DisplayName("console report is off by default and cannot enable without a token")
    void consoleReportRequiresToken() {
        TelemetryConfig off = TelemetryConfig.fromRaw(Map.of());
        assertFalse(off.consoleReportEnabled());

        TelemetryConfig missingToken =
                TelemetryConfig.fromRaw(Map.of("console-report.enabled", "true"));
        assertThrows(IllegalArgumentException.class, missingToken::validate);

        TelemetryConfig ok = TelemetryConfig.fromRaw(Map.of(
                "console-report.enabled", "true",
                "console-report.token", "0123456789abcdef",
                "endpoint.url", "https://a.example/v1/telemetry"));
        ok.validate();
    }

    @Test
    @DisplayName("console report keys are observation, not action")
    void consoleReportIsNotAReportOnlyViolation() {
        TelemetryConfig c = TelemetryConfig.fromRaw(Map.of(
                "console-report.enabled", "true",
                "console-report.token", "0123456789abcdef"));
        assertTrue(c.reportOnlyViolations().isEmpty());
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
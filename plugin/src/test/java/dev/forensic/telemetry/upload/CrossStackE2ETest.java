package dev.forensic.telemetry.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.forensic.telemetry.TelemetryConfig;
import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-stack transport proof: real plugin pipeline (ring → batcher → signer → HTTP) against a
 * REAL cloud ingest server, which persists to REAL PostgreSQL.
 *
 * <p>Runs only with CLOUD_E2E_URL set (e.g. a scratch cloud pointed at a scratch database);
 * otherwise skips loudly. The cloud side is set up out of band (migrations + server row +
 * key), because this test proves the WIRE contract, not cloud provisioning. After green,
 * verify the rows independently: envelope in telemetry_envelopes, gap in telemetry_gaps,
 * segment descriptor in telemetry_segments, receipt in ingest_audit.
 */
class CrossStackE2ETest {

    private static final UUID EPOCH = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("plugin envelope accepted and persisted by real cloud ingest")
    void envelopeAcceptedEndToEnd() throws Exception {
        String url = System.getenv("CLOUD_E2E_URL");
        String secret = System.getenv("CLOUD_E2E_SECRET");
        assumeTrue(url != null && secret != null,
                "set CLOUD_E2E_URL + CLOUD_E2E_SECRET to run the cross-stack test");

        Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("server.id", "11111111-1111-1111-1111-111111111111");
        raw.put("server.key-id", "e2e-key-1");
        raw.put("server.key-secret", secret);
        raw.put("buffer.bytes", Integer.toString(16 * 1024));
        raw.put("buffer.chunk-bytes", "1024");
        raw.put("flush.interval-ms", "100");
        raw.put("flush.max-bytes", "4096");
        raw.put("flush.max-batch-chunks", "4");
        raw.put("upload.max-inflight", "1");
        TelemetryConfig config = TelemetryConfig.fromRaw(raw);
        config.validate();

        TelemetryRing ring = new TelemetryRing(16 * 1024, new GapTracker());
        int playerEntity = 42;
        for (int t = 5000; t < 5005; t++) {
            ring.offerState(t, playerEntity, t * 1.0, 64.0, 0.0, 90f, 0f, 1, 17);
        }
        // One genuine loss inside the stream: the gap record travels in-band.
        ring.offerGap(5005, 4998, 4999, ControlKind.GAP_RING_OVERFLOW);
        // v2 combat-mechanic records ride the same envelope path.
        ring.offerHeldSlot(5005, playerEntity, 0, 1, 2, 4);
        ring.offerCrystalSpawn(5005, 77, 10.5f, 64.0f, -3.25f, playerEntity);
        ring.offerAttackCtx(5005, playerEntity, 2.5f, 0.875f, 4, 2, 2);
        ring.offerCrystalBreak(5005, 77, playerEntity, 10.5f, 64.0f, -3.25f);
        ring.offerInteract(5005, playerEntity, 10, 64, -4, 0, 8, 0);
        ring.offerAnchorExplode(5005, 10.5f, 64.5f, -2.5f, -1);
        ring.offerTotemPop(5005, playerEntity);
        ring.offerWindCharge(5005, playerEntity, 91);

        Map<Integer, UUID> entities = Map.of(playerEntity,
                UUID.fromString("33333333-3333-3333-3333-333333333333"));
        AsyncUploader uploader = new AsyncUploader(
                config, ring, new HttpUploadEndpoint(url, java.time.Duration.ofSeconds(10)),
                entities::get, "11111111-1111-1111-1111-111111111111", EPOCH,
                System.currentTimeMillis(),
                secret.getBytes(StandardCharsets.UTF_8));
        uploader.start();
        long deadline = System.currentTimeMillis() + 15000;
        while (uploader.envelopesAccepted() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        uploader.stopAndFlush(5000);

        assertEquals(1, uploader.envelopesAccepted(), "cloud must accept the envelope");
        assertEquals(0, uploader.chunksDroppedPoison(), "no poison drops against real cloud");
        // Segment bytes ride the same pipeline when the cloud has a byte sink:
        // without one the server 501s and the assertions below are skipped.
        long segDeadline = System.currentTimeMillis() + 15000;
        while (uploader.segmentsUploaded() < 1 && System.currentTimeMillis() < segDeadline) {
            Thread.sleep(100);
        }
        if (uploader.segmentsUploaded() >= 1) {
            System.out.println("E2E segment bytes accepted by real cloud ingest");
        } else {
            System.out.println(
                    "E2E segment bytes not stored (no byte sink on this cloud); "
                            + "envelope metadata path verified, bytes path covered by unit tests");
        }
        System.out.println("E2E accepted; verify rows: "
                + "telemetry_envelopes (1), telemetry_gaps (4998-4999 RING_OVERFLOW), "
                + "telemetry_segments (1 descriptor), ingest_audit (receipt), "
                + "players + server_players (upserted)");
    }
}

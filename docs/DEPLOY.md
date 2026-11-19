# Deploy the collector

This page covers the Minecraft side. The ingest service is separate. If you already run something that accepts the envelope format in `PROTOCOL.md`, point the plugin at it and skip the server details.

## Before you start

You need:

- Paper or Purpur 1.21.x
- Java 21
- A URL that accepts `POST /v1/telemetry` and `PUT /v1/segments/*`
- A server UUID and an HMAC secret registered on the ingest side

The collector has no database, no external process, and no companion plugin. It runs inside the Minecraft server process.

## Install

Drop `forensic-telemetry.jar` into `plugins/`. Start the server once so the plugin creates its data folder. Stop it again before editing the config.

The config lives at:

```text
plugins/ForensicTelemetry/config.yml
```

That path is case-sensitive on Linux. `forensictelemetry` and `ForensicTelemetry` are not the same directory.

Copy `plugin-config.example.yml` over the generated file. Fill these values:

```yaml
telemetry:
  enabled: true

endpoint:
  url: "https://ingest.example.com/v1/telemetry"

server:
  id: "00000000-0000-0000-0000-000000000000"
  key-id: "k1"
  key-secret: "a long random value, at least 16 characters"
```

The server UUID must match the row on the ingest side. The `key-id` names the secret so you can rotate keys without dropping data. Keep the secret in the config and nowhere else.

Start the server. The log should show:

```text
forensic-telemetry enabled: session=..., buffer=67108864 bytes, recordSchema=v2, envelopeSchema=v2
```

Join the server, walk around, hit something. Uploads begin after the first flush interval, which is two seconds by default.

## Sizing

The defaults fit most Paper servers:

- 64 MiB ring
- 64 KiB chunks
- two-second flush
- 40-tick resync
- two in-flight uploads

A busy 100-player server writes more state than a survival server with ten players. If the ring overflows, the log and the envelope gap records say so. Increase `buffer.bytes` before increasing `upload.max-inflight`. The ring is the shock absorber, not the network.

`upload.max-inflight` is capped at 4. Values above that are rejected at startup. `flush.interval-ms` below 100 is also rejected.

## Capture families

`capture.*` turns one mechanic family on or off:

```yaml
capture:
  crystal: true
  anchor: true
  slot-swap: true
  interact: true
  swing: true
  attack-context: true
```

Turning a family off stops those records only. State, attacks, damage, deaths, and gap accounting keep running, so a disabled family cannot make a session look quieter than it was. `attack-context` covers the mace family: fall distance, attack cooldown, held category, victim category, and wind charge launches.

Schema v2 is the default. The plugin still ingests v1 segments, and a v2 reader skips unknown control markers instead of failing the segment.

## Secrets and rotation

The secret is read at startup and kept in process memory. It is never logged, never placed in an envelope, and never written back to disk.

To rotate:

1. Add the new key on the ingest side under a new `key-id`, keeping the old one active.
2. Change `server.key-id` and `server.key-secret` in the plugin config.
3. Restart the Minecraft server.
4. Retire the old key after the last upload signed with it has aged out.

Rotating a single shared secret without overlap causes a gap. The plugin will retry, but the ingest side will keep rejecting the old key.

## Failure behavior

If the endpoint is unreachable, the plugin keeps recording into the ring and retries with backoff. The Minecraft server does not block on uploads.

If the ring fills, the oldest records are dropped and a gap range is recorded. Gaps travel to the ingest side in the next envelope. They are not hidden, and they are not treated as silence.

If the plugin is disabled cleanly, it writes a session-end envelope. If the server crashes, the next session starts with a session-start boundary. Both cases leave a visible edge in the capture.

## TLS

The plugin speaks plain HTTP or HTTPS. HMAC signing proves the request came from a holder of the secret. It does not encrypt the body. If the network between the Minecraft host and the ingest host is shared or untrusted, terminate TLS in front of the ingest service.

If you use a self-signed certificate, add it to the JVM trust store. The plugin does not disable certificate validation.

## Troubleshooting

**`telemetry.enabled=false: observing nothing`**
The plugin could not find your config and generated a default. Check the folder name and the file path.

**`401 bad_signature`**
The secret does not match, or the canonical field order changed. Compare the config value with the ingest side. Do not paste either value into a ticket.

**`401 stale_timestamp`**
The server clock is off by more than 30 seconds. Fix NTP on the Minecraft host.

**`413 body_too_large`**
The envelope exceeded the ingest body cap. Lower `flush.max-bytes` or raise the cap on the ingest side.

**No uploads, no errors**
Check that the endpoint URL ends at `/v1/telemetry` and that DNS and egress work from the Minecraft host. The plugin logs one upload failure per flush tick, not one per packet.

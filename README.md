# Forensic Telemetry Collector

This repository is the Minecraft side of a PvP telemetry pipeline. It records what the server already knows about movement and combat, packs those observations into signed uploads, and sends them to an ingest service you control.

It does not decide whether anyone cheats. The scoring service is separate and private. What you get here is the collector, the wire format, and the tests that keep the collector report-only.

## What it records

At 20 Hz, for each tracked player:

- position, yaw, and pitch
- ground, sprint, sneak, fly, swim, glide, climb, and riptide flags
- ping
- attack, damage, death, and interest events

Each tick is a fixed 32-byte record. The plugin keeps a 64 MiB off-heap ring, seals 64 KiB chunks every two seconds, and writes an absolute position and rotation resync every 40 ticks. Uploads are signed with HMAC-SHA256. If the endpoint is down, the ring absorbs the backlog and the gap tracker records what was lost. The server keeps running either way.

## What it never does

No kicks. No bans. No teleports. No damage. No attribute changes. No inventory changes. No packet cancellation.

That is enforced in the build, not just in a policy note. A test walks the compiled class files and fails on any reference to a mutating Bukkit or Paper method. The parser reads the constant pool, so a string literal cannot hide a call.

## Requirements

- Paper or Purpur 1.21.x
- Java 21 or newer
- An ingest endpoint that accepts the signed envelope format
- No PacketEvents, ProtocolLib, or companion plugin

## Install

1. Put `forensic-telemetry.jar` in `plugins/`.
2. Copy `plugin-config.example.yml` to `plugins/ForensicTelemetry/config.yml`. The folder name is case-sensitive on Linux. Capital F, capital T.
3. Set `endpoint.url`, `server.id`, `server.key-id`, and `server.key-secret`. Set `telemetry.enabled: true`.
4. Restart the server.

A healthy start logs:

```text
forensic-telemetry enabled: session=..., buffer=67108864 bytes, recordSchema=v1, envelopeSchema=v1
```

If you see `telemetry.enabled=false: observing nothing`, the config is missing or sitting in the wrong folder. The plugin writes a default config when it cannot find yours, so check the path before editing anything else.

## Build and test

```sh
./gradlew :plugin:test :plugin:jar
```

The jar lands in `plugin/build/libs/forensic-telemetry.jar`. The suite covers the encoder, ring, gap accounting, upload signing, config validation, and the report-only bytecode check.

`docs/PROTOCOL.md` describes the envelope, the headers, and the 32-byte record layout. `docs/DEPLOY.md` covers the config in more detail.

## Configuration that matters

The example config is the source of truth. A few values cause most support questions:

- `buffer.bytes` must be a multiple of `buffer.chunk-bytes` and hold at least 16 chunks.
- `encoder.resync-interval-ticks` is capped at 400. Lower values bound drift and cost bandwidth.
- `upload.max-inflight` is capped at 4. The ring handles bursts.
- `flush.interval-ms` below 100 is rejected.

Secrets live in the config file and process memory. They are never logged and never added to an envelope.

## Data and privacy

The envelope carries the server UUID, session epoch, player UUIDs and entity IDs, the tick range, and the recorded state. It does not carry chat, commands, or inventory contents. Positions and rotations are personal data, so treat the ingest endpoint and its storage accordingly.

HMAC signing proves an upload came from a holder of the secret. It does not encrypt the payload. If the path between the Minecraft server and the ingest service is not trusted, put TLS in front.

The collector does not ship any player data to a third party. It sends data to the endpoint you configure, and nowhere else.

## Status

Schema v1, tested against Paper/Purpur 1.21.11 on Java 21. The collector is usable now. Detector rules, thresholds, and calibration data are not part of this repository.

The plugin is published as `forensic-telemetry` in `plugin.yml`. Builds from this repo use version `0.1.0`.

## License

MIT. See `LICENSE`.

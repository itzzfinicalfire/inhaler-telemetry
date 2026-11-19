# Contributing

This repository is the collector and the wire format. The detector service is private, so changes here should stay inside that boundary.

## What belongs here

- telemetry capture and encoding
- the ring, flush, retry, and gap accounting
- envelope and segment uploads
- config parsing and validation
- protocol documentation
- tests that prove the points above

Detector rules, thresholds, scoring, and calibration data do not belong here. A pull request that adds them will be closed, not because the idea is bad, but because this repository is public and the scoring service is not.

## The report-only rule

The plugin must never reference a mutating Bukkit or Paper method. No kick, ban, teleport, damage, attribute change, inventory change, or event cancellation.

This is checked by `TelemetryPluginArchitectureTest`, which parses the compiled constant pool. If you add a call that trips it, the build fails. Do not weaken the test to make a feature fit. Change the feature.

## Build and test

```sh
./gradlew :plugin:test
```

Use Java 21. The wrapper downloads the right Gradle version.

If you touch the encoder, the record layout, or the signing string, update the known-answer vectors. The Java and cloud implementations must agree byte for byte. A one-character drift produces 401s on every upload.

## Pull requests

Keep them small enough to review in one sitting. Include a test that fails before the change and passes after it. If the change is about performance, include the measurement that motivated it.

Do not paste real server UUIDs, HMAC secrets, player names, or captured sessions into an issue or a pull request. Use the synthetic fixtures in the test tree.

Describe why the change exists. A diff shows what changed. It rarely shows what problem the change solves.

## Style

Match the surrounding code. Comments should explain a decision that is not obvious from the code. Avoid comments that restate the next line.

The wire format is a contract. A field rename, an offset change, or a new flag bit is a schema change. Bump the version and document it in `docs/PROTOCOL.md`.

## License

By contributing, you agree that your contribution is licensed under the MIT license in `LICENSE`.

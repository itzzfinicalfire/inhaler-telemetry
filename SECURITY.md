# Security

## Supported versions

The `0.1.x` line receives fixes. Older builds do not.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository. Do not open a public issue for a security problem.

Include the version, the config shape with secrets removed, and a reproduction that does not contain real player data. If the issue is in the HMAC or envelope parser, say which request path and which header.

I aim to acknowledge a report within a few days. This is a small project, so I cannot promise a fixed timeline for every fix.

## Scope

In scope:

- signature verification and replay handling
- envelope and segment parsing
- path handling for segment keys
- resource exhaustion through the ring, upload queue, or HTTP body
- report-only invariant violations in the compiled jar

Out of scope:

- detector thresholds or scoring decisions, which live in a separate private service
- issues that require a modified server jar
- denial of service from a host that already has root or the server console

## Secrets

If you find a committed secret, report it privately and rotate it. Do not paste the value into the report. Tell me the path and the commit, and I will remove it from history.

The collector reads its secret from `plugins/ForensicTelemetry/config.yml`. That file should be mode 0600 and must never be committed.

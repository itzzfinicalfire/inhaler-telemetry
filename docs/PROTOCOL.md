# Wire format

Two request types reach the ingest service:

- `POST /v1/telemetry` with a JSON envelope and a record stream inside the envelope's segment objects
- `PUT /v1/segments/<key>` with the raw record bytes for one segment

Both use the same HMAC-SHA256 scheme. The method and path are part of the signed string, so a captured envelope cannot be replayed as a segment upload, or the other way around.

## Headers

| Header | Meaning |
|---|---|
| `x-server-id` | Server UUID. Must exist in the ingest service's server table. |
| `x-key-id` | Key identifier, for rotation with overlap. |
| `x-timestamp-ms` | Unix epoch milliseconds. The server accepts plus or minus 30 seconds. |
| `x-nonce` | Base64 nonce. At least 16 random bytes. One per attempt. |
| `x-session-epoch` | UUID for this plugin session. Changes on every server start. |
| `x-schema-version` | Envelope schema version. Currently `1`. |
| `x-signature` | Lowercase hex HMAC-SHA256 of the canonical string below. |

The uploader does not set `content-length` explicitly. The HTTP client derives it from the body, and the signature covers the same byte count.

## Canonical signing string

Ten lines, joined with `\n`, no trailing newline:

```text
METHOD
/path
x-server-id
x-key-id
x-timestamp-ms
x-nonce
x-session-epoch
x-schema-version
content-length
body-sha256-hex
```

`METHOD` is upper case. `content-length` is the decimal byte count of the request body. `body-sha256-hex` is the lowercase hex SHA-256 of the exact bytes sent. The HMAC key is the shared secret, and the digest is lower case hex.

For the envelope:

```text
POST
/v1/telemetry
```

For a segment:

```text
PUT
/v1/segments/seg/<server>/<epoch>/<min>-<max>.zst
```

The `.zst` suffix is part of the object key. The payload is the raw 32-byte record stream, not a zstd frame.

## Envelope JSON

```json
{
  "envelope_id": "uuid",
  "chunk_start_tick": 1200,
  "chunk_count": 1,
  "payload_bytes": 65536,
  "gap_count": 0,
  "tick_min": 1200,
  "tick_max": 1400,
  "blob_key": null,
  "gaps": [],
  "segments": [
    {
      "segment_key": "seg/<server>/<epoch>/1200-1400.zst",
      "tick_start": 1200,
      "tick_end": 1400,
      "schema_version": 1
    }
  ],
  "players": [
    {
      "player_id": "uuid",
      "name": null,
      "entity_id": 33
    }
  ],
  "session": {
    "started_at_ms": 1763480934384,
    "ended_at_ms": null,
    "ended_reason": null,
    "peak_players": null,
    "tick_count": null
  }
}
```

`envelope_id` is the idempotency key. Re-sending the same envelope is safe. `players[].entity_id` is the session-scoped mapping the ingest side uses to resolve combat participants. Entity IDs are not portable across sessions.

`session` is present on the first envelope of a session and on the clean-shutdown envelope. It is `null` otherwise.

## Records

Every slot is 32 bytes.

### State record

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | tick, int32 big-endian |
| 4 | 4 | entity id, int32 big-endian |
| 8 | 12 | x, y, z, float32 big-endian |
| 20 | 4 | yaw, float32 big-endian |
| 24 | 4 | pitch, float32 big-endian |
| 28 | 2 | flags, uint16 big-endian |
| 30 | 2 | ping in ms, uint16 big-endian. `65535` means unknown |

Flag bits:

| Bit | Meaning |
|---:|---|
| 0 | ground |
| 1 | sprint |
| 2 | sneak |
| 3 | fly |
| 4 | swim |
| 5 | glide |
| 6 | climb |
| 7 | riptide |

Positions are full-precision floats. The wire format does not quantize them.

### Control record

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | kind marker, `0x80` to `0xFF` |
| 1 | 3 | reserved, zero |
| 4 | 4 | tick, int32 big-endian |
| 8 | 24 | kind-specific payload |

A state record starts with a non-negative int32 tick, so its first byte is always below `0x80`. Any first byte at or above `0x80` is a control record. That removes the need for a length prefix or a version flag per slot.

## Failure behavior

If an upload fails, the plugin keeps the bytes in the ring and retries with backoff. If the ring fills, it drops the oldest data and records a gap. A gap is data, not silence. The ingest side keeps the gap ranges in the exported corpus so a reviewer can see what was missing.

The envelope signature is checked before JSON parsing. A malformed body from an authenticated client is a client bug. An unauthenticated body never reaches the parser or the database.

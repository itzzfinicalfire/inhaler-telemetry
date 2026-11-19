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
| `x-schema-version` | Envelope schema version. `2` is current. `1` is still accepted. |
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
      "schema_version": 2
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
| 1 | 1 | intra-tick sequence, uint8, writer order. `0` on v1 records means order unknown |
| 2 | 2 | reserved, zero |
| 4 | 4 | tick, int32 big-endian |
| 8 | 24 | kind-specific payload |

A state record starts with a non-negative int32 tick, so its first byte is always below `0x80`. Any first byte at or above `0x80` is a control record. That removes the need for a length prefix or a version flag per slot.

The sequence byte is stamped by the ring at offer time, so it follows the order the server fired events. It wraps at 256. A missing value in the sequence is a dropped record, not a new tick.

## Control kinds

Payload bytes are big-endian. Offsets are from the start of the record. Every payload ends with zeroed bytes to offset 32.

| Marker | Kind | Payload |
|---:|---|---|
| `0x80` | ATTACK | tick@4, attacker@8 int32, victim@12 int32, damage@16 float32 |
| `0x81` | DAMAGE | tick@4, victim@8 int32, damage@12 float32, cause@16 uint8, damager@20 int32 (-1 environmental) |
| `0x82` | DEATH | tick@4, victim@8 int32 |
| `0x83` | SESSION_START | tick@4 |
| `0x84` | SESSION_END | tick@4 |
| `0x85` | GAP | tick@4 seal, from@8 int32, to@12 int32, reason@16 uint8 |
| `0x86` | INTEREST | tick@4, entityA@8, entityB@12, trigger@16 uint8 |
| `0x87` | RESYNC | tick@4, config hash@8 int32 |
| `0x88` | HELD_SLOT | tick@4, entity@8, from slot@12 int8, to slot@13 int8, previous category@14 uint8, new category@15 uint8 |
| `0x89` | PLACE | tick@4, entity@8, block x@12, y@16, z@20 int32, block category@24 uint8, flags@25 uint8 |
| `0x8A` | INTERACT | tick@4, entity@8, block x@12, y@16, z@20 int32, interact kind@24 uint8, item category@25 uint8, flags@26 uint8 |
| `0x8B` | SWING | tick@4, entity@8, hand@12 uint8, flags@13 uint8 |
| `0x8C` | CRYSTAL_SPAWN | tick@4, crystal entity@8, x@12, y@16, z@20 float32, owner@24 int32 (-1 unknown) |
| `0x8D` | CRYSTAL_BREAK | tick@4, crystal entity@8, breaker@12 int32 (-1 unknown), x@16, y@20, z@24 float32 |
| `0x8E` | ANCHOR_EXPLODE | tick@4, x@8, y@12, z@16 float32, trigger@20 int32 (-1 unknown) |
| `0x8F` | TOTEM_POP | tick@4, entity@8 int32 |
| `0x90` | WIND_CHARGE | tick@4, shooter@8, projectile@12 int32 |
| `0x91` | ATTACK_CTX | tick@4, attacker@8, fall distance@12 float32, attack cooldown@16 float32, held category@20 uint8, flags@21 uint8, victim category@22 uint8 |

Gap reasons, in ordinal order: `0` RING_OVERFLOW, `1` SEND_FAILURE, `2` SESSION_START, `3` PLUGIN_DISABLE, `4` QUEUE_DROPPED, `5` INTERNAL_ERROR, `6` EVENT_QUEUE_OVERFLOW.

### Item and block categories

`PLACE.blockCategory` uses the same table. `NONE` never appears in a place record.

| Value | Category |
|---:|---|
| 0 | none (empty hand) |
| 1 | other |
| 2 | sword |
| 3 | axe |
| 4 | mace |
| 5 | end crystal |
| 6 | obsidian |
| 7 | respawn anchor |
| 8 | glowstone |
| 9 | totem of undying |
| 10 | wind charge |

### Flags

| Record | Bit | Meaning |
|---|---:|---|
| PLACE | 0 | the place event was cancelled by another plugin |
| INTERACT | 0 | the interaction was cancelled by another plugin |
| INTERACT | 1 | off hand |
| ATTACK_CTX | 0 | victim is an end crystal |
| ATTACK_CTX | 1 | victim is a player |
| ATTACK_CTX | 2 | victim is another entity |
| ATTACK_CTX | 3 | attacker was sprinting |
| ATTACK_CTX | 4 | attacker was on the ground |
| ATTACK_CTX | 5 | attacker was sneaking |

Victim category in `ATTACK_CTX`: `0` other entity, `1` player, `2` end crystal.

Cancelled events are recorded on purpose. A protection plugin blocking a crystal placement still tells you the client tried to place one.

### Compatibility

- A v2 reader decodes v1 records. The only difference in the fixed fields is the sequence byte, which v1 wrote as zero.
- Unknown control markers are skipped by the reader. Slots are fixed size, so the next record still starts at the next 32-byte boundary. A reader that throws on an unknown marker is wrong.
- The envelope accepts schema version 1 and 2. The segment descriptor names the version that produced its bytes.
- Adding a new kind is not a breaking change. Changing an existing offset, width, or category number is.

### Verification status

The wire format and the byte offsets are covered by Java and TypeScript tests that run the real encoders. Two things cannot be verified without a live server and are marked UNVERIFIED here:

- the exact firing order of `EntityPlaceEvent`, damage, and `EntityExplodeEvent` for an end crystal inside one tick
- the hand used for `PlayerAnimationEvent`. This API version does not expose it, so `SWING.hand` is written as main hand

Both are capture accuracy questions, not protocol questions. The sequence byte preserves whatever order the server actually fired.

## Failure behavior

If an upload fails, the plugin keeps the bytes in the ring and retries with backoff. If the ring fills, it drops the oldest data and records a gap. A gap is data, not silence. The ingest side keeps the gap ranges in the exported corpus so a reviewer can see what was missing.

The envelope signature is checked before JSON parsing. A malformed body from an authenticated client is a client bug. An unauthenticated body never reaches the parser or the database.

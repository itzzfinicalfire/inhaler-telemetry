# Data and privacy

The collector watches players. That makes it a privacy-sensitive component, even though it never talks to the player and never changes the game.

## What leaves the server

Each tracked player produces a state record every tick. The record contains the player UUID, the session entity ID, the tick, position, yaw, pitch, movement flags, and ping.

Combat events add attack, damage, death, and interest records. The v2 mechanic records add hotbar slot changes (with an item category on each side), placements of obsidian, respawn anchors and glowstone, anchor charge and crystal placement attempts (including cancelled attempts), arm swings, crystal spawn/break, anchor explosions, totem pops, wind charge launches, and attack context (fall distance, attack cooldown, held category, victim category, movement flags).

The envelope carries the server UUID, the session epoch, the tick range, the player list, and any gap ranges.

## What does not leave the server

- chat and private messages
- commands and command output
- inventory contents (only the category of a held or placed item, never the stack, name, enchantments or count)
- IP addresses
- account credentials
- client brand or mod list
- anything the server does not already hold in memory

The plugin does not read files outside its own config, does not write telemetry to disk, and does not call a third-party service. It sends to the endpoint in `endpoint.url`.

Item categories are intentionally coarse. The collector maps a held or placed material to one of eleven values (none, other, sword, axe, mace, end crystal, obsidian, respawn anchor, glowstone, totem, wind charge). It never records a display name, lore, enchantment, durability, or stack size.

## Where the data goes

The ingest endpoint is chosen by the server operator. The collector has no default endpoint that phones home. If the endpoint is blank or set to the placeholder, the plugin stays disabled.

Once the data arrives, retention is an ingest-side decision. A reasonable default is 30 days for raw segments and longer for exported review bundles, but that is a policy choice, not a property of this code.

## Identifiers

Player UUIDs are stable account identifiers in online mode. Positions and rotations can reveal play patterns, relationships, and time zone habits. Treat the ingest store with the same care as a server log, or more.

The ingest side resolves entity IDs to UUIDs for combat participants. Entity IDs are session-scoped and meaningless outside the session that produced them.

## Integrity versus confidentiality

HMAC-SHA256 signs every request. The ingest side rejects a body that was modified in transit, and it rejects a request signed with an unknown key. That is integrity and authentication.

The collector does not encrypt the payload. Plain HTTP exposes positions and UUIDs to anyone on the path. If that path is not trusted, terminate TLS at the ingest endpoint.

## Operator checklist

- Use a dedicated ingest host or a private network path.
- Keep `.env` and the plugin config at mode 0600.
- Rotate the HMAC secret if it appears in a screenshot, a chat, or a support ticket.
- Set a retention window before the first capture, not after.
- Restrict access to exported review bundles. They contain the same player data plus reviewer notes.
- Do not commit captured sessions to a public repository.

## Player-facing summary

If you run this on a public server, say what you collect. A short line in your rules is enough: "We record player positions and combat events for anti-cheat review. We do not record chat." That is accurate for this collector.

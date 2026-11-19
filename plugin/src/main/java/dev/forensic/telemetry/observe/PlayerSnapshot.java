package dev.forensic.telemetry.observe;

import java.util.UUID;

/**
 * Reusable per-tick player state carrier.
 *
 * <p>One instance per tracked player, filled in place every tick. The observer owns these; the
 * encoder reads them. Nothing here is ever published to another thread: the uploader drains
 * encoded BYTES from the ring, never these objects, so no synchronization and no defensive
 * copies.
 */
public final class PlayerSnapshot {
    public int tick;
    public int entityId;
    public UUID playerId;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public int flags;
    public int pingMs;
}

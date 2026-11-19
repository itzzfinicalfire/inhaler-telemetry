package dev.forensic.telemetry.record;

/**
 * Authoritative 32-byte record layout, schema v1.
 *
 * <p>Every slot in the ring is exactly {@link dev.forensic.telemetry.TelemetryConfig#RECORD_BYTES}
 * bytes. Fixed size is what makes the ring branchless math (index {@code &} mask) instead of a
 * framing problem, and what lets the uploader slice 64 KiB chunks without parsing.
 *
 * <h2>State record (per player, per tick)</h2>
 *
 * <pre>
 *  0- 3  tick        int32 BE, Bukkit.getCurrentTick(), non-negative
 *  4- 7  entityId    int32 BE, Bukkit entity id (session-scoped, never persisted)
 *  8-11  x           float32 BE, full precision
 * 12-15  y           float32 BE, full precision
 * 16-19  z           float32 BE, full precision
 * 20-23  yaw         float32 BE, degrees
 * 24-27  pitch       float32 BE, degrees
 * 28-29  flags       uint16 BE (bit0 ground, bit1 sprint, bit2 sneak, bit3 fly,
 *                     bit4 swim, bit5 glide, bit6 climb, bit7 riptide, rest 0)
 * 30-31  pingMs      uint16 BE, clamped to 0xFFFF; 0xFFFF also means unknown
 * </pre>
 *
 * <p>Positions are full-precision floats, never quantized. The transport does not get to decide
 * what precision the evidence needs: quantization is an analysis decision (and a forbidden one
 * for detection reasoning), so the wire carries every bit the server had. The 40-tick
 * "resync" config does not mean delta state here; it drives {@link ControlKind#RESYNC}
 * continuity checkpoints.
 *
 * <p>Health is deliberately absent. Per-tick health would cost 4 bytes on every record for a
 * value that changes rarely; health transitions arrive as {@link ControlKind#DAMAGE} and {@link
 * ControlKind#DEATH} control records, which is also where the cause lives. Kill attribution
 * needs cause + victim + tick, never a health time series.
 *
 * <h2>Control record (events, boundaries, gaps)</h2>
 *
 * <pre>
 *  0     kind        0x80-0xFF marker (see {@link ControlKind})
 *  1- 3  reserved    0
 *  4- 7  tick        int32 BE
 *  8-31  kind-specific payload (see ControlKind javadoc per kind)
 * </pre>
 *
 * <h2>Kind discrimination</h2>
 *
 * <p>The marker is the FIRST byte. State records start with a non-negative int32 tick, whose
 * high byte is always 0x00-0x7F (int32 max is 0x7FFFFFFF; ticks are never negative). Any first
 * byte >= 0x80 is therefore unambiguously a control record, with no version flag, no length
 * prefix, and no branch beyond one unsigned comparison. A tick counter would have to run
 * 3.4 continuous years to reach 0x80000000, and it cannot: the counter is Bukkit's
 * per-session tick, reset on every restart under a fresh session_epoch.
 */
public final class RecordLayout {

    public static final int RECORD_BYTES = 32;

    /** Schema version of this layout. Bumped only by an incompatible layout change. */
    public static final int SCHEMA_VERSION = 1;

    // State record offsets.
    public static final int OFF_TICK = 0;
    public static final int OFF_ENTITY_ID = 4;
    public static final int OFF_X = 8;
    public static final int OFF_Y = 12;
    public static final int OFF_Z = 16;
    public static final int OFF_YAW = 20;
    public static final int OFF_PITCH = 24;
    public static final int OFF_FLAGS = 28;
    public static final int OFF_PING = 30;

    // Flag bits.
    public static final int FLAG_GROUND = 1 << 0;
    public static final int FLAG_SPRINT = 1 << 1;
    public static final int FLAG_SNEAK = 1 << 2;
    public static final int FLAG_FLY = 1 << 3;
    public static final int FLAG_SWIM = 1 << 4;
    public static final int FLAG_GLIDE = 1 << 5;
    public static final int FLAG_CLIMB = 1 << 6;
    public static final int FLAG_RIPTIDE = 1 << 7;

    // Control record offsets.
    public static final int OFF_KIND = 0;
    public static final int OFF_CTRL_TICK = 4;

    private RecordLayout() {}
}

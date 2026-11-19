package dev.forensic.telemetry.record;

/**
 * Control record kinds with fixed markers and payload layouts.
 *
 * <p>Priority classes (drop order under ring pressure, highest survives):
 *
 * <ul>
 *   <li>CRITICAL: SESSION_START, SESSION_END, GAP, DEATH. Losing one destroys the
 *       ability to interpret everything around it, so these are never dropped for state.
 *   <li>HIGH: ATTACK, DAMAGE, INTEREST, RESYNC. Combat evidence and continuity checkpoints.
 *   <li>NORMAL: state records only (see {@link RecordLayout}).
 * </ul>
 *
 * <p>Payload layouts (offsets relative to record start; bytes 1-3 always 0):
 *
 * <ul>
 *   <li>ATTACK (0x80, HIGH): tick@4, attackerEntityId@8, victimEntityId@12, damageBits(float)@16.
 *   <li>DAMAGE (0x81, HIGH): tick@4, victimEntityId@8, damageBits(float)@12, causeOrdinal@16,
 *       damagerEntityId@20 (-1 when environmental).
 *   <li>DEATH (0x82, CRITICAL): tick@4, victimEntityId@8.
 *   <li>SESSION_START (0x83, CRITICAL): tick@4. Marks observer (re)start; everything before
 *       this tick in this session_epoch is unknown history, never "no events".
 *   <li>SESSION_END (0x84, CRITICAL): tick@4. Best-effort clean-shutdown marker.
 *   <li>GAP (0x85, CRITICAL): tick@4 (seal tick), fromTick@8, toTick@12, reason@16
 *       (cloud gap_reason ordinal: 0 RING_OVERFLOW, 1 SEND_FAILURE, 2 SESSION_START,
 *       3 PLUGIN_DISABLE, 4 QUEUE_DROPPED, 5 INTERNAL_ERROR, 6 EVENT_QUEUE_OVERFLOW).
 *   <li>INTEREST (0x86, HIGH): tick@4, playerAEntityId@8, playerBEntityId@12, trigger@16
 *       (0 attack, 1 damage, 2 proximity). Lightweight server-side interest window only;
 *       engagement derivation is cloud-side (WP6).
 *   <li>RESYNC (0x87, HIGH): tick@4, configHash@8 (int32). Continuity checkpoint every
 *       {@code encoder.resync-interval-ticks}; lets the cloud verify stream integrity
 *       without trusting any single record.
 * </ul>
 */
public enum ControlKind {
    ATTACK(0x80, Priority.HIGH),
    DAMAGE(0x81, Priority.HIGH),
    DEATH(0x82, Priority.CRITICAL),
    SESSION_START(0x83, Priority.CRITICAL),
    SESSION_END(0x84, Priority.CRITICAL),
    GAP(0x85, Priority.CRITICAL),
    INTEREST(0x86, Priority.HIGH),
    RESYNC(0x87, Priority.HIGH);

    /** Cloud gap_reason ordinals, in cloud enum order. Must match exactly. */
    public static final int GAP_RING_OVERFLOW = 0;
    public static final int GAP_SEND_FAILURE = 1;
    public static final int GAP_SESSION_START = 2;
    public static final int GAP_PLUGIN_DISABLE = 3;
    public static final int GAP_QUEUE_DROPPED = 4;
    public static final int GAP_INTERNAL_ERROR = 5;
    public static final int GAP_EVENT_QUEUE_OVERFLOW = 6;

    public enum Priority {
        NORMAL,
        HIGH,
        CRITICAL
    }

    private final int marker;
    private final Priority priority;

    ControlKind(int marker, Priority priority) {
        this.marker = marker;
        this.priority = priority;
    }

    public int marker() {
        return marker;
    }

    public Priority priority() {
        return priority;
    }

    public static boolean isControlByte(int firstByte) {
        return (firstByte & 0xFF) >= 0x80;
    }

    public static ControlKind fromMarker(int marker) {
        for (ControlKind k : values()) {
            if (k.marker == (marker & 0xFF)) {
                return k;
            }
        }
        throw new IllegalArgumentException("unknown control marker: " + marker);
    }
}

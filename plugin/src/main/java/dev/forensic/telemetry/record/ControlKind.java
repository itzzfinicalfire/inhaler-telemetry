package dev.forensic.telemetry.record;

/**
 * Control record kinds with fixed markers and payload layouts.
 *
 * <p>Priority classes (drop order under ring pressure, highest survives):
 *
 * <ul>
 *   <li>CRITICAL: SESSION_START, SESSION_END, GAP, DEATH, TOTEM_POP. Losing one destroys the
 *       ability to interpret everything around it, so these are never dropped for state.
 *   <li>HIGH: ATTACK, DAMAGE, INTEREST, RESYNC and the combat-mechanic records. Evidence.
 *   <li>NORMAL: state records and SWING (see {@link RecordLayout}). A dropped swing is
 *       recoverable from the surrounding attack; a dropped state tick is recoverable from the
 *       next tick.
 * </ul>
 *
 * <p>Schema v2 adds an intra-tick sequence byte at offset 1. Control records written by a v1
 * encoder left it zero; a v2 reader must treat zero as "ordering unknown" and fall back to
 * content ordering. The byte is stamped by the ring at offer time, so the sequence follows
 * <em>ring write order</em>, which is event order on the main thread. It wraps at 256; more than
 * 256 control records in one tick is already a capture anomaly, not a normal fight.
 *
 * <p>Payload layouts (offsets relative to record start; bytes 0 marker, 1 sequence, 2-3 zero):
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
 *       engagement derivation is cloud-side.
 *   <li>RESYNC (0x87, HIGH): tick@4, configHash@8 (int32). Continuity checkpoint every
 *       {@code encoder.resync-interval-ticks}; lets the cloud verify stream integrity
 *       without trusting any single record.
 *   <li>HELD_SLOT (0x88, HIGH): tick@4, entityId@8, fromSlot@12(int8), toSlot@13(int8),
 *       previousCategory@14(uint8), newCategory@15(uint8). Source: PlayerItemHeldEvent.
 *   <li>PLACE (0x89, HIGH): tick@4, entityId@8, blockX@12, blockY@16, blockZ@20 (int32),
 *       blockCategory@24(uint8, an {@link ItemCategory} ordinal), flags@25(uint8, see
 *       {@link ControlFlags#PLACE_CANCELLED}). Source: BlockPlaceEvent, cancelled events
 *       included on purpose.
 *   <li>INTERACT (0x8A, HIGH): tick@4, entityId@8, blockX@12, blockY@16, blockZ@20 (int32),
 *       interactKind@24(uint8, see {@link InteractKind}), itemCategory@25(uint8),
 *       flags@26(uint8, bit0 cancelled, bit1 off hand). Source: PlayerInteractEvent.
 *   <li>SWING (0x8B, NORMAL): tick@4, entityId@8, hand@12(uint8, 0 main / 1 off).
 *       Source: PlayerAnimationEvent, ARM_SWING only.
 *   <li>CRYSTAL_SPAWN (0x8C, HIGH): tick@4, crystalEntityId@8, x@12(float), y@16, z@20,
 *       ownerEntityId@24 (int32, -1 unknown). Source: EntityPlaceEvent for END_CRYSTAL.
 *   <li>CRYSTAL_BREAK (0x8D, HIGH): tick@4, crystalEntityId@8, breakerEntityId@12 (int32,
 *       -1 unknown), x@16(float), y@20, z@24. Sources: attack/damage on a crystal, and
 *       EntityExplodeEvent with an END_CRYSTAL entity.
 *   <li>ANCHOR_EXPLODE (0x8E, HIGH): tick@4, x@8(float), y@12, z@16,
 *       triggerEntityId@20 (int32, -1 unknown). Source: BlockExplodeEvent whose block is a
 *       respawn anchor.
 *   <li>TOTEM_POP (0x8F, CRITICAL): tick@4, entityId@8. Source: EntityResurrectEvent.
 *   <li>WIND_CHARGE (0x90, HIGH): tick@4, shooterEntityId@8, projectileEntityId@12.
 *       Source: ProjectileLaunchEvent for WIND_CHARGE.
 *   <li>ATTACK_CTX (0x91, HIGH): tick@4, attackerEntityId@8, fallDistanceBits(float)@12,
 *       attackCooldownBits(float)@16, heldCategory@20(uint8), flags@21(uint8, see
 *       {@link ControlFlags}), victimCategory@22(uint8), reserved@23. Emitted next to the
 *       ATTACK whenever the attacker is a player and capture.attack-context is on.
 *       Flag bit 6 marks a vanilla sweep, so multi-victim ticks can be separated from a
 *       multi-target attack.
 * </ul>
 *
 * <p>Unknown markers are skipped by readers, never thrown: the slot size is fixed, so a future
 * kind is forward-compatible by construction.
 */
public enum ControlKind {
    ATTACK(0x80, Priority.HIGH),
    DAMAGE(0x81, Priority.HIGH),
    DEATH(0x82, Priority.CRITICAL),
    SESSION_START(0x83, Priority.CRITICAL),
    SESSION_END(0x84, Priority.CRITICAL),
    GAP(0x85, Priority.CRITICAL),
    INTEREST(0x86, Priority.HIGH),
    RESYNC(0x87, Priority.HIGH),
    HELD_SLOT(0x88, Priority.HIGH),
    PLACE(0x89, Priority.HIGH),
    INTERACT(0x8A, Priority.HIGH),
    SWING(0x8B, Priority.NORMAL),
    CRYSTAL_SPAWN(0x8C, Priority.HIGH),
    CRYSTAL_BREAK(0x8D, Priority.HIGH),
    ANCHOR_EXPLODE(0x8E, Priority.HIGH),
    TOTEM_POP(0x8F, Priority.CRITICAL),
    WIND_CHARGE(0x90, Priority.HIGH),
    ATTACK_CTX(0x91, Priority.HIGH);

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

    /** Strict lookup. Throws on an unknown marker; use {@link #fromMarkerOrNull} in readers. */
    public static ControlKind fromMarker(int marker) {
        ControlKind kind = fromMarkerOrNull(marker);
        if (kind == null) {
            throw new IllegalArgumentException("unknown control marker: " + marker);
        }
        return kind;
    }

    /** Forward-compatible lookup: null for a marker this build does not know. */
    public static ControlKind fromMarkerOrNull(int marker) {
        int m = marker & 0xFF;
        for (ControlKind k : values()) {
            if (k.marker == m) {
                return k;
            }
        }
        return null;
    }
}

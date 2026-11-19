package dev.forensic.telemetry.record;

/**
 * Bit flags for v2 control payloads. Wire format; append only.
 *
 * <p>Cancelled actions are recorded on purpose. A plugin cancel from a protection plugin is
 * still evidence about what the client tried to do, and dropping it would make a blocked
 * crystal macro look like inactivity.
 */
public final class ControlFlags {

    /** PLACE flag: the place event was cancelled by another plugin. */
    public static final int PLACE_CANCELLED = 1 << 0;

    /** INTERACT flag: the interaction was cancelled by another plugin. */
    public static final int INTERACT_CANCELLED = 1 << 0;
    /** INTERACT flag: the interaction used the off hand. */
    public static final int INTERACT_OFF_HAND = 1 << 1;

    /** ATTACK_CTX flag: the victim is an end crystal. */
    public static final int ATTACK_VICTIM_CRYSTAL = 1 << 0;
    /** ATTACK_CTX flag: the victim is a player. */
    public static final int ATTACK_VICTIM_PLAYER = 1 << 1;
    /** ATTACK_CTX flag: the victim is neither a player nor a crystal (mob, anchor, ...). */
    public static final int ATTACK_VICTIM_OTHER = 1 << 2;
    /** ATTACK_CTX flag: the attacker was sprinting at the hit. */
    public static final int ATTACK_ATTACKER_SPRINTING = 1 << 3;
    /** ATTACK_CTX flag: the attacker was on the ground at the hit. */
    public static final int ATTACK_ATTACKER_GROUND = 1 << 4;
    /** ATTACK_CTX flag: the attacker was sneaking at the hit. */
    public static final int ATTACK_ATTACKER_SNEAK = 1 << 5;
    /** ATTACK_CTX flag: the damage cause was a vanilla sweep, so multiple victims are expected. */
    public static final int ATTACK_SWEEP = 1 << 6;

    private ControlFlags() {}
}

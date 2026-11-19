package dev.forensic.telemetry.record;

/**
 * Victim entity category in an ATTACK_CTX record. Wire format; append only.
 *
 * <p>This exists because an attack on an end crystal is already recorded as ATTACK, but the
 * cloud cannot tell the victim was a crystal: entity ids are session-scoped and the envelope
 * only maps player entities. Crystal PvP needs that distinction.
 */
public final class VictimCategory {

    public static final int OTHER = 0;
    public static final int PLAYER = 1;
    public static final int CRYSTAL = 2;

    private VictimCategory() {}
}

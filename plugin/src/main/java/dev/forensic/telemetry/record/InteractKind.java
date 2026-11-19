package dev.forensic.telemetry.record;

/**
 * INTERACT payload kind codes. Wire format; append only.
 *
 * <p>These describe what the client attempted, not what the server allowed. A cancelled
 * anchor charge in a protected area still means the client tried to charge.
 */
public final class InteractKind {

    public static final int ANCHOR_CHARGE = 0;
    public static final int CRYSTAL_PLACE_ATTEMPT = 1;
    public static final int OTHER = 2;

    private InteractKind() {}
}

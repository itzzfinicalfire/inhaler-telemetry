package dev.forensic.telemetry.observe;

/**
 * Per-family capture toggles, resolved once at enable.
 *
 * <p>Families are grouped by the detector they feed, not by Bukkit event type:
 *
 * <ul>
 *   <li>{@code crystal}: end-crystal spawn and break records.
 *   <li>{@code anchor}: respawn-anchor place/charge/explode records.
 *   <li>{@code slotSwap}: hotbar slot change records.
 *   <li>{@code interact}: right-click attempts (anchor charge, crystal place attempt).
 *   <li>{@code swing}: arm swing records.
 *   <li>{@code attackContext}: fall distance, attack cooldown, held category, victim category,
 *       and wind-charge launches. This is the mace/crit family.
 * </ul>
 *
 * <p>Toggles only stop records from being offered. They never change observation of state,
 * attacks, damage, deaths or gaps: disabling a family must not make the capture look quieter
 * than it was.
 */
public record CaptureFlags(
        boolean crystal,
        boolean anchor,
        boolean slotSwap,
        boolean interact,
        boolean swing,
        boolean attackContext) {

    public static CaptureFlags all() {
        return new CaptureFlags(true, true, true, true, true, true);
    }
}

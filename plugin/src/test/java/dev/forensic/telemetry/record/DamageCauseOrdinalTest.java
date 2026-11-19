package dev.forensic.telemetry.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the damage-cause ordinals carried on the wire to the cloud's
 * {@code reconstruct/damage-cause.ts} map. If Paper reorders the enum, this fails before
 * the cloud silently misreads FALL as something else.
 */
class DamageCauseOrdinalTest {

    @Test
    @DisplayName("cloud damage-cause constants match the Paper enum")
    void ordinalsMatchCloudConstants() {
        assertEquals(3, DamageCause.ENTITY_ATTACK.ordinal());
        assertEquals(4, DamageCause.ENTITY_SWEEP_ATTACK.ordinal());
        assertEquals(7, DamageCause.FALL.ordinal());
        assertEquals(14, DamageCause.ENTITY_EXPLOSION.ordinal());
    }
}

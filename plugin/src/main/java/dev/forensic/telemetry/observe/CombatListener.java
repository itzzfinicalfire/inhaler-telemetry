package dev.forensic.telemetry.observe;

import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.ring.TelemetryRing;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Combat and presence listener: MONITOR only, read-only, main thread.
 *
 * <p>Every handler observes and encodes; none cancels, none modifies, none queries further
 * state (no location reads here — positions come from the 20 Hz snapshots, which keeps the
 * event path O(1) and allocation-free). The tick is {@link Bukkit#getCurrentTick()}, the same
 * clock the observer task uses, so events and snapshots share one timeline with no skew to
 * reconcile cloud-side.
 *
 * <p>Interest windows: an attack or damage event involving at least one player emits an
 * INTEREST control for the pair. These are lightweight server-side hints only; engagement
 * derivation stays cloud-side (WP6). Non-player pairs (villager vs zombie) are ignored:
 * forensic scope is PvP.
 */
public final class CombatListener implements Listener {

    private final TelemetryRing ring;
    private final TrackedPlayers tracked;

    public CombatListener(TelemetryRing ring, TrackedPlayers tracked) {
        this.ring = ring;
        this.tracked = tracked;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) && !(event.getDamager() instanceof Player)) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();
        float damage = (float) event.getDamage();
        int causeOrdinal = event.getCause().ordinal();

        ring.offerAttack(tick, damager.getEntityId(), victim.getEntityId(), damage);

        if (victim instanceof Player) {
            int damagerId = damager instanceof Player ? damager.getEntityId() : -1;
            ring.offerDamage(tick, victim.getEntityId(), damage, causeOrdinal, damagerId);
        }

        if (victim instanceof Player && damager instanceof Player && !victim.equals(damager)) {
            ring.offerInterest(tick, damager.getEntityId(), victim.getEntityId(),
                    InterestTrigger.ATTACK);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        ring.offerDeath(Bukkit.getCurrentTick(), victim.getEntityId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        tracked.add(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracked.remove(event.getPlayer());
    }

    /** INTEREST trigger codes. Must match ControlKind javadoc. */
    public static final class InterestTrigger {
        public static final int ATTACK = 0;
        public static final int DAMAGE = 1;
        public static final int PROXIMITY = 2;

        private InterestTrigger() {}
    }
}

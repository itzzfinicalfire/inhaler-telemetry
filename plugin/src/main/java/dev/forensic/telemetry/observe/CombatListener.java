package dev.forensic.telemetry.observe;

import dev.forensic.telemetry.record.ControlFlags;
import dev.forensic.telemetry.record.InteractKind;
import dev.forensic.telemetry.record.ItemCategory;
import dev.forensic.telemetry.record.VictimCategory;
import dev.forensic.telemetry.ring.TelemetryRing;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Combat and presence listener: MONITOR only, read-only, main thread.
 *
 * <p>Every handler observes and encodes; none cancels, none modifies. Positions come from the
 * 20 Hz snapshots except for events whose whole point is a location (placed block, spawned
 * crystal, explosion). Those read primitives ({@code getX()/getY()/getZ()}) instead of
 * allocating a {@code Location}.
 *
 * <p>Cancelled actions are recorded. A protection plugin cancelling a crystal placement is
 * still evidence that the client attempted it; dropping the attempt would make a blocked macro
 * look like inactivity. The payload carries a cancelled flag so a reviewer can tell the
 * difference.
 *
 * <p>Ordering: all offers happen in handler order on the main thread, and the ring stamps an
 * intra-tick sequence at offer time. Place, break and swap inside one tick therefore survive to
 * the cloud in the order the server fired them. The one ordering the collector cannot see is
 * ordering <em>within</em> the client's packet burst before the server processes it; that is a
 * documented tick-resolution limitation, not an assumption.
 *
 * <p>Not captured here, deliberately: chat, commands, inventory contents beyond a category,
 * and any player state not needed by a detector.
 */
public final class CombatListener implements Listener {

    private final TelemetryRing ring;
    private final TrackedPlayers tracked;
    private final CaptureFlags flags;
    private final CooldownReader cooldown;

    /**
     * Attack cooldown source. Split out because MockBukkit does not implement the getter and
     * aborts the test rather than returning a value. Production always uses the Paper getter.
     */
    @FunctionalInterface
    public interface CooldownReader {
        float read(Player player);
    }

    public CombatListener(TelemetryRing ring, TrackedPlayers tracked) {
        this(ring, tracked, CaptureFlags.all(), Player::getAttackCooldown);
    }

    public CombatListener(TelemetryRing ring, TrackedPlayers tracked, CaptureFlags flags) {
        this(ring, tracked, flags, Player::getAttackCooldown);
    }

    public CombatListener(
            TelemetryRing ring, TrackedPlayers tracked, CaptureFlags flags,
            CooldownReader cooldown) {
        this.ring = ring;
        this.tracked = tracked;
        this.flags = flags;
        this.cooldown = cooldown;
    }

    // ── combat ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();
        if (!(victim instanceof Player) && !(damager instanceof Player)) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        float damage = (float) event.getDamage();
        int causeOrdinal = event.getCause().ordinal();

        ring.offerAttack(tick, damager.getEntityId(), victim.getEntityId(), damage);

        if (victim instanceof Player) {
            int damagerId = damager instanceof Player ? damager.getEntityId() : -1;
            ring.offerDamage(tick, victim.getEntityId(), damage, causeOrdinal, damagerId);
        }

        if (damager instanceof Player attacker) {
            if (flags.attackContext()) {
                int victimCategory;
                int victimFlags;
                if (victim instanceof Player) {
                    victimCategory = VictimCategory.PLAYER;
                    victimFlags = ControlFlags.ATTACK_VICTIM_PLAYER;
                } else if (victim instanceof EnderCrystal) {
                    victimCategory = VictimCategory.CRYSTAL;
                    victimFlags = ControlFlags.ATTACK_VICTIM_CRYSTAL;
                } else {
                    victimCategory = VictimCategory.OTHER;
                    victimFlags = ControlFlags.ATTACK_VICTIM_OTHER;
                }
                int attackFlags = victimFlags;
                if (attacker.isSprinting()) {
                    attackFlags |= ControlFlags.ATTACK_ATTACKER_SPRINTING;
                }
                if (attacker.isOnGround()) {
                    attackFlags |= ControlFlags.ATTACK_ATTACKER_GROUND;
                }
                if (attacker.isSneaking()) {
                    attackFlags |= ControlFlags.ATTACK_ATTACKER_SNEAK;
                }
                if (event.getCause()
                        == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                    attackFlags |= ControlFlags.ATTACK_SWEEP;
                }
                int heldCategory = ItemCategory.of(attacker.getInventory().getItemInMainHand());
                ring.offerAttackCtx(tick, attacker.getEntityId(), attacker.getFallDistance(),
                        cooldown.read(attacker), heldCategory, attackFlags, victimCategory);
            }
            if (flags.crystal() && victim instanceof EnderCrystal) {
                ring.offerCrystalBreak(tick, victim.getEntityId(), attacker.getEntityId(),
                        (float) victim.getX(), (float) victim.getY(), (float) victim.getZ());
            }
        }

        if (victim instanceof Player && damager instanceof Player && !victim.equals(damager)) {
            ring.offerInterest(tick, damager.getEntityId(), victim.getEntityId(),
                    InterestTrigger.ATTACK);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        ring.offerDeath(Bukkit.getCurrentTick(), event.getEntity().getEntityId());
    }

    // ── items and blocks ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldSlot(PlayerItemHeldEvent event) {
        if (!flags.slotSwap()) {
            return;
        }
        Player player = event.getPlayer();
        int previous = ItemCategory.of(player.getInventory().getItem(event.getPreviousSlot()));
        int next = ItemCategory.of(player.getInventory().getItem(event.getNewSlot()));
        ring.offerHeldSlot(Bukkit.getCurrentTick(), player.getEntityId(),
                event.getPreviousSlot(), event.getNewSlot(), previous, next);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (!flags.anchor()) {
            return;
        }
        Block block = event.getBlockPlaced();
        int category = ItemCategory.of(event.getItemInHand());
        // Only the three blocks that make up a respawn-anchor or crystal setup. Recording
        // every dirt placement would be volume without evidence.
        if (category != ItemCategory.OBSIDIAN
                && category != ItemCategory.RESPAWN_ANCHOR
                && category != ItemCategory.GLOWSTONE) {
            return;
        }
        int placeFlags = event.isCancelled() ? ControlFlags.PLACE_CANCELLED : 0;
        ring.offerPlace(Bukkit.getCurrentTick(), event.getPlayer().getEntityId(),
                block.getX(), block.getY(), block.getZ(), category, placeFlags);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!flags.interact() || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        ItemStack item = event.getItem();
        int itemCategory = ItemCategory.of(item);
        int kind;
        if (clicked.getType() == Material.RESPAWN_ANCHOR
                && itemCategory == ItemCategory.GLOWSTONE) {
            if (!flags.anchor()) {
                return;
            }
            kind = InteractKind.ANCHOR_CHARGE;
        } else if (itemCategory == ItemCategory.END_CRYSTAL) {
            if (!flags.crystal()) {
                return;
            }
            kind = InteractKind.CRYSTAL_PLACE_ATTEMPT;
        } else {
            return;
        }
        int interactFlags = 0;
        // isCancelled() is deprecated on this event; the two use-results are the modern
        // representation. Check all three so an older plugin that only calls setCancelled
        // still lands in the cancelled bucket.
        if (event.isCancelled()
                || event.useInteractedBlock() == Event.Result.DENY
                || event.useItemInHand() == Event.Result.DENY) {
            interactFlags |= ControlFlags.INTERACT_CANCELLED;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            interactFlags |= ControlFlags.INTERACT_OFF_HAND;
        }
        ring.offerInteract(Bukkit.getCurrentTick(), event.getPlayer().getEntityId(),
                clicked.getX(), clicked.getY(), clicked.getZ(), kind, itemCategory,
                interactFlags);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        // Hand is not exposed by PlayerAnimationEvent on this API version. The field is
        // written as main-hand and marked UNVERIFIED in PROTOCOL.md rather than guessed.
        if (!flags.swing() || event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        ring.offerSwing(Bukkit.getCurrentTick(), event.getPlayer().getEntityId(), 0, 0);
    }

    // ── crystals, anchors, totems, projectiles ──────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!flags.crystal() || !(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        Player player = event.getPlayer();
        int owner = player == null ? -1 : player.getEntityId();
        ring.offerCrystalSpawn(Bukkit.getCurrentTick(), crystal.getEntityId(),
                (float) crystal.getX(), (float) crystal.getY(), (float) crystal.getZ(), owner);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // A crystal removed by its own explosion never had a damage event with a player
        // breaker; the spawn record plus this break record still close the cycle.
        if (!flags.crystal() || !(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        ring.offerCrystalBreak(Bukkit.getCurrentTick(), crystal.getEntityId(), -1,
                (float) crystal.getX(), (float) crystal.getY(), (float) crystal.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!flags.anchor() || event.getBlock().getType() != Material.RESPAWN_ANCHOR) {
            return;
        }
        Block block = event.getBlock();
        ring.offerAnchorExplode(Bukkit.getCurrentTick(),
                block.getX() + 0.5f, block.getY() + 0.5f, block.getZ() + 0.5f, -1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }
        ring.offerTotemPop(Bukkit.getCurrentTick(), player.getEntityId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!flags.attackContext() || !(event.getEntity() instanceof WindCharge charge)) {
            return;
        }
        if (!(charge.getShooter() instanceof Player shooter)) {
            return;
        }
        ring.offerWindCharge(Bukkit.getCurrentTick(), shooter.getEntityId(),
                charge.getEntityId());
    }

    // ── presence ────────────────────────────────────────────────────────────

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

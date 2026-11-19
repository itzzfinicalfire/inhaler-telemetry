package dev.forensic.telemetry.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.record.ControlFlags;
import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.InteractKind;
import dev.forensic.telemetry.record.ItemCategory;
import dev.forensic.telemetry.record.VictimCategory;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.EnderCrystalMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WindChargeMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;

/**
 * Listener behavior against a real (mocked) server: event → control records, presence
 * tracking, intra-tick ordering, and the report-only property.
 *
 * <p>MockBukkit proves handler wiring and byte layout. It does NOT prove Paper's event firing
 * order for end-crystal placement, respawn-anchor explosions, or the exact hand used for a
 * swing. Those are marked UNVERIFIED in docs/PROTOCOL.md and need a live server.
 */
class CombatListenerTest {

    private ServerMock server;
    private TelemetryRing ring;
    private TrackedPlayers tracked;
    private CombatListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        ring = new TelemetryRing(128 * 32, new GapTracker());
        tracked = new TrackedPlayers();
        listener = new CombatListener(ring, tracked, CaptureFlags.all(), p -> 1.0f);
        server.getPluginManager().registerEvents(listener, MockBukkit.createMockPlugin());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Drains every queued record as an independent 32-byte buffer. */
    private List<ByteBuffer> drainRecords() {
        ByteBuffer dst = ByteBuffer.allocate(32 * 256);
        int n = ring.drainTo(dst, 256);
        List<ByteBuffer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ByteBuffer copy = ByteBuffer.allocate(32);
            for (int k = 0; k < 32; k++) {
                copy.put(k, dst.get(i * 32 + k));
            }
            out.add(copy);
        }
        return out;
    }

    private static List<ControlKind> kinds(List<ByteBuffer> records) {
        List<ControlKind> kinds = new ArrayList<>();
        for (ByteBuffer r : records) {
            if (ControlKind.isControlByte(r.get(0))) {
                ControlKind k = ControlKind.fromMarkerOrNull(r.get(0));
                if (k != null) kinds.add(k);
            }
        }
        return kinds;
    }

    private static ByteBuffer first(List<ByteBuffer> records, ControlKind kind) {
        for (ByteBuffer r : records) {
            if ((r.get(0) & 0xFF) == kind.marker()) {
                return r;
            }
        }
        return null;
    }

    private void fireDamage(Entity attacker, Entity victim, double amount) {
        server.getPluginManager().callEvent(new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, amount));
    }

    @Test
    @DisplayName("player-vs-player damage emits ATTACK, DAMAGE, ATTACK_CTX, INTEREST in order")
    void pvpDamageOrder() {
        PlayerMock attacker = server.addPlayer("Attacker");
        PlayerMock victim = server.addPlayer("Victim");
        tracked.add(attacker);
        tracked.add(victim);
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.MACE));

        fireDamage(attacker, victim, 6.0);

        List<ByteBuffer> records = drainRecords();
        assertEquals(
                List.of(ControlKind.ATTACK, ControlKind.DAMAGE, ControlKind.ATTACK_CTX,
                        ControlKind.INTEREST),
                kinds(records));
        // Sequence is writer order, so place/break/swap ordering survives same-tick sorting.
        for (int i = 0; i < 4; i++) {
            assertEquals(i, records.get(i).get(1) & 0xFF, "sequence at index " + i);
        }
        ByteBuffer ctx = first(records, ControlKind.ATTACK_CTX);
        assertNotNull(ctx);
        assertEquals(ItemCategory.MACE, ctx.get(20) & 0xFF);
        assertEquals(VictimCategory.PLAYER, ctx.get(22) & 0xFF);
        assertTrue((ctx.get(21) & ControlFlags.ATTACK_VICTIM_PLAYER) != 0);
    }

    @Test
    @DisplayName("mob damage to a player emits ATTACK+DAMAGE with damager -1, no ATTACK_CTX")
    void mobDamage() {
        PlayerMock victim = server.addPlayer("Victim");
        tracked.add(victim);
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());

        fireDamage(zombie, victim, 4.0);

        List<ControlKind> kinds = kinds(drainRecords());
        assertTrue(kinds.contains(ControlKind.ATTACK), "missing ATTACK in " + kinds);
        assertTrue(kinds.contains(ControlKind.DAMAGE), "missing DAMAGE in " + kinds);
        assertTrue(!kinds.contains(ControlKind.ATTACK_CTX), "unexpected ATTACK_CTX in " + kinds);
        assertTrue(!kinds.contains(ControlKind.INTEREST), "unexpected INTEREST in " + kinds);
    }

    @Test
    @DisplayName("non-player damager id encodes as -1 in the DAMAGE record")
    void damagerIdMinusOne() {
        PlayerMock victim = server.addPlayer("Victim");
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        fireDamage(zombie, victim, 4.0);

        List<ByteBuffer> records = drainRecords();
        ByteBuffer damage = first(records, ControlKind.DAMAGE);
        assertNotNull(damage);
        assertEquals(-1, damage.getInt(20));
    }

    @Test
    @DisplayName("non-player combat is ignored (forensic scope is PvP)")
    void nonPlayerIgnored() {
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        ZombieMock skeleton = new ZombieMock(server, UUID.randomUUID());
        fireDamage(zombie, skeleton, 3.0);
        assertEquals(0, ring.size());
    }

    @Test
    @DisplayName("player death emits CRITICAL DEATH")
    void death() {
        PlayerMock victim = server.addPlayer("Victim");
        tracked.add(victim);
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK).build();
        server.getPluginManager().callEvent(
                new PlayerDeathEvent(victim, source, new ArrayList<>(), 0, "died"));
        assertTrue(kinds(drainRecords()).contains(ControlKind.DEATH));
    }

    @Test
    @DisplayName("held-slot change records both categories")
    void heldSlot() {
        PlayerMock player = server.addPlayer("Swapper");
        player.getInventory().setItem(4, new ItemStack(Material.MACE));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));

        server.getPluginManager().callEvent(new PlayerItemHeldEvent(player, 0, 4));

        ByteBuffer slot = first(drainRecords(), ControlKind.HELD_SLOT);
        assertNotNull(slot);
        assertEquals(player.getEntityId(), slot.getInt(8));
        assertEquals(0, slot.get(12));
        assertEquals(4, slot.get(13));
        assertEquals(ItemCategory.SWORD, slot.get(14) & 0xFF);
        assertEquals(ItemCategory.MACE, slot.get(15) & 0xFF);
    }

    @Test
    @DisplayName("obsidian place is recorded, dirt is not, cancelled place keeps its flag")
    void placeFiltering() {
        PlayerMock player = server.addPlayer("Builder");
        Block block = player.getWorld().getBlockAt(10, 64, 10);

        block.setType(Material.OBSIDIAN);
        server.getPluginManager().callEvent(new BlockPlaceEvent(
                block, block.getState(), block, new ItemStack(Material.OBSIDIAN),
                player, true, EquipmentSlot.HAND));
        ByteBuffer place = first(drainRecords(), ControlKind.PLACE);
        assertNotNull(place);
        assertEquals(10, place.getInt(12));
        assertEquals(64, place.getInt(16));
        assertEquals(10, place.getInt(20));
        assertEquals(ItemCategory.OBSIDIAN, place.get(24) & 0xFF);
        assertEquals(0, place.get(25) & 0xFF);

        block.setType(Material.RESPAWN_ANCHOR);
        BlockPlaceEvent cancelled = new BlockPlaceEvent(
                block, block.getState(), block, new ItemStack(Material.RESPAWN_ANCHOR),
                player, true, EquipmentSlot.HAND);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        place = first(drainRecords(), ControlKind.PLACE);
        assertNotNull(place);
        assertEquals(ItemCategory.RESPAWN_ANCHOR, place.get(24) & 0xFF);
        assertEquals(ControlFlags.PLACE_CANCELLED, place.get(25) & 0xFF);

        Block dirt = player.getWorld().getBlockAt(11, 64, 11);
        dirt.setType(Material.DIRT);
        server.getPluginManager().callEvent(new BlockPlaceEvent(
                dirt, dirt.getState(), dirt, new ItemStack(Material.DIRT),
                player, true, EquipmentSlot.HAND));
        assertEquals(null, first(drainRecords(), ControlKind.PLACE),
                "ordinary blocks must not be recorded");
    }

    @Test
    @DisplayName("anchor charge and crystal place attempt are distinct interactions")
    void interactions() {
        PlayerMock player = server.addPlayer("Clicker");
        Block anchor = player.getWorld().getBlockAt(3, 64, 3);
        anchor.setType(Material.RESPAWN_ANCHOR);

        server.getPluginManager().callEvent(new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.GLOWSTONE),
                anchor, BlockFace.UP, EquipmentSlot.HAND));
        ByteBuffer interact = first(drainRecords(), ControlKind.INTERACT);
        assertNotNull(interact);
        assertEquals(InteractKind.ANCHOR_CHARGE, interact.get(24) & 0xFF);
        assertEquals(ItemCategory.GLOWSTONE, interact.get(25) & 0xFF);

        Block ground = player.getWorld().getBlockAt(4, 64, 4);
        PlayerInteractEvent cancel = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.END_CRYSTAL),
                ground, BlockFace.UP, EquipmentSlot.OFF_HAND);
        cancel.setCancelled(true);
        server.getPluginManager().callEvent(cancel);
        interact = first(drainRecords(), ControlKind.INTERACT);
        assertNotNull(interact);
        assertEquals(InteractKind.CRYSTAL_PLACE_ATTEMPT, interact.get(24) & 0xFF);
        assertEquals(ItemCategory.END_CRYSTAL, interact.get(25) & 0xFF);
        assertEquals(ControlFlags.INTERACT_CANCELLED | ControlFlags.INTERACT_OFF_HAND,
                interact.get(26) & 0xFF);
    }

    @Test
    @DisplayName("crystal spawn, damage break and explosion break all carry the crystal id")
    void crystalLifecycle() {
        PlayerMock player = server.addPlayer("Crafter");
        EnderCrystalMock crystal = new EnderCrystalMock(server, UUID.randomUUID());
        crystal.teleport(new Location(player.getWorld(), 10.5, 64.0, -3.25));
        Block base = player.getWorld().getBlockAt(10, 63, -4);

        server.getPluginManager().callEvent(
                new EntityPlaceEvent(crystal, player, base, BlockFace.UP, EquipmentSlot.HAND));
        ByteBuffer spawn = first(drainRecords(), ControlKind.CRYSTAL_SPAWN);
        assertNotNull(spawn);
        assertEquals(crystal.getEntityId(), spawn.getInt(8));
        assertEquals(player.getEntityId(), spawn.getInt(24));
        assertEquals(10.5f, spawn.getFloat(12));

        fireDamage(player, crystal, 6.0);
        List<ByteBuffer> hit = drainRecords();
        ByteBuffer brk = first(hit, ControlKind.CRYSTAL_BREAK);
        assertNotNull(brk);
        assertEquals(crystal.getEntityId(), brk.getInt(8));
        assertEquals(player.getEntityId(), brk.getInt(12));
        ByteBuffer ctx = first(hit, ControlKind.ATTACK_CTX);
        assertNotNull(ctx);
        assertEquals(VictimCategory.CRYSTAL, ctx.get(22) & 0xFF);
        assertTrue((ctx.get(21) & ControlFlags.ATTACK_VICTIM_CRYSTAL) != 0);

        server.getPluginManager().callEvent(new EntityExplodeEvent(
                crystal, crystal.getLocation(), new ArrayList<>(), 6.0f,
                ExplosionResult.DESTROY));
        brk = first(drainRecords(), ControlKind.CRYSTAL_BREAK);
        assertNotNull(brk);
        assertEquals(-1, brk.getInt(12), "explosion breaker is unknown, not invented");
    }

    @Test
    @DisplayName("respawn anchor explosion is recorded with block-centered coordinates")
    void anchorExplosion() {
        PlayerMock player = server.addPlayer("Anchor");
        Block anchor = player.getWorld().getBlockAt(7, 64, -9);
        anchor.setType(Material.RESPAWN_ANCHOR);
        server.getPluginManager().callEvent(new BlockExplodeEvent(
                anchor, anchor.getState(), new ArrayList<>(), 5.0f, ExplosionResult.DESTROY));
        ByteBuffer boom = first(drainRecords(), ControlKind.ANCHOR_EXPLODE);
        assertNotNull(boom);
        assertEquals(7.5f, boom.getFloat(8));
        assertEquals(64.5f, boom.getFloat(12));
        assertEquals(-8.5f, boom.getFloat(16));
        assertEquals(-1, boom.getInt(20));
    }

    @Test
    @DisplayName("totem pop and wind charge launches are captured")
    void totemAndWindCharge() {
        PlayerMock player = server.addPlayer("Survivor");
        server.getPluginManager().callEvent(new EntityResurrectEvent(player, EquipmentSlot.HAND));
        ByteBuffer totem = first(drainRecords(), ControlKind.TOTEM_POP);
        assertNotNull(totem);
        assertEquals(player.getEntityId(), totem.getInt(8));

        WindChargeMock charge = new WindChargeMock(server, UUID.randomUUID());
        charge.setShooter(player);
        server.getPluginManager().callEvent(new ProjectileLaunchEvent(charge));
        ByteBuffer wind = first(drainRecords(), ControlKind.WIND_CHARGE);
        assertNotNull(wind);
        assertEquals(player.getEntityId(), wind.getInt(8));
        assertEquals(charge.getEntityId(), wind.getInt(12));
    }

    @Test
    @DisplayName("arm swing is recorded")
    void swing() {
        PlayerMock player = server.addPlayer("Swinger");
        server.getPluginManager().callEvent(
                new PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING));
        ByteBuffer swing = first(drainRecords(), ControlKind.SWING);
        assertNotNull(swing);
        assertEquals(player.getEntityId(), swing.getInt(8));
    }

    @Test
    @DisplayName("capture toggles suppress only their own family")
    void captureToggles() {
        // A second ring with an all-off listener: the all-on listener from setUp keeps
        // writing to the original ring, so this ring only sees the off listener's output.
        TelemetryRing quietRing = new TelemetryRing(128 * 32, new GapTracker());
        CombatListener off = new CombatListener(quietRing, tracked,
                new CaptureFlags(false, false, false, false, false, false), p -> 1.0f);
        server.getPluginManager().registerEvents(off, MockBukkit.createMockPlugin());

        PlayerMock attacker = server.addPlayer("A");
        PlayerMock victim = server.addPlayer("V");
        fireDamage(attacker, victim, 6.0);
        PlayerMock swapper = server.addPlayer("S");
        server.getPluginManager().callEvent(new PlayerItemHeldEvent(swapper, 0, 1));
        server.getPluginManager().callEvent(new EntityResurrectEvent(swapper, EquipmentSlot.HAND));

        ByteBuffer dst = ByteBuffer.allocate(32 * 256);
        int n = quietRing.drainTo(dst, 256);
        List<ControlKind> kinds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (ControlKind.isControlByte(dst.get(i * 32))) {
                ControlKind k = ControlKind.fromMarkerOrNull(dst.get(i * 32));
                if (k != null) kinds.add(k);
            }
        }
        assertTrue(kinds.contains(ControlKind.ATTACK), "core combat must stay on");
        assertTrue(kinds.contains(ControlKind.DAMAGE), "core combat must stay on");
        assertTrue(kinds.contains(ControlKind.TOTEM_POP), "totem is core, not a family");
        assertTrue(!kinds.contains(ControlKind.ATTACK_CTX));
        assertTrue(!kinds.contains(ControlKind.HELD_SLOT));
    }

    @Test
    @DisplayName("join and quit maintain the tracked set")
    void presence() {
        PlayerMock player = server.addPlayer("Joiner");
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, "joined"));
        assertEquals(1, tracked.size());

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, "quit"));
        assertEquals(0, tracked.size());
    }

    @Test
    @DisplayName("report-only: damage event is not cancelled and health untouched")
    void reportOnly() {
        PlayerMock attacker = server.addPlayer("Attacker");
        PlayerMock victim = server.addPlayer("Victim");
        double before = victim.getHealth();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 6.0);
        server.getPluginManager().callEvent(event);
        assertEquals(false, event.isCancelled());
        assertEquals(before, victim.getHealth(), "listener must not change health");
    }

    @Test
    @DisplayName("PaperPlayerSource reads a live mock player without allocation hooks")
    void paperSourceReads() {
        PlayerMock player = server.addPlayer("Reader");
        player.teleport(new Location(player.getWorld(), 10.5, 65.0, -3.25, 45f, 10f));
        PlayerSnapshot snap = new PlayerSnapshot();
        PaperPlayerSource source = new PaperPlayerSource();
        assertTrue(source.readInto(player, 99, snap));
        assertEquals(99, snap.tick);
        assertEquals(player.getEntityId(), snap.entityId);
        assertEquals(10.5, snap.x, 1e-9);
        assertEquals(65.0, snap.y, 1e-9);
        assertEquals(-3.25, snap.z, 1e-9);
        assertEquals(45f, snap.yaw, 1e-6f);
        assertEquals(10f, snap.pitch, 1e-6f);
        assertTrue(snap.pingMs >= 0, "ping must be a sane value");
    }
}

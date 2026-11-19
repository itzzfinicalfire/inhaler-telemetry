package dev.forensic.telemetry.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Listener behavior against a real (mocked) server: event → control records, presence
 * tracking, and the report-only property (no event cancelled, no state mutated).
 */
class CombatListenerTest {

    private ServerMock server;
    private TelemetryRing ring;
    private TrackedPlayers tracked;
    private CombatListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        ring = new TelemetryRing(64 * 32, new GapTracker());
        tracked = new TrackedPlayers();
        listener = new CombatListener(ring, tracked);
        server.getPluginManager().registerEvents(listener,
                MockBukkit.createMockPlugin());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private List<ControlKind> drainKinds() {
        ByteBuffer dst = ByteBuffer.allocate(32 * 128);
        int n = ring.drainTo(dst, 128);
        dst.flip();
        List<ControlKind> kinds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int off = i * 32;
            if (ControlKind.isControlByte(dst.get(off))) {
                kinds.add(ControlKind.fromMarker(dst.get(off)));
            }
        }
        return kinds;
    }

    @Test
    @DisplayName("player-vs-player damage emits ATTACK, DAMAGE, and INTEREST")
    void pvpDamage() {
        PlayerMock attacker = server.addPlayer("Attacker");
        PlayerMock victim = server.addPlayer("Victim");
        tracked.add(attacker);
        tracked.add(victim);

        server.getPluginManager().callEvent(
                new EntityDamageByEntityEvent(attacker, victim,
                        org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                        6.0));

        List<ControlKind> kinds = drainKinds();
        assertTrue(kinds.contains(ControlKind.ATTACK), "missing ATTACK in " + kinds);
        assertTrue(kinds.contains(ControlKind.DAMAGE), "missing DAMAGE in " + kinds);
        assertTrue(kinds.contains(ControlKind.INTEREST), "missing INTEREST in " + kinds);
    }

    @Test
    @DisplayName("mob damage to a player emits ATTACK+DAMAGE with damager -1, no INTEREST")
    void mobDamage() {
        PlayerMock victim = server.addPlayer("Victim");
        tracked.add(victim);
        org.bukkit.entity.Entity zombie =
                new org.mockbukkit.mockbukkit.entity.ZombieMock(server,
                        java.util.UUID.randomUUID());

        server.getPluginManager().callEvent(
                new EntityDamageByEntityEvent(zombie, victim,
                        org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                        4.0));

        List<ControlKind> kinds = drainKinds();
        assertTrue(kinds.contains(ControlKind.ATTACK), "missing ATTACK in " + kinds);
        assertTrue(kinds.contains(ControlKind.DAMAGE), "missing DAMAGE in " + kinds);
        assertTrue(!kinds.contains(ControlKind.INTEREST), "unexpected INTEREST in " + kinds);
    }

    @Test
    @DisplayName("non-player damager id encodes as -1 in the DAMAGE record")
    void damagerIdMinusOne() {
        PlayerMock victim = server.addPlayer("Victim");
        org.bukkit.entity.Entity zombie =
                new org.mockbukkit.mockbukkit.entity.ZombieMock(server,
                        java.util.UUID.randomUUID());
        server.getPluginManager().callEvent(
                new EntityDamageByEntityEvent(zombie, victim,
                        org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                        4.0));

        // Drain to the DAMAGE record (ATTACK lands first) and check offset 20.
        ByteBuffer dst = ByteBuffer.allocate(64);
        ring.drainTo(dst, 2);
        dst.flip();
        assertEquals(ControlKind.ATTACK, ControlKind.fromMarker(dst.get(0)));
        assertEquals(ControlKind.DAMAGE, ControlKind.fromMarker(dst.get(32)));
        assertEquals(-1, dst.getInt(32 + 20));
    }

    @Test
    @DisplayName("non-player combat is ignored (forensic scope is PvP)")
    void nonPlayerIgnored() {
        org.bukkit.entity.Entity zombie =
                new org.mockbukkit.mockbukkit.entity.ZombieMock(server,
                        java.util.UUID.randomUUID());
        org.bukkit.entity.Entity skeleton =
                new org.mockbukkit.mockbukkit.entity.ZombieMock(server,
                        java.util.UUID.randomUUID());
        server.getPluginManager().callEvent(
                new EntityDamageByEntityEvent(zombie, skeleton,
                        org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                        3.0));
        assertEquals(0, ring.size());
    }

    @Test
    @DisplayName("player death emits CRITICAL DEATH")
    void death() {
        PlayerMock victim = server.addPlayer("Victim");
        tracked.add(victim);
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource.builder(
                org.bukkit.damage.DamageType.PLAYER_ATTACK).build();
        server.getPluginManager().callEvent(new PlayerDeathEvent(victim, source,
                new ArrayList<>(), 0, "died"));
        List<ControlKind> kinds = drainKinds();
        assertTrue(kinds.contains(ControlKind.DEATH), "missing DEATH in " + kinds);
    }

    @Test
    @DisplayName("join and quit maintain the tracked set")
    void presence() {
        PlayerMock player = server.addPlayer("Joiner");
        server.getPluginManager().callEvent(
                new org.bukkit.event.player.PlayerJoinEvent(player, "joined"));
        assertEquals(1, tracked.size());

        server.getPluginManager().callEvent(
                new org.bukkit.event.player.PlayerQuitEvent(player, "quit"));
        assertEquals(0, tracked.size());
    }

    @Test
    @DisplayName("report-only: damage event is not cancelled and health untouched")
    void reportOnly() {
        PlayerMock attacker = server.addPlayer("Attacker");
        PlayerMock victim = server.addPlayer("Victim");
        double before = victim.getHealth();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, victim,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 6.0);
        server.getPluginManager().callEvent(event);
        assertEquals(false, event.isCancelled());
        assertEquals(before, victim.getHealth(), "listener must not change health");
    }

    @Test
    @DisplayName("PaperPlayerSource reads a live mock player without allocation hooks")
    void paperSourceReads() {
        PlayerMock player = server.addPlayer("Reader");
        player.teleport(new org.bukkit.Location(player.getWorld(), 10.5, 65.0, -3.25, 45f, 10f));
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

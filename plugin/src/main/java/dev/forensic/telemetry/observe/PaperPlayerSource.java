package dev.forensic.telemetry.observe;

import dev.forensic.telemetry.record.RecordLayout;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Zero-allocation Paper adapter: reads authoritative state into a reused snapshot.
 *
 * <p>Allocation discipline (every getter verified against paper-api 1.21.11 via javap):
 *
 * <ul>
 *   <li>{@code getLocation(Location)} fills the caller-owned instance; the no-arg overload
 *       allocates and is never called here.
 *   <li>{@code getEntityId}, {@code getPing}, {@code isOnGround}, {@code isSneaking},
 *       {@code isSprinting}, {@code isFlying}, {@code isSwimming}, {@code isGliding},
 *       {@code isClimbing}, {@code isRiptiding} are all primitive getters.
 *   <li>{@code getUniqueId} returns the entity's cached UUID reference; {@code getWorld}
 *       returns the cached world reference. Neither allocates.
 *   <li>Yaw/pitch come from the reused Location as primitive floats.
 * </ul>
 *
 * <p>Deliberately absent: velocity (Vector allocates; derivable cloud-side from positions),
 * health (arrives via DAMAGE/DEATH events), held item (ItemStack; Phase 1 does not need it),
 * blocking state (no zero-alloc getter in this API version; shield use is inferable from
 * damage patterns if ever needed).
 */
public final class PaperPlayerSource implements ObserverTask.StateReader {

    @Override
    public boolean readInto(Player player, int tick, PlayerSnapshot snap) {
        return readIntoLive(player, tick, snap);
    }

    private final Location scratch = new Location(null, 0, 0, 0);

    /**
     * Fills {@code snap} from the live player. Returns false when the player can no longer be
     * read (should not happen on the main thread for a tracked player; the caller drops them).
     */
    public boolean readIntoLive(Player player, int tick, PlayerSnapshot snap) {
        if (!player.isValid()) {
            return false;
        }
        player.getLocation(scratch);
        int flags = 0;
        // Entity-typed call: Player redeclares isOnGround() as deprecated (same behavior,
        // same dispatch), and this plugin treats deprecation warnings as build noise to
        // eliminate, not suppress.
        if (((org.bukkit.entity.Entity) player).isOnGround()) {
            flags |= RecordLayout.FLAG_GROUND;
        }
        if (player.isSprinting()) {
            flags |= RecordLayout.FLAG_SPRINT;
        }
        if (player.isSneaking()) {
            flags |= RecordLayout.FLAG_SNEAK;
        }
        if (player.isFlying()) {
            flags |= RecordLayout.FLAG_FLY;
        }
        if (player.isSwimming()) {
            flags |= RecordLayout.FLAG_SWIM;
        }
        if (player.isGliding()) {
            flags |= RecordLayout.FLAG_GLIDE;
        }
        if (player.isClimbing()) {
            flags |= RecordLayout.FLAG_CLIMB;
        }
        if (player.isRiptiding()) {
            flags |= RecordLayout.FLAG_RIPTIDE;
        }
        snap.tick = tick;
        snap.entityId = player.getEntityId();
        snap.playerId = player.getUniqueId();
        snap.x = scratch.getX();
        snap.y = scratch.getY();
        snap.z = scratch.getZ();
        snap.yaw = scratch.getYaw();
        snap.pitch = scratch.getPitch();
        snap.flags = flags;
        snap.pingMs = player.getPing();
        return true;
    }
}

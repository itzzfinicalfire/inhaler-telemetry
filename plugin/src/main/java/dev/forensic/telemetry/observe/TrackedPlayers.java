package dev.forensic.telemetry.observe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Tracked player set with stable iteration order and entityId lookup.
 *
 * <p>Why not {@code Bukkit.getOnlinePlayers()} every tick: the returned collection's
 * allocation behavior is an implementation detail of the server build, and this plugin does
 * not get to depend on implementation details for its hottest loop. This set is seeded once
 * at enable and maintained by join/quit events on the same thread that reads it, so it cannot
 * drift and allocates only on membership change (join/quit), never per tick.
 *
 * <p>Iteration order is insertion order (deterministic encoding order for a given join
 * sequence), which keeps regression tests stable. The entityId map lets the uploader resolve
 * entity ids in drained chunks back to UUIDs for the envelope {@code players[]} array.
 * Entity ids are session-scoped and reused after quit; entries are removed on quit and
 * overwritten on join, so a stale mapping cannot survive.
 */
public final class TrackedPlayers {

    private final Map<UUID, Entry> byUuid = new LinkedHashMap<>();
    private final Map<Integer, UUID> uuidByEntityId = new LinkedHashMap<>();

    public void add(Player player) {
        UUID id = player.getUniqueId();
        Entry existing = byUuid.get(id);
        if (existing != null) {
            uuidByEntityId.remove(existing.entityId);
        }
        int entityId = player.getEntityId();
        byUuid.put(id, new Entry(player, entityId, new PlayerSnapshot()));
        uuidByEntityId.put(entityId, id);
    }

    public void remove(Player player) {
        UUID id = player.getUniqueId();
        Entry removed = byUuid.remove(id);
        if (removed != null) {
            uuidByEntityId.remove(removed.entityId, id);
        }
    }

    public int size() {
        return byUuid.size();
    }

    public Iterable<Entry> entries() {
        return byUuid.values();
    }

    /** Resolves a session entity id to a player UUID, or null after quit/reuse. */
    public UUID uuidForEntityId(int entityId) {
        return uuidByEntityId.get(entityId);
    }

    public void clear() {
        byUuid.clear();
        uuidByEntityId.clear();
    }

    /**
     * Test/benchmark entry point: server-free drivers construct entries with a null player
     * and a scripted reader. Never used on the production path (joins go through
     * {@link #add}), so its existence cannot affect live behavior.
     */
    public void putTestEntry(UUID id, Entry entry) {
        byUuid.put(id, entry);
        if (entry.entityId >= 0) {
            uuidByEntityId.put(entry.entityId, id);
        }
    }

    public static final class Entry {
        public final Player player;
        public final int entityId;
        public final PlayerSnapshot snapshot;

        public Entry(Player player, int entityId, PlayerSnapshot snapshot) {
            this.player = player;
            this.entityId = entityId;
            this.snapshot = snapshot;
        }
    }
}

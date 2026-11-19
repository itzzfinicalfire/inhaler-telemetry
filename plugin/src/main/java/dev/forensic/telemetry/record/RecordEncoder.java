package dev.forensic.telemetry.record;

import java.nio.ByteBuffer;

/**
 * Zero-allocation record encoders.
 *
 * <p>All methods write at absolute buffer positions and return void. There is deliberately no
 * returned object, no builder, no intermediate: on the main server thread, 800 records/second
 * must not create 800 of anything. Callers hand a slice position; the encoder puts 32 bytes.
 *
 * <p>Byte order is big-endian throughout (the JVM default), matching the cloud decoder, which
 * reads with DataView defaults. Mixed endianness would be a silent corruption vector, so there
 * is exactly one order and it is this one.
 *
 * <p>Every control encoder takes {@code sequence}, the intra-tick writer order stamped by the
 * ring. See {@link RecordLayout} for why it exists.
 */
public final class RecordEncoder {

    private RecordEncoder() {}

    public static void encodeState(
            ByteBuffer buf,
            int base,
            int tick,
            int entityId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int flags,
            int pingMs) {
        buf.putInt(base + RecordLayout.OFF_TICK, tick);
        buf.putInt(base + RecordLayout.OFF_ENTITY_ID, entityId);
        // Narrowing double->float is a precision decision the layout owns openly:
        // float32 carries ~7 significant digits (~mm precision at Minecraft
        // coordinates), and the alternative (float64 xyz = 24 bytes) would leave
        // no room for rotation. This is transport precision, never analysis
        // quantization: every bit the server had at float32 survives.
        buf.putFloat(base + RecordLayout.OFF_X, (float) x);
        buf.putFloat(base + RecordLayout.OFF_Y, (float) y);
        buf.putFloat(base + RecordLayout.OFF_Z, (float) z);
        buf.putFloat(base + RecordLayout.OFF_YAW, yaw);
        buf.putFloat(base + RecordLayout.OFF_PITCH, pitch);
        buf.putShort(base + RecordLayout.OFF_FLAGS, (short) (flags & 0xFFFF));
        buf.putShort(base + RecordLayout.OFF_PING, (short) Math.min(Math.max(pingMs, 0), 0xFFFF));
    }

    private static void controlHeader(
            ByteBuffer buf, int base, ControlKind kind, int tick, int sequence) {
        buf.put(base + RecordLayout.OFF_KIND, (byte) kind.marker());
        buf.put(base + RecordLayout.OFF_CTRL_SEQ, (byte) (sequence & 0xFF));
        buf.put(base + 2, (byte) 0);
        buf.put(base + 3, (byte) 0);
        buf.putInt(base + RecordLayout.OFF_CTRL_TICK, tick);
    }

    public static void encodeAttack(ByteBuffer buf, int base, int tick, int sequence,
            int attackerId, int victimId, float damage) {
        controlHeader(buf, base, ControlKind.ATTACK, tick, sequence);
        buf.putInt(base + 8, attackerId);
        buf.putInt(base + 12, victimId);
        buf.putFloat(base + 16, damage);
        zeroTail(buf, base, 20, 32);
    }

    public static void encodeDamage(ByteBuffer buf, int base, int tick, int sequence,
            int victimId, float damage, int causeOrdinal, int damagerId) {
        controlHeader(buf, base, ControlKind.DAMAGE, tick, sequence);
        buf.putInt(base + 8, victimId);
        buf.putFloat(base + 12, damage);
        buf.put(base + 16, (byte) causeOrdinal);
        buf.put(base + 17, (byte) 0);
        buf.put(base + 18, (byte) 0);
        buf.put(base + 19, (byte) 0);
        buf.putInt(base + 20, damagerId);
        zeroTail(buf, base, 24, 32);
    }

    public static void encodeDeath(ByteBuffer buf, int base, int tick, int sequence,
            int victimId) {
        controlHeader(buf, base, ControlKind.DEATH, tick, sequence);
        buf.putInt(base + 8, victimId);
        zeroTail(buf, base, 12, 32);
    }

    public static void encodeSessionStart(ByteBuffer buf, int base, int tick, int sequence) {
        controlHeader(buf, base, ControlKind.SESSION_START, tick, sequence);
        zeroTail(buf, base, 8, 32);
    }

    public static void encodeSessionEnd(ByteBuffer buf, int base, int tick, int sequence) {
        controlHeader(buf, base, ControlKind.SESSION_END, tick, sequence);
        zeroTail(buf, base, 8, 32);
    }

    public static void encodeGap(ByteBuffer buf, int base, int sealTick, int sequence,
            int fromTick, int toTick, int reason) {
        controlHeader(buf, base, ControlKind.GAP, sealTick, sequence);
        buf.putInt(base + 8, fromTick);
        buf.putInt(base + 12, toTick);
        buf.put(base + 16, (byte) reason);
        zeroTail(buf, base, 17, 32);
    }

    public static void encodeInterest(ByteBuffer buf, int base, int tick, int sequence,
            int entityA, int entityB, int trigger) {
        controlHeader(buf, base, ControlKind.INTEREST, tick, sequence);
        buf.putInt(base + 8, entityA);
        buf.putInt(base + 12, entityB);
        buf.put(base + 16, (byte) trigger);
        zeroTail(buf, base, 17, 32);
    }

    public static void encodeResync(ByteBuffer buf, int base, int tick, int sequence,
            int configHash) {
        controlHeader(buf, base, ControlKind.RESYNC, tick, sequence);
        buf.putInt(base + 8, configHash);
        zeroTail(buf, base, 12, 32);
    }

    public static void encodeHeldSlot(ByteBuffer buf, int base, int tick, int sequence,
            int entityId, int fromSlot, int toSlot, int previousCategory, int newCategory) {
        controlHeader(buf, base, ControlKind.HELD_SLOT, tick, sequence);
        buf.putInt(base + 8, entityId);
        buf.put(base + 12, (byte) fromSlot);
        buf.put(base + 13, (byte) toSlot);
        buf.put(base + 14, (byte) previousCategory);
        buf.put(base + 15, (byte) newCategory);
        zeroTail(buf, base, 16, 32);
    }

    public static void encodePlace(ByteBuffer buf, int base, int tick, int sequence,
            int entityId, int blockX, int blockY, int blockZ, int blockCategory, int flags) {
        controlHeader(buf, base, ControlKind.PLACE, tick, sequence);
        buf.putInt(base + 8, entityId);
        buf.putInt(base + 12, blockX);
        buf.putInt(base + 16, blockY);
        buf.putInt(base + 20, blockZ);
        buf.put(base + 24, (byte) blockCategory);
        buf.put(base + 25, (byte) flags);
        zeroTail(buf, base, 26, 32);
    }

    public static void encodeInteract(ByteBuffer buf, int base, int tick, int sequence,
            int entityId, int blockX, int blockY, int blockZ, int interactKind,
            int itemCategory, int flags) {
        controlHeader(buf, base, ControlKind.INTERACT, tick, sequence);
        buf.putInt(base + 8, entityId);
        buf.putInt(base + 12, blockX);
        buf.putInt(base + 16, blockY);
        buf.putInt(base + 20, blockZ);
        buf.put(base + 24, (byte) interactKind);
        buf.put(base + 25, (byte) itemCategory);
        buf.put(base + 26, (byte) flags);
        zeroTail(buf, base, 27, 32);
    }

    public static void encodeSwing(ByteBuffer buf, int base, int tick, int sequence,
            int entityId, int hand, int flags) {
        controlHeader(buf, base, ControlKind.SWING, tick, sequence);
        buf.putInt(base + 8, entityId);
        buf.put(base + 12, (byte) hand);
        buf.put(base + 13, (byte) flags);
        zeroTail(buf, base, 14, 32);
    }

    public static void encodeCrystalSpawn(ByteBuffer buf, int base, int tick, int sequence,
            int crystalEntityId, float x, float y, float z, int ownerEntityId) {
        controlHeader(buf, base, ControlKind.CRYSTAL_SPAWN, tick, sequence);
        buf.putInt(base + 8, crystalEntityId);
        buf.putFloat(base + 12, x);
        buf.putFloat(base + 16, y);
        buf.putFloat(base + 20, z);
        buf.putInt(base + 24, ownerEntityId);
        zeroTail(buf, base, 28, 32);
    }

    public static void encodeCrystalBreak(ByteBuffer buf, int base, int tick, int sequence,
            int crystalEntityId, int breakerEntityId, float x, float y, float z) {
        controlHeader(buf, base, ControlKind.CRYSTAL_BREAK, tick, sequence);
        buf.putInt(base + 8, crystalEntityId);
        buf.putInt(base + 12, breakerEntityId);
        buf.putFloat(base + 16, x);
        buf.putFloat(base + 20, y);
        buf.putFloat(base + 24, z);
        zeroTail(buf, base, 28, 32);
    }

    public static void encodeAnchorExplode(ByteBuffer buf, int base, int tick, int sequence,
            float x, float y, float z, int triggerEntityId) {
        controlHeader(buf, base, ControlKind.ANCHOR_EXPLODE, tick, sequence);
        buf.putFloat(base + 8, x);
        buf.putFloat(base + 12, y);
        buf.putFloat(base + 16, z);
        buf.putInt(base + 20, triggerEntityId);
        zeroTail(buf, base, 24, 32);
    }

    public static void encodeTotemPop(ByteBuffer buf, int base, int tick, int sequence,
            int entityId) {
        controlHeader(buf, base, ControlKind.TOTEM_POP, tick, sequence);
        buf.putInt(base + 8, entityId);
        zeroTail(buf, base, 12, 32);
    }

    public static void encodeWindCharge(ByteBuffer buf, int base, int tick, int sequence,
            int shooterEntityId, int projectileEntityId) {
        controlHeader(buf, base, ControlKind.WIND_CHARGE, tick, sequence);
        buf.putInt(base + 8, shooterEntityId);
        buf.putInt(base + 12, projectileEntityId);
        zeroTail(buf, base, 16, 32);
    }

    public static void encodeAttackCtx(ByteBuffer buf, int base, int tick, int sequence,
            int attackerEntityId, float fallDistance, float attackCooldown,
            int heldCategory, int flags, int victimCategory) {
        controlHeader(buf, base, ControlKind.ATTACK_CTX, tick, sequence);
        buf.putInt(base + 8, attackerEntityId);
        buf.putFloat(base + 12, fallDistance);
        buf.putFloat(base + 16, attackCooldown);
        buf.put(base + 20, (byte) heldCategory);
        buf.put(base + 21, (byte) flags);
        buf.put(base + 22, (byte) victimCategory);
        buf.put(base + 23, (byte) 0);
        zeroTail(buf, base, 24, 32);
    }

    private static void zeroTail(ByteBuffer buf, int base, int from, int to) {
        for (int i = from; i < to; i++) {
            buf.put(base + i, (byte) 0);
        }
    }
}

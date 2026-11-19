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

    private static void controlHeader(ByteBuffer buf, int base, ControlKind kind, int tick) {
        buf.put(base + RecordLayout.OFF_KIND, (byte) kind.marker());
        buf.put(base + 1, (byte) 0);
        buf.put(base + 2, (byte) 0);
        buf.put(base + 3, (byte) 0);
        buf.putInt(base + RecordLayout.OFF_CTRL_TICK, tick);
    }

    public static void encodeAttack(ByteBuffer buf, int base, int tick, int attackerId, int victimId,
            float damage) {
        controlHeader(buf, base, ControlKind.ATTACK, tick);
        buf.putInt(base + 8, attackerId);
        buf.putInt(base + 12, victimId);
        buf.putFloat(base + 16, damage);
        zeroTail(buf, base, 20, 32);
    }

    public static void encodeDamage(ByteBuffer buf, int base, int tick, int victimId, float damage,
            int causeOrdinal, int damagerId) {
        controlHeader(buf, base, ControlKind.DAMAGE, tick);
        buf.putInt(base + 8, victimId);
        buf.putFloat(base + 12, damage);
        buf.put(base + 16, (byte) causeOrdinal);
        buf.put(base + 17, (byte) 0);
        buf.put(base + 18, (byte) 0);
        buf.put(base + 19, (byte) 0);
        buf.putInt(base + 20, damagerId);
        zeroTail(buf, base, 24, 32);
    }

    public static void encodeDeath(ByteBuffer buf, int base, int tick, int victimId) {
        controlHeader(buf, base, ControlKind.DEATH, tick);
        buf.putInt(base + 8, victimId);
        zeroTail(buf, base, 12, 32);
    }

    public static void encodeSessionStart(ByteBuffer buf, int base, int tick) {
        controlHeader(buf, base, ControlKind.SESSION_START, tick);
        zeroTail(buf, base, 8, 32);
    }

    public static void encodeSessionEnd(ByteBuffer buf, int base, int tick) {
        controlHeader(buf, base, ControlKind.SESSION_END, tick);
        zeroTail(buf, base, 8, 32);
    }

    public static void encodeGap(ByteBuffer buf, int base, int sealTick, int fromTick, int toTick,
            int reason) {
        controlHeader(buf, base, ControlKind.GAP, sealTick);
        buf.putInt(base + 8, fromTick);
        buf.putInt(base + 12, toTick);
        buf.put(base + 16, (byte) reason);
        zeroTail(buf, base, 17, 32);
    }

    public static void encodeInterest(ByteBuffer buf, int base, int tick, int entityA, int entityB,
            int trigger) {
        controlHeader(buf, base, ControlKind.INTEREST, tick);
        buf.putInt(base + 8, entityA);
        buf.putInt(base + 12, entityB);
        buf.put(base + 16, (byte) trigger);
        zeroTail(buf, base, 17, 32);
    }

    public static void encodeResync(ByteBuffer buf, int base, int tick, int configHash) {
        controlHeader(buf, base, ControlKind.RESYNC, tick);
        buf.putInt(base + 8, configHash);
        zeroTail(buf, base, 12, 32);
    }

    private static void zeroTail(ByteBuffer buf, int base, int from, int to) {
        for (int i = from; i < to; i++) {
            buf.put(base + i, (byte) 0);
        }
    }
}

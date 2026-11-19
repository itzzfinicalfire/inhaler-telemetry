package dev.forensic.telemetry.fixtures;

import dev.forensic.telemetry.record.ControlFlags;
import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.InteractKind;
import dev.forensic.telemetry.record.ItemCategory;
import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.record.RecordLayout;
import dev.forensic.telemetry.record.VictimCategory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates cross-language fixture bytes with the REAL production encoder.
 *
 * <p>Run with {@code gradlew :plugin:dumpFixtures}. Writes one hex file per scenario into
 * {@code build/fixtures/}. The cloud's {@code records.cross.test.ts} embeds that hex and
 * asserts full decode: if the Java layout drifts, the TypeScript side fails first. When the
 * layout intentionally changes, regenerate and update both sides together.
 *
 * <p>Scenarios are fixed and deterministic: no timestamps, no random, no config. Every byte
 * below is hand-auditable against RecordLayout.
 */
public final class FixtureMain {

    private FixtureMain() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/fixtures");
        Files.createDirectories(out);
        writeScenario(out.resolve("scenario1.hex"), scenario1());
        writeScenario(out.resolve("scenario2.hex"), scenario2());
        writeScenario(out.resolve("scenario3-v2.hex"), scenario3());
        System.out.println("fixtures written to " + out.toAbsolutePath());
    }

    static ByteBuffer scenario1() {
        // 12 records: session_start @100, interleaved state for entities 7 and 8
        // @100-104, attack @102 (7 -> 8, 6.0). Damage/interest/gap/death/resync
        // live in scenario2; each file stays small enough to audit by hand.
        ByteBuffer buf = ByteBuffer.allocateDirect(12 * 32);
        int base = 0;
        RecordEncoder.encodeSessionStart(buf, base, 100, 0);
        base += 32;
        for (int t = 100; t <= 104; t++) {
            RecordEncoder.encodeState(buf, base, t, 7,
                    t * 0.5, 64.0, 0.0, (float) (t * 10), 0f,
                    RecordLayout.FLAG_GROUND, 23);
            base += 32;
            RecordEncoder.encodeState(buf, base, t, 8,
                    200.0, 65.0, -50.0, 180f, 5f,
                    RecordLayout.FLAG_GROUND | RecordLayout.FLAG_SNEAK,
                    41);
            base += 32;
        }
        RecordEncoder.encodeAttack(buf, base, 102, 0, 7, 8, 6.0f);
        return buf;
    }

    static ByteBuffer scenario2() {
        // 6 records: gap (seal 105, 90-95 RING_OVERFLOW), death @106, resync @120,
        // session_end @130, interest @107 (9,10,trigger 1), damage @108 environmental.
        ByteBuffer buf = ByteBuffer.allocateDirect(6 * 32);
        int base = 0;
        RecordEncoder.encodeGap(buf, base, 105, 0, 90, 95, 0);
        base += 32;
        RecordEncoder.encodeDeath(buf, base, 106, 0, 8);
        base += 32;
        RecordEncoder.encodeInterest(buf, base, 107, 0, 9, 10, 1);
        base += 32;
        RecordEncoder.encodeDamage(buf, base, 108, 0, 8, 4.5f, 3, -1);
        base += 32;
        RecordEncoder.encodeResync(buf, base, 120, 0, 12345);
        base += 32;
        RecordEncoder.encodeSessionEnd(buf, base, 130, 0);
        return buf;
    }

    static ByteBuffer scenario3() {
        // v2 kinds with nonzero intra-tick sequence, in writer order: a held-slot swap,
        // a crystal spawn, an attack context, a crystal break, an anchor charge attempt,
        // an anchor explosion, a totem pop and a wind-charge launch. Byte-auditable.
        ControlKind[] kinds = {
            ControlKind.HELD_SLOT, ControlKind.CRYSTAL_SPAWN, ControlKind.ATTACK_CTX,
            ControlKind.CRYSTAL_BREAK, ControlKind.INTERACT, ControlKind.ANCHOR_EXPLODE,
            ControlKind.TOTEM_POP, ControlKind.WIND_CHARGE,
        };
        ByteBuffer buf = ByteBuffer.allocateDirect(kinds.length * 32);
        int base = 0;
        RecordEncoder.encodeHeldSlot(buf, base, 200, 1, 7, 0, 4,
                ItemCategory.SWORD, ItemCategory.MACE);
        base += 32;
        RecordEncoder.encodeCrystalSpawn(buf, base, 200, 2, 42, 10.5f, 64.0f, -3.25f, 7);
        base += 32;
        RecordEncoder.encodeAttackCtx(buf, base, 200, 3, 7, 2.5f, 0.875f,
                ItemCategory.MACE, ControlFlags.ATTACK_VICTIM_PLAYER, VictimCategory.PLAYER);
        base += 32;
        RecordEncoder.encodeCrystalBreak(buf, base, 200, 4, 42, 7, 10.5f, 64.0f, -3.25f);
        base += 32;
        RecordEncoder.encodeInteract(buf, base, 201, 1, 7, 10, 64, -3,
                InteractKind.ANCHOR_CHARGE, ItemCategory.GLOWSTONE, 0);
        base += 32;
        RecordEncoder.encodeAnchorExplode(buf, base, 202, 1, 10.5f, 64.5f, -2.5f, -1);
        base += 32;
        RecordEncoder.encodeTotemPop(buf, base, 203, 1, 8);
        base += 32;
        RecordEncoder.encodeWindCharge(buf, base, 204, 1, 7, 91);
        return buf;
    }

    private static void writeScenario(Path path, ByteBuffer buf) throws Exception {
        // Absolute puts never advance position, so remaining() is meaningless here.
        // Scenario buffers are exactly filled by construction; capacity IS the size.
        // (Absolute get(i) is bounds-checked against limit, and flip() would cap
        // limit at the un-advanced position 0: hence neither flip nor remaining.)
        byte[] bytes = new byte[buf.capacity()];
        buf.limit(buf.capacity());
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = buf.get(i);
        }
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        Files.writeString(path, hex.toString());
        System.out.println(path.getFileName() + ": " + bytes.length + " bytes");
    }
}

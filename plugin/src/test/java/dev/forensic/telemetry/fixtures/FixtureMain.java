package dev.forensic.telemetry.fixtures;

import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.record.RecordLayout;
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
        System.out.println("fixtures written to " + out.toAbsolutePath());
    }

    static ByteBuffer scenario1() {
        // 12 records: session_start @100, interleaved state for entities 7 and 8
        // @100-104, attack @102 (7 -> 8, 6.0). Damage/interest/gap/death/resync
        // live in scenario2; each file stays small enough to audit by hand.
        ByteBuffer buf = ByteBuffer.allocateDirect(12 * 32);
        int base = 0;
        RecordEncoder.encodeSessionStart(buf, base, 100);
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
        RecordEncoder.encodeAttack(buf, base, 102, 7, 8, 6.0f);
        return buf;
    }

    static ByteBuffer scenario2() {
        // 6 records: gap (seal 105, 90-95 RING_OVERFLOW), death @106, resync @120,
        // session_end @130, interest @107 (9,10,trigger 1), damage @108 environmental.
        ByteBuffer buf = ByteBuffer.allocateDirect(6 * 32);
        int base = 0;
        RecordEncoder.encodeGap(buf, base, 105, 90, 95, 0);
        base += 32;
        RecordEncoder.encodeDeath(buf, base, 106, 8);
        base += 32;
        RecordEncoder.encodeInterest(buf, base, 107, 9, 10, 1);
        base += 32;
        RecordEncoder.encodeDamage(buf, base, 108, 8, 4.5f, 3, -1);
        base += 32;
        RecordEncoder.encodeResync(buf, base, 120, 12345);
        base += 32;
        RecordEncoder.encodeSessionEnd(buf, base, 130);
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

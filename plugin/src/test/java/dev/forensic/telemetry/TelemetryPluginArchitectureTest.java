package dev.forensic.telemetry;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces the load-bearing report-only invariant at the bytecode level.
 *
 * <p>Configuration checks (TelemetryConfig) catch an operator asking for action. This test
 * catches a developer writing it: every compiled main class is parsed, and any reference to a
 * known mutating Bukkit/Paper method is a failure. Comments and documentation cannot keep this
 * promise; only the shipped class files can.
 *
 * <p>Scope is deliberately conservative. The test only looks at method references whose owner
 * is a Bukkit/Paper type, so a same-named method on our own record or on {@code
 * java.util.concurrent} never trips it. It parses the constant pool rather than scanning raw
 * strings, so a string literal containing "kick" is not a violation while an actual {@code
 * Player#kick()} call is.
 */
class TelemetryPluginArchitectureTest {

    /**
     * Method names that mutate player state, cancel events, or mutate the world. Any call to
     * one of these on a Bukkit/Paper type is action, not observation.
     */
    private static final Set<String> FORBIDDEN_METHODS = Set.of(
            // Removal and account state
            "kick", "ban", "setBanned", "setOp", "setWhitelisted", "pardon",
            // Health, movement, and combat state
            "damage", "setHealth", "setMaxHealth", "setAbsorptionAmount", "setFoodLevel",
            "setSaturation", "setExhaustion", "setFireTicks", "setFallDistance",
            "setVelocity", "setWalkSpeed", "setFlySpeed", "setAllowFlight", "setFlying",
            "setInvulnerable", "setNoDamageTicks", "setMaximumNoDamageTicks",
            "setGameMode", "teleport", "teleportAsync",
            // Effects and progression
            "addPotionEffect", "removePotionEffect", "clearActivePotionEffects",
            "giveExp", "giveExpLevels", "setLevel", "setExp", "setTotalExperience",
            // Inventory mutation
            "setItemInMainHand", "setItemInOffHand", "setItemOnCursor", "setItem",
            "setContents", "setArmorContents", "addItem", "removeItem", "clear",
            "closeInventory", "openInventory", "openWorkbench", "openEnchanting",
            "openMerchant", "updateInventory",
            // World mutation
            "setBlock", "setType", "setBlockData", "breakNaturally", "setBiome",
            "generateTree", "strikeLightning", "createExplosion", "spawnEntity",
            "dropItem", "setTime", "setStorm", "setThundering", "setWeatherDuration",
            "setDifficulty", "setSpawnLocation", "setWhitelist",
            // Commands and event cancellation
            "dispatchCommand", "performCommand", "cancel", "setCancelled",
            // v2 event surface: entity/explosion/interaction mutation
            "setBeamTarget", "setShowingBottom", "setShooter", "setYield",
            "setUseInteractedBlock", "setUseItemInHand", "setBuild");

    /** Method owners that are part of the server API surface. */
    private static final List<String> API_PREFIXES = List.of(
            "org/bukkit/", "io/papermc/", "com/destroystokyo/");

    @Test
    @DisplayName("compiled plugin classes reference no mutating Bukkit API")
    void compiledClassesAreReportOnly() throws IOException {
        Path classes = Path.of("build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classes),
                "main classes not compiled; run this through Gradle (test depends on classes)");

        List<String> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        assertTrue(!files.isEmpty(), "no class files found under " + classes);

        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            for (MethodRef ref : methodRefs(bytes)) {
                if (!isApiType(ref.owner()) || !FORBIDDEN_METHODS.contains(ref.name())) {
                    continue;
                }
                violations.add(
                        classes.relativize(file) + " calls " + ref.owner() + "#" + ref.name());
            }
        }

        if (!violations.isEmpty()) {
            fail("report-only invariant violated by compiled plugin bytecode:\n  "
                    + String.join("\n  ", violations));
        }
    }

    private static boolean isApiType(String internalName) {
        for (String prefix : API_PREFIXES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private record MethodRef(String owner, String name) {}

    /**
     * Extracts Methodref/InterfaceMethodref entries from one class file's constant pool.
     * Layout per JVMS §4.1 and §4.4: magic, versions, pool count, then variable-width entries.
     */
    private static List<MethodRef> methodRefs(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int magic = in.getInt();
        if (magic != 0xCAFEBABE) {
            throw new IllegalArgumentException("not a class file (bad magic)");
        }
        in.getShort(); // minor
        in.getShort(); // major
        int poolCount = in.getShort() & 0xFFFF;
        String[] utf8 = new String[poolCount];
        int[] classNameIndex = new int[poolCount];
        int[] methodClassIndex = new int[poolCount];
        int[] methodNameAndTypeIndex = new int[poolCount];
        int[] nameAndTypeNameIndex = new int[poolCount];

        for (int i = 1; i < poolCount; i++) {
            int tag = in.get() & 0xFF;
            switch (tag) {
                case 1 -> { // CONSTANT_Utf8
                    int len = in.getShort() & 0xFFFF;
                    byte[] raw = new byte[len];
                    in.get(raw);
                    utf8[i] = new String(raw, StandardCharsets.UTF_8);
                }
                case 3, 4 -> in.getInt(); // Integer, Float
                case 5, 6 -> { // Long, Double (two slots)
                    in.getLong();
                    i++;
                }
                case 7 -> classNameIndex[i] = in.getShort() & 0xFFFF; // Class
                case 8 -> in.getShort(); // String
                case 9 -> { // Fieldref
                    in.getShort();
                    in.getShort();
                }
                case 10, 11 -> { // Methodref, InterfaceMethodref
                    methodClassIndex[i] = in.getShort() & 0xFFFF;
                    methodNameAndTypeIndex[i] = in.getShort() & 0xFFFF;
                }
                case 12 -> { // NameAndType
                    nameAndTypeNameIndex[i] = in.getShort() & 0xFFFF;
                    in.getShort(); // descriptor
                }
                case 15 -> { // MethodHandle
                    in.get();
                    in.getShort();
                }
                case 16 -> in.getShort(); // MethodType
                case 17, 18 -> { // Dynamic, InvokeDynamic
                    in.getShort();
                    in.getShort();
                }
                case 19, 20 -> in.getShort(); // Module, Package
                default -> throw new IllegalArgumentException(
                        "unexpected constant-pool tag " + tag + " at index " + i);
            }
        }

        List<MethodRef> refs = new ArrayList<>();
        for (int i = 1; i < poolCount; i++) {
            int classIndex = methodClassIndex[i];
            if (classIndex == 0) {
                continue;
            }
            String owner = utf8[classNameIndex[classIndex]];
            int nt = methodNameAndTypeIndex[i];
            String name = utf8[nameAndTypeNameIndex[nt]];
            if (owner != null && name != null) {
                refs.add(new MethodRef(owner, name));
            }
        }
        return refs;
    }
}

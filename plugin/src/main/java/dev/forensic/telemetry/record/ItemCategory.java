package dev.forensic.telemetry.record;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Small, closed item-category enum carried instead of item identity.
 *
 * <p>Privacy boundary: the collector never sends inventory contents. Only the category below is
 * recorded, and only on events that already carry an entity and a tick (held-slot change,
 * place, interact, attack context). The numeric values are wire format; append only, never
 * renumber.
 *
 * <p>Categories are deliberately coarse. A detector needs to know "was the mace held" and
 * "was a crystal in hand", not which enchantment or custom name. The mapping is a pure
 * function of {@link Material}, so it is allocation-free on the event path.
 */
public final class ItemCategory {

    public static final int NONE = 0;
    public static final int OTHER = 1;
    public static final int SWORD = 2;
    public static final int AXE = 3;
    public static final int MACE = 4;
    public static final int END_CRYSTAL = 5;
    public static final int OBSIDIAN = 6;
    public static final int RESPAWN_ANCHOR = 7;
    public static final int GLOWSTONE = 8;
    public static final int TOTEM = 9;
    public static final int WIND_CHARGE = 10;

    private ItemCategory() {}

    /** Category of a held stack. Empty/air is {@link #NONE}. Never allocates. */
    public static int of(ItemStack stack) {
        if (stack == null) {
            return NONE;
        }
        return of(stack.getType());
    }

    public static int of(Material material) {
        if (material == null || material.isAir()) {
            return NONE;
        }
        if (material == Material.MACE) {
            return MACE;
        }
        if (material == Material.END_CRYSTAL) {
            return END_CRYSTAL;
        }
        if (material == Material.OBSIDIAN) {
            return OBSIDIAN;
        }
        if (material == Material.RESPAWN_ANCHOR) {
            return RESPAWN_ANCHOR;
        }
        if (material == Material.GLOWSTONE) {
            return GLOWSTONE;
        }
        if (material == Material.TOTEM_OF_UNDYING) {
            return TOTEM;
        }
        if (material == Material.WIND_CHARGE) {
            return WIND_CHARGE;
        }
        String name = material.name();
        if (name.endsWith("_SWORD")) {
            return SWORD;
        }
        // PICKAXE also ends with AXE; it is not an axe for combat purposes.
        if (name.endsWith("_AXE") && !"PICKAXE".equals(name)) {
            return AXE;
        }
        return OTHER;
    }
}

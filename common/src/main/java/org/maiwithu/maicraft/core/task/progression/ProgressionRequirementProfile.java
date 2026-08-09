// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.progression;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;

/** Data-driven private loadout policy for progression; never serialized to the model. */
public final class ProgressionRequirementProfile {
    public record Requirement(
            String key,
            List<ResourceLocation> alternatives,
            int finalCount,
            EquipmentSlot equipSlot,
            boolean hostileHuntAllowed,
            SemanticAcquireTaskRecord.SourceHint sourceHint) {
        public Requirement {
            if (key == null || key.isBlank() || alternatives == null || alternatives.isEmpty()) {
                throw new IllegalArgumentException("progression requirement needs a key and alternatives");
            }
            alternatives = List.copyOf(alternatives);
            if (finalCount < 1) throw new IllegalArgumentException("finalCount must be positive");
            sourceHint = sourceHint == null
                    ? SemanticAcquireTaskRecord.SourceHint.empty() : sourceHint;
        }
    }

    private static final SemanticAcquireTaskRecord.SourceHint SKELETON_ARROWS =
            new SemanticAcquireTaskRecord.SourceHint(
                    List.of(),
                    List.of(id("minecraft:skeleton"), id("minecraft:stray")),
                    List.of(id("minecraft:arrow")),
                    List.of(),
                    "ordinary hostile skeleton-family arrow drops");

    private static final List<Requirement> STRONGHOLD = List.of(
            requirement("navigation_reserve", 4, true, "minecraft:ender_eye"));

    private static final List<Requirement> DRAGON = List.of(
            requirement("ranged_weapon", 1, true, "minecraft:bow", "minecraft:crossbow"),
            new Requirement("ranged_ammunition", ids("minecraft:arrow"), 32, null, true,
                    SKELETON_ARROWS),
            requirement("melee_weapon", 1, false,
                    "minecraft:iron_sword", "minecraft:diamond_sword", "minecraft:netherite_sword"),
            requirement("harvest_tool", 1, false,
                    "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"),
            equipped("head_protection", EquipmentSlot.HEAD,
                    "minecraft:iron_helmet", "minecraft:diamond_helmet", "minecraft:netherite_helmet"),
            equipped("chest_protection", EquipmentSlot.CHEST,
                    "minecraft:iron_chestplate", "minecraft:diamond_chestplate", "minecraft:netherite_chestplate"),
            equipped("leg_protection", EquipmentSlot.LEGS,
                    "minecraft:iron_leggings", "minecraft:diamond_leggings", "minecraft:netherite_leggings"),
            equipped("foot_protection", EquipmentSlot.FEET,
                    "minecraft:iron_boots", "minecraft:diamond_boots", "minecraft:netherite_boots"),
            // Passive-animal hunting is intentionally disabled for food. Craft/cook/storage/farming
            // evidence may satisfy this family without silently killing owned or wild livestock.
            requirement("safe_food", 16, false,
                    "minecraft:bread", "minecraft:baked_potato", "minecraft:cooked_beef",
                    "minecraft:cooked_porkchop", "minecraft:cooked_mutton",
                    "minecraft:cooked_chicken", "minecraft:cooked_rabbit",
                    "minecraft:cooked_cod", "minecraft:cooked_salmon"),
            requirement("scaffolding_reserve", 64, false,
                    "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:netherrack"));

    private static final List<Requirement> ELYTRA = List.of(
            requirement("gateway_consumable", 1, true, "minecraft:ender_pearl"));

    private ProgressionRequirementProfile() {}

    public static List<Requirement> strongholdNavigation() { return STRONGHOLD; }
    public static List<Requirement> dragonLoadout() { return DRAGON; }
    public static List<Requirement> elytraTraversal() { return ELYTRA; }

    private static Requirement requirement(
            String key, int count, boolean hostileHuntAllowed, String... values) {
        return new Requirement(key, ids(values), count, null, hostileHuntAllowed,
                SemanticAcquireTaskRecord.SourceHint.empty());
    }

    private static Requirement equipped(String key, EquipmentSlot slot, String... values) {
        return new Requirement(key, ids(values), 1, slot, false,
                SemanticAcquireTaskRecord.SourceHint.empty());
    }

    private static List<ResourceLocation> ids(String... values) {
        return java.util.Arrays.stream(values).map(ProgressionRequirementProfile::id).toList();
    }

    private static ResourceLocation id(String value) {
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null) throw new IllegalArgumentException("invalid progression item id " + value);
        return parsed;
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.structure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Data-driven, loaded-client evidence for structures whose placement is not synchronized to a
 * remote client.
 *
 * <p>A profile is deliberately evidence, not a hidden assertion about a server structure start.
 * Every group must be present in one bounded cluster. Profiles can be extended without adding a
 * new task branch; an unregistered id is rejected rather than assigned invented coordinates.</p>
 */
public final class StructureEvidenceProfiles {
    public record Group(String label, int minimum, List<String> blockIds) {
        public Group {
            if (label == null || label.isBlank()) throw new IllegalArgumentException("group label");
            if (minimum < 1) throw new IllegalArgumentException("group minimum");
            blockIds = List.copyOf(blockIds);
            if (blockIds.isEmpty()) throw new IllegalArgumentException("group block ids");
        }
    }

    public record Profile(
            String canonicalId,
            Set<String> dimensions,
            int clusterRadius,
            int minimumTotal,
            String evidenceDescription,
            List<Group> groups) {
        public Profile {
            dimensions = Set.copyOf(dimensions);
            groups = List.copyOf(groups);
            if (clusterRadius < 1 || minimumTotal < 1 || groups.isEmpty()) {
                throw new IllegalArgumentException("invalid evidence profile");
            }
        }
    }

    public record ResolvedGroup(String label, int minimum, Set<Block> blocks) {}

    public record ResolvedProfile(
            String requestedId,
            Profile profile,
            List<ResolvedGroup> groups,
            Set<Block> targetBlocks) {}

    private static final Map<String, Profile> PROFILES = profiles();

    private StructureEvidenceProfiles() {}

    public static ResolvedProfile resolve(String structureId) {
        Profile profile = PROFILES.get(structureId);
        if (profile == null) return null;
        List<ResolvedGroup> groups = new ArrayList<>();
        Set<Block> all = new LinkedHashSet<>();
        for (Group group : profile.groups()) {
            Set<Block> blocks = resolveBlocks(group.blockIds());
            if (blocks.isEmpty()) return null;
            groups.add(new ResolvedGroup(
                    group.label(), group.minimum(), Collections.unmodifiableSet(blocks)));
            all.addAll(blocks);
        }
        return new ResolvedProfile(
                structureId,
                profile,
                List.copyOf(groups),
                Collections.unmodifiableSet(all));
    }

    public static boolean hasProfile(String structureId) {
        return PROFILES.containsKey(structureId);
    }

    public static Set<String> registeredIds() {
        return PROFILES.keySet();
    }

    private static Set<Block> resolveBlocks(Collection<String> ids) {
        Set<Block> resolved = new LinkedHashSet<>();
        for (String raw : ids) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) continue;
            Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block != null && block != Blocks.AIR) resolved.add(block);
        }
        return resolved;
    }

    private static Map<String, Profile> profiles() {
        Map<String, Profile> out = new LinkedHashMap<>();

        register(out, profile(
                "minecraft:stronghold", dims("minecraft:overworld"), 12, 1,
                "a loaded end portal frame",
                group("end_portal_frame", 1, "minecraft:end_portal_frame")));

        register(out, profile(
                "minecraft:fortress", dims("minecraft:the_nether"), 18, 20,
                "a dense nether-brick bridge/interior cluster",
                group("nether_brick_mass", 16,
                        "minecraft:nether_bricks", "minecraft:nether_brick_fence",
                        "minecraft:nether_brick_stairs"),
                group("fortress_fittings", 2,
                        "minecraft:nether_brick_fence", "minecraft:nether_brick_stairs")));

        register(out, profile(
                "minecraft:bastion_remnant", dims("minecraft:the_nether"), 18, 16,
                "gilded blackstone inside a dense bastion palette cluster",
                group("gilded_signature", 1, "minecraft:gilded_blackstone"),
                group("blackstone_mass", 12,
                        "minecraft:polished_blackstone_bricks",
                        "minecraft:cracked_polished_blackstone_bricks",
                        "minecraft:chiseled_polished_blackstone",
                        "minecraft:blackstone", "minecraft:polished_blackstone")));

        register(out, profile(
                "minecraft:end_city", dims("minecraft:the_end"), 20, 18,
                "a purpur building cluster with end-city fittings",
                group("purpur_mass", 14,
                        "minecraft:purpur_block", "minecraft:purpur_pillar",
                        "minecraft:purpur_stairs", "minecraft:purpur_slab"),
                group("end_city_fittings", 2,
                        "minecraft:end_rod", "minecraft:purpur_stairs",
                        "minecraft:purpur_slab")));

        register(out, profile(
                "minecraft:monu" + "ment", dims("minecraft:overworld"), 22, 24,
                "a dense prismarine cluster with ocean-temple lighting/palette evidence",
                group("prismarine_mass", 20,
                        "minecraft:prismarine", "minecraft:prismarine_bricks",
                        "minecraft:dark_prismarine"),
                group("ocean_temple_fittings", 2,
                        "minecraft:dark_prismarine", "minecraft:sea_lantern")));

        Profile village = profile(
                "minecraft:village", dims("minecraft:overworld"), 24, 4,
                "a village bell with multiple domestic or profession blocks",
                group("village_center", 1, "minecraft:bell"),
                group("village_activity", 3,
                        "minecraft:white_bed", "minecraft:orange_bed",
                        "minecraft:magenta_bed", "minecraft:light_blue_bed",
                        "minecraft:yellow_bed", "minecraft:lime_bed",
                        "minecraft:pink_bed", "minecraft:gray_bed",
                        "minecraft:light_gray_bed", "minecraft:cyan_bed",
                        "minecraft:purple_bed", "minecraft:blue_bed",
                        "minecraft:brown_bed", "minecraft:green_bed",
                        "minecraft:red_bed", "minecraft:black_bed",
                        "minecraft:composter", "minecraft:blast_furnace",
                        "minecraft:smoker", "minecraft:cartography_table",
                        "minecraft:brewing_stand", "minecraft:lectern",
                        "minecraft:stonecutter", "minecraft:loom",
                        "minecraft:smithing_table", "minecraft:grindstone",
                        "minecraft:fletching_table", "minecraft:barrel"));
        register(out, village,
                "minecraft:village",
                "minecraft:village_plains",
                "minecraft:village_desert",
                "minecraft:village_savanna",
                "minecraft:village_snowy",
                "minecraft:village_taiga");

        register(out, profile(
                "minecraft:ancient_city", dims("minecraft:overworld"), 28, 12,
                "reinforced deepslate surrounded by an ancient-city sculk palette",
                group("ancient_city_signature", 1, "minecraft:reinforced_deepslate"),
                group("sculk_mass", 8,
                        "minecraft:sculk", "minecraft:sculk_catalyst",
                        "minecraft:sculk_sensor", "minecraft:sculk_shrieker",
                        "minecraft:sculk_vein")));

        register(out, profile(
                "minecraft:trial_chambers", dims("minecraft:overworld"), 24, 2,
                "trial-only functional blocks in a loaded chamber palette",
                group("trial_signature", 1, "minecraft:trial_spawner", "minecraft:vault"),
                group("trial_palette", 1,
                        "minecraft:chiseled_tuff", "minecraft:polished_tuff",
                        "minecraft:tuff_bricks", "minecraft:copper_bulb")));

        register(out, profile(
                "minecraft:desert_pyramid", dims("minecraft:overworld"), 20, 20,
                "a desert-temple sandstone and terracotta motif cluster",
                group("pyramid_motif", 4,
                        "minecraft:chiseled_sandstone", "minecraft:orange_terracotta",
                        "minecraft:blue_terracotta"),
                group("sandstone_mass", 14,
                        "minecraft:sandstone", "minecraft:cut_sandstone",
                        "minecraft:chiseled_sandstone")));

        register(out, profile(
                "minecraft:jungle_pyramid", dims("minecraft:overworld"), 18, 18,
                "a mossy temple cluster with carved/redstone fittings",
                group("jungle_temple_mass", 14,
                        "minecraft:mossy_cobblestone", "minecraft:cobblestone"),
                group("jungle_temple_fittings", 3,
                        "minecraft:chiseled_stone_bricks", "minecraft:tripwire",
                        "minecraft:repeater", "minecraft:redstone_wire")));

        register(out, profile(
                "minecraft:mineshaft", dims("minecraft:overworld"), 18, 20,
                "a rail-and-timber underground support cluster",
                group("mineshaft_rails", 8,
                        "minecraft:rail", "minecraft:powered_rail",
                        "minecraft:detector_rail", "minecraft:activator_rail"),
                group("mineshaft_timber", 10,
                        "minecraft:oak_planks", "minecraft:oak_fence",
                        "minecraft:dark_oak_planks", "minecraft:dark_oak_fence")),
                "minecraft:mineshaft", "minecraft:mineshaft_mesa");

        register(out, profile(
                "minecraft:mansion", dims("minecraft:overworld"), 28, 36,
                "a large dark-oak interior palette with mansion carpet/bookshelf evidence",
                group("mansion_shell", 24,
                        "minecraft:dark_oak_planks", "minecraft:dark_oak_log",
                        "minecraft:dark_oak_stairs"),
                group("mansion_interior", 8,
                        "minecraft:white_carpet", "minecraft:red_carpet",
                        "minecraft:blue_carpet", "minecraft:bookshelf")));

        register(out, profile(
                "minecraft:pillager_outpost", dims("minecraft:overworld"), 22, 28,
                "a tall dark-oak/cobblestone outpost palette cluster",
                group("outpost_timber", 16,
                        "minecraft:dark_oak_planks", "minecraft:dark_oak_log",
                        "minecraft:dark_oak_fence"),
                group("outpost_stone", 10,
                        "minecraft:cobblestone", "minecraft:mossy_cobblestone")));

        register(out, profile(
                "minecraft:ruined_portal", Set.of(), 16, 10,
                "crying obsidian in a ruined-portal netherrack/obsidian cluster",
                group("ruined_portal_signature", 1, "minecraft:crying_obsidian"),
                group("ruined_portal_mass", 8,
                        "minecraft:obsidian", "minecraft:netherrack",
                        "minecraft:magma_block", "minecraft:crying_obsidian")),
                "minecraft:ruined_portal",
                "minecraft:ruined_portal_desert",
                "minecraft:ruined_portal_jungle",
                "minecraft:ruined_portal_mountain",
                "minecraft:ruined_portal_nether",
                "minecraft:ruined_portal_ocean",
                "minecraft:ruined_portal_swamp");

        register(out, profile(
                "minecraft:trail_ruins", dims("minecraft:overworld"), 20, 14,
                "suspicious gravel inside a buried masonry/terracotta cluster",
                group("trail_ruins_signature", 3, "minecraft:suspicious_gravel"),
                group("trail_ruins_palette", 10,
                        "minecraft:bricks", "minecraft:mud_bricks",
                        "minecraft:terracotta", "minecraft:yellow_terracotta",
                        "minecraft:red_terracotta", "minecraft:light_blue_terracotta")));

        register(out, profile(
                "minecraft:nether_fossil", dims("minecraft:the_nether"), 18, 12,
                "a dense loaded bone-block fossil cluster",
                group("fossil_bones", 12, "minecraft:bone_block")));

        register(out, profile(
                "minecraft:shipwreck", dims("minecraft:overworld"), 24, 26,
                "a compact mixed-timber hull with multiple ship storage blocks",
                group("ship_hull", 24,
                        "minecraft:oak_planks", "minecraft:spruce_planks",
                        "minecraft:birch_planks", "minecraft:jungle_planks",
                        "minecraft:dark_oak_planks", "minecraft:oak_log",
                        "minecraft:spruce_log", "minecraft:birch_log",
                        "minecraft:jungle_log", "minecraft:dark_oak_log"),
                group("ship_storage", 2, "minecraft:chest", "minecraft:barrel")),
                "minecraft:shipwreck", "minecraft:shipwreck_beached");

        register(out, profile(
                "minecraft:ocean_ruin", dims("minecraft:overworld"), 20, 16,
                "suspicious sand/gravel in a compact ocean-ruin masonry cluster",
                group("ocean_archaeology", 1,
                        "minecraft:suspicious_sand", "minecraft:suspicious_gravel"),
                group("ocean_ruin_palette", 14,
                        "minecraft:stone_bricks", "minecraft:cracked_stone_bricks",
                        "minecraft:mossy_stone_bricks", "minecraft:sandstone",
                        "minecraft:cut_sandstone", "minecraft:magma_block")),
                "minecraft:ocean_ruin",
                "minecraft:ocean_ruin_cold",
                "minecraft:ocean_ruin_warm");

        return Collections.unmodifiableMap(out);
    }

    private static Profile profile(
            String canonicalId,
            Set<String> dimensions,
            int clusterRadius,
            int minimumTotal,
            String evidence,
            Group... groups) {
        return new Profile(
                canonicalId, dimensions, clusterRadius, minimumTotal, evidence, List.of(groups));
    }

    private static Group group(String label, int minimum, String... blockIds) {
        return new Group(label, minimum, List.of(blockIds));
    }

    private static Set<String> dims(String... ids) {
        return Set.of(ids);
    }

    private static void register(Map<String, Profile> out, Profile profile, String... aliases) {
        if (aliases.length == 0) {
            out.put(profile.canonicalId(), profile);
            return;
        }
        for (String alias : aliases) out.put(alias, profile);
    }
}

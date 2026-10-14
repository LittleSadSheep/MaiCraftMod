// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 结构线索的证据组合：哪些方块凑在一起、凑够多少，可以当"附近大概有某种结构"的线索。
 *
 * <p>组合只能证明附近符合特征，不能区分天然结构和人为仿建，所以结构线索只按线索记入
 * 产地线索种类，不冒充到访。表里是原版结构的地表特征；留意清单给的认识不出的结构，
 * 就没有线索可发，不把未知结构猜成最相近的原版建筑。
 */
public final class StructureClues {

    /** 一组证据：这几种方块在这一带至少凑够几个。 */
    public record Group(String label, int minimum, List<String> blockIds) {
        public Group {
            if (minimum < 1) throw new IllegalArgumentException("证据组至少要凑 1 个：" + label);
            blockIds = List.copyOf(blockIds);
            if (blockIds.isEmpty()) throw new IllegalArgumentException("证据组不能没有方块：" + label);
        }
    }

    /** 一份线索档案：某种结构由哪几组证据在多大的聚集范围里凑成。 */
    public record Profile(String structureId, int clusterRadius, int minimumTotal, List<Group> groups) {
        public Profile {
            groups = List.copyOf(groups);
            if (clusterRadius < 1 || minimumTotal < 1 || groups.isEmpty()) {
                throw new IllegalArgumentException("线索档案不完整：" + structureId);
            }
        }
    }

    /** 解析好的档案：方块 ID 换成游戏里真实注册的方块；一组里一个方块都没有就整份不可用。 */
    public record Resolved(Profile profile, List<Block> unionBlocks) {}

    private static final Map<String, Profile> PROFILES = profiles();

    private StructureClues() {}

    /** 这个结构 ID 有没有线索档案（结构在游戏里注册过但没有档案，就没有线索可发）。 */
    public static boolean known(String structureId) {
        return PROFILES.containsKey(normalize(structureId));
    }

    /** 有档案的结构 ID 都有哪些（留意清单的说明与测试用）。 */
    public static Set<String> knownIds() {
        return Set.copyOf(PROFILES.keySet());
    }

    /** 把一个结构 ID 的档案解析成真实方块；有一组一个方块都认不出来，整份就发不出线索。 */
    public static Optional<Resolved> resolve(String structureId) {
        Profile profile = PROFILES.get(normalize(structureId));
        if (profile == null) {
            return Optional.empty();
        }
        Set<Block> union = new LinkedHashSet<>();
        for (Group group : profile.groups()) {
            Set<Block> groupBlocks = blocksOf(group.blockIds());
            if (groupBlocks.isEmpty()) {
                return Optional.empty();
            }
            union.addAll(groupBlocks);
        }
        return Optional.of(new Resolved(profile, List.copyOf(union)));
    }

    /** 一处证据：扫描扫到的方块与它是什么。 */
    public record Evidence(BlockPos pos, Block block) {}

    /**
     * 评估一片证据方块里有没有凑齐的组合，有就给聚集锚点。
     * 每处证据轮流当锚点：以它为圆心在聚集半径内数每组凑了几个，各组都够数、
     * 总量也够数才算一条线索；锚点取第一个凑齐的位置。
     */
    public static Optional<BlockPos> anchorOf(Resolved resolved, List<Evidence> evidence) {
        int radiusSquared = resolved.profile().clusterRadius()
                * resolved.profile().clusterRadius();
        for (Evidence center : evidence) {
            List<Evidence> nearby = evidence.stream()
                    .filter(hit -> hit.pos().distSqr(center.pos()) <= radiusSquared)
                    .toList();
            if (nearby.size() < resolved.profile().minimumTotal()) {
                continue;
            }
            if (groupsSatisfied(resolved, nearby)) {
                return Optional.of(center.pos());
            }
        }
        return Optional.empty();
    }

    /** 各证据组都在锚点附近凑够数。 */
    private static boolean groupsSatisfied(Resolved resolved, List<Evidence> nearby) {
        for (Group group : resolved.profile().groups()) {
            Set<Block> groupBlocks = blocksOf(group.blockIds());
            long count = nearby.stream()
                    .filter(hit -> groupBlocks.contains(hit.block()))
                    .map(hit -> hit.pos())
                    .distinct()
                    .count();
            if (count < group.minimum()) {
                return false;
            }
        }
        return true;
    }

    /** 组内方块 ID 换成注册过的方块；写岔的跳过。 */
    private static Set<Block> blocksOf(List<String> blockIds) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String blockId : blockIds) {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id != null) {
                BuiltInRegistries.BLOCK.getOptional(id)
                        .filter(block -> block != Blocks.AIR)
                        .ifPresent(blocks::add);
            }
        }
        return blocks;
    }

    private static String normalize(String structureId) {
        return structureId.toLowerCase(Locale.ROOT);
    }

    // 每份档案：结构 ID、聚集半径（格）、总量下限、各证据组（组名、组内下限、方块）。
    // 方块组合只能证明"附近符合特征"：土径与露天堆肥桶几乎只有村庄在用，
    // 传送门框架只有要塞的传送间在用，这类组合才当得线索。
    private static Map<String, Profile> profiles() {
        Map<String, Profile> out = new LinkedHashMap<>();
        registerVillage(out);
        registerDesertAndJungleTemples(out);
        registerMonumentAndStronghold(out);
        registerMineshaftAndRuins(out);
        registerNetherAndEnd(out);
        return out;
    }

    /** 村庄：五个变体共用同一套地表特征。 */
    private static void registerVillage(Map<String, Profile> out) {
        Profile village = new Profile("minecraft:village", 64, 8,
                List.of(new Group("village_center", 1,
                                List.of("minecraft:bell", "minecraft:dirt_path")),
                        new Group("village_activity", 2,
                                List.of("minecraft:composter", "minecraft:blast_furnace",
                                        "minecraft:smoker", "minecraft:cartography_table",
                                        "minecraft:brewing_stand", "minecraft:lectern",
                                        "minecraft:stonecutter", "minecraft:loom",
                                        "minecraft:smithing_table", "minecraft:grindstone",
                                        "minecraft:fletching_table", "minecraft:barrel",
                                        "minecraft:white_bed", "minecraft:red_bed",
                                        "minecraft:blue_bed", "minecraft:yellow_bed"))));
        register(out, village,
                "minecraft:village", "minecraft:village_plains", "minecraft:village_desert",
                "minecraft:village_savanna", "minecraft:village_snowy", "minecraft:village_taiga");
    }

    /** 沙漠神殿与丛林神殿：砂岩与陶瓦、苔石与红石机件的成片组合。 */
    private static void registerDesertAndJungleTemples(Map<String, Profile> out) {
        register(out, new Profile("minecraft:desert_pyramid", 20, 18,
                List.of(new Group("pyramid_motif", 4,
                                List.of("minecraft:chiseled_sandstone", "minecraft:cut_sandstone",
                                        "minecraft:orange_terracotta", "minecraft:blue_terracotta")),
                        new Group("sandstone_mass", 14,
                                List.of("minecraft:sandstone", "minecraft:cut_sandstone",
                                        "minecraft:chiseled_sandstone")))),
                "minecraft:desert_pyramid");

        register(out, new Profile("minecraft:jungle_pyramid", 18, 16,
                List.of(new Group("temple_mass", 12,
                                List.of("minecraft:mossy_cobblestone", "minecraft:cobblestone")),
                        new Group("temple_fittings", 3,
                                List.of("minecraft:chiseled_stone_bricks", "minecraft:tripwire",
                                        "minecraft:repeater", "minecraft:redstone_wire")))),
                "minecraft:jungle_pyramid");
    }

    /** 海底神殿与要塞传送间：海晶石成片、传送门框架孤证即线索。 */
    private static void registerMonumentAndStronghold(Map<String, Profile> out) {
        register(out, new Profile("minecraft:ocean_monument", 22, 22,
                List.of(new Group("prismarine_mass", 18,
                                List.of("minecraft:prismarine", "minecraft:prismarine_bricks",
                                        "minecraft:dark_prismarine")),
                        new Group("temple_lighting", 2,
                                List.of("minecraft:sea_lantern", "minecraft:dark_prismarine")))),
                "minecraft:ocean_monument");

        register(out, new Profile("minecraft:stronghold", 12, 1,
                List.of(new Group("portal_frame", 1, List.of("minecraft:end_portal_frame")))),
                "minecraft:stronghold");
    }

    /** 矿道、废弃传送门、沼泽小屋、试炼密室与远古城市：地表与洞穴里凑得出来的组合。 */
    private static void registerMineshaftAndRuins(Map<String, Profile> out) {
        register(out, new Profile("minecraft:mineshaft", 18, 12,
                List.of(new Group("rails", 6, List.of("minecraft:rail")),
                        new Group("timber", 4,
                                List.of("minecraft:oak_fence", "minecraft:oak_planks",
                                        "minecraft:oak_log")))),
                "minecraft:mineshaft");

        register(out, new Profile("minecraft:ruined_portal", 12, 6,
                List.of(new Group("portal_signature", 1,
                                List.of("minecraft:crying_obsidian", "minecraft:gold_block")),
                        new Group("portal_mass", 4,
                                List.of("minecraft:obsidian", "minecraft:netherrack",
                                        "minecraft:crying_obsidian")))),
                "minecraft:ruined_portal");

        register(out, new Profile("minecraft:swamp_hut", 12, 4,
                List.of(new Group("hut_fittings", 2,
                                List.of("minecraft:cauldron", "minecraft:crafting_table")),
                        new Group("hut_timber", 2,
                                List.of("minecraft:spruce_planks", "minecraft:oak_planks")))),
                "minecraft:swamp_hut");

        register(out, new Profile("minecraft:trial_chambers", 24, 2,
                List.of(new Group("trial_signature", 1,
                        List.of("minecraft:trial_spawner", "minecraft:vault")))),
                "minecraft:trial_chambers");

        register(out, new Profile("minecraft:ancient_city", 28, 9,
                List.of(new Group("city_signature", 1, List.of("minecraft:reinforced_deepslate")),
                        new Group("sculk_mass", 8,
                                List.of("minecraft:sculk", "minecraft:sculk_catalyst",
                                        "minecraft:sculk_sensor", "minecraft:sculk_shrieker",
                                        "minecraft:sculk_vein")))),
                "minecraft:ancient_city");
    }

    /** 下界与末地的结构：地狱砖、黑石与紫珀的成片组合。 */
    private static void registerNetherAndEnd(Map<String, Profile> out) {
        // 原版下界要塞的结构 ID 是 minecraft:fortress；旧写法 nether_fortress 也认。
        register(out, new Profile("minecraft:fortress", 18, 18,
                List.of(new Group("brick_mass", 16,
                                List.of("minecraft:nether_bricks", "minecraft:nether_brick_fence",
                                        "minecraft:nether_brick_stairs")),
                        new Group("fortress_fittings", 2,
                                List.of("minecraft:nether_brick_fence", "minecraft:nether_brick_stairs")))),
                "minecraft:fortress", "minecraft:nether_fortress");

        register(out, new Profile("minecraft:bastion_remnant", 18, 13,
                List.of(new Group("gilded_signature", 1, List.of("minecraft:gilded_blackstone")),
                        new Group("blackstone_mass", 12,
                                List.of("minecraft:polished_blackstone_bricks",
                                        "minecraft:cracked_polished_blackstone_bricks",
                                        "minecraft:chiseled_polished_blackstone",
                                        "minecraft:blackstone", "minecraft:polished_blackstone")))),
                "minecraft:bastion_remnant");

        register(out, new Profile("minecraft:end_city", 20, 16,
                List.of(new Group("purpur_mass", 14,
                                List.of("minecraft:purpur_block", "minecraft:purpur_pillar",
                                        "minecraft:purpur_stairs", "minecraft:purpur_slab")),
                        new Group("city_fittings", 2,
                                List.of("minecraft:end_rod", "minecraft:purpur_stairs",
                                        "minecraft:purpur_slab")))),
                "minecraft:end_city");
    }

    /** 一份档案登记到几个结构 ID 下：村庄的五个变体共用同一套地表特征。 */
    private static void register(Map<String, Profile> out, Profile profile, String... structureIds) {
        for (String structureId : structureIds) {
            out.put(structureId, profile);
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * 原版 1.18+ 矿物自然生成带小表：探矿授权的唯一依据。
 *
 * <p>每项记录 [最低生成 Y, 推荐探矿 Y, 最高生成 Y] 与所在维度。推荐探矿 Y 取该矿物
 * 公开生成层数据中暴露率最高的层位（diamond -59、iron 16、copper 48 等），不是内部
 * 参数，也不对模型开放。表只覆盖原版公开数据；表外物品探矿如实拒绝——没有生成带
 * 证据就猜一个下降深度，等于把透视伪装成探索。
 *
 * <p>数据出处：原版 1.18+ 世界生成的矿石分布（三角/均匀分布的公开生成层数据）。
 * 覆盖矿物族按最终物品登记，锭与粗金属变体指向同一条生成带。
 */
public record OreGenerationBand(
        int minY, int prospectY, int maxY, String dimension, List<String> blockIds) {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    private static final Map<String, OreGenerationBand> BANDS = build();

    public boolean matchesDimension(String currentDimension) {
        return dimension.equals(currentDimension);
    }

    /** 单个物品的生成带；表外物品返回 null，调用方必须拒绝探矿而不是猜深度。 */
    public static OreGenerationBand forItem(ResourceLocation itemId) {
        return itemId == null ? null : BANDS.get(itemId.toString());
    }

    /** 一组可替代物品共用第一条已知生成带；全部表外时返回 null。 */
    public static OreGenerationBand forItems(Collection<ResourceLocation> itemIds) {
        for (ResourceLocation id : itemIds) {
            OreGenerationBand band = forItem(id);
            if (band != null) return band;
        }
        return null;
    }

    /** 供编排回执引用的目标方块族（普通矿与深层矿变体一并覆盖）。 */
    public List<net.minecraft.world.level.block.Block> targetBlocks() {
        return blockIds.stream()
                .map(ResourceLocation::parse)
                .map(BuiltInRegistries.BLOCK::get)
                .filter(block -> block != net.minecraft.world.level.block.Blocks.AIR)
                .toList();
    }

    private static Map<String, OreGenerationBand> build() {
        Map<String, OreGenerationBand> bands = new LinkedHashMap<>();
        // 煤：0..320，山体峰值最高；平原探矿用 96 层。
        put(bands, "coal", 0, 96, 320, OVERWORLD, "coal_ore");
        // 铜：-16..112，暴露率峰值 48。
        put(bands, "raw_copper", -16, 48, 112, OVERWORLD, "copper_ore", "deepslate_copper_ore");
        put(bands, "copper_ingot", -16, 48, 112, OVERWORLD, "copper_ore", "deepslate_copper_ore");
        // 铁：-64..320，三角分布峰值 16。
        put(bands, "raw_iron", -64, 16, 320, OVERWORLD, "iron_ore", "deepslate_iron_ore");
        put(bands, "iron_ingot", -64, 16, 320, OVERWORLD, "iron_ore", "deepslate_iron_ore");
        // 金：-64..32，峰值 -16（恶地另有暴露层，不单独建模）。
        put(bands, "raw_gold", -64, -16, 32, OVERWORLD, "gold_ore", "deepslate_gold_ore", "nether_gold_ore");
        put(bands, "gold_ingot", -64, -16, 32, OVERWORLD, "gold_ore", "deepslate_gold_ore", "nether_gold_ore");
        // 红石：-64..15，深板岩层峰值 -58。
        put(bands, "redstone", -64, -58, 15, OVERWORLD, "redstone_ore", "deepslate_redstone_ore");
        // 青金石：-64..64，峰值 0。
        put(bands, "lapis_lazuli", -64, 0, 64, OVERWORLD, "lapis_ore", "deepslate_lapis_ore");
        // 钻石：-64..16，三角分布峰值 -59（岩床之上第一层开始）。
        put(bands, "diamond", -64, -59, 16, OVERWORLD, "diamond_ore", "deepslate_diamond_ore");
        // 绿宝石：山地生成，峰值在 224 附近的尖峰分布。
        put(bands, "emerald", -16, 224, 320, OVERWORLD, "emerald_ore", "deepslate_emerald_ore");
        // 远古残骸：下界 8..119 全域低密度，公开数据推荐 15 层。
        put(bands, "ancient_debris", 8, 15, 119, NETHER, "ancient_debris");
        put(bands, "netherite_scrap", 8, 15, 119, NETHER, "ancient_debris");
        return Map.copyOf(bands);
    }

    private static void put(Map<String, OreGenerationBand> bands, String itemId,
                            int minY, int prospectY, int maxY, String dimension, String... blocks) {
        ResourceLocation id = ResourceLocation.tryParse("minecraft:" + itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalStateException("ore generation band references unknown item: " + itemId);
        }
        bands.put(id.toString(), new OreGenerationBand(
                minY, prospectY, maxY, dimension, List.of(blocks)));
    }

    // ---- 探矿编排决策：纯逻辑，供编排层执行与回归直接核对 ----

    /** 探矿编排的下一步。每一步都携带原因，回执与回归共用同一份事实。 */
    public enum Step {
        /** 授权未开：维持切片 1 的公平空手行为，不下降也不掘进。 */
        UNAUTHORIZED,
        /** 表内无此物品：如实拒绝探矿，不猜测下降深度。 */
        UNKNOWN_BAND,
        /** 生成带在另一维度：拒绝在本维度下降，如实报告维度壁垒。 */
        OTHER_DIMENSION,
        /** 派出下降子任务，目标 = 当前 xz + 推荐探矿 Y。 */
        DESCEND,
        /** 下降完成后派探矿采矿：矿道掘进，边暴露边采。 */
        PROSPECT,
        /** 探矿已执行过：不再重复，交给既有来源推进与终态逻辑。 */
        ALREADY_PROSPECTED
    }

    public record Plan(Step step, OreGenerationBand band) {
        public static final Plan UNAUTHORIZED = new Plan(Step.UNAUTHORIZED, null);
        public static final Plan UNKNOWN_BAND = new Plan(Step.UNKNOWN_BAND, null);
    }

    /**
     * 探矿决策：公平扫描空手后是否、以及如何进入探矿。决策只读请求事实
     * （授权、表、当前维度、已推进到的阶段），不携带任何世界内部状态。
     */
    public static Plan plan(boolean allowProspecting, Collection<ResourceLocation> itemIds,
                            String currentDimension, boolean descendStarted, boolean prospectMineStarted) {
        if (!allowProspecting) return Plan.UNAUTHORIZED;
        if (prospectMineStarted || descendStarted) return new Plan(Step.ALREADY_PROSPECTED, null);
        OreGenerationBand band = forItems(itemIds);
        if (band == null) return Plan.UNKNOWN_BAND;
        if (!band.matchesDimension(currentDimension)) return new Plan(Step.OTHER_DIMENSION, band);
        return new Plan(Step.DESCEND, band);
    }
}

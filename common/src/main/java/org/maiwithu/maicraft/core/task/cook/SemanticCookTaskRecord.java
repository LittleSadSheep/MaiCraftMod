// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.ProductionLineage;
import java.util.Objects;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存加工目标、设备／配方偏好、允许燃料、取材来源和是否允许伤害生物。
 * 保护标签会传给前置取材任务；具体来源估价和炉子操作由 SemanticCookCompanionTask 处理。
 */
public final class SemanticCookTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "cook";
    // 这是整件取物目标的上限；每炉的装料量由执行器另算，不能把大目标误当成一炉最多做多少。
    public static final int MAX_FINAL_COUNT = SemanticAcquireTaskRecord.MAX_FINAL_COUNT;
    public static final int MAX_FUEL_ALTERNATIVES = 64;

    public enum Preference {
        AUTO, FASTEST, PRESERVE_RARE, SMELTING, BLASTING, SMOKING, CAMPFIRE;

        public static Preference parse(String value) {
            if (value == null || value.isBlank()) return AUTO;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "fastest" -> FASTEST;
                case "preserve_rare", "preserve-rare" -> PRESERVE_RARE;
                case "smelting", "furnace" -> SMELTING;
                case "blasting", "blast_furnace", "blast-furnace" -> BLASTING;
                case "smoking", "smoker" -> SMOKING;
                case "campfire", "campfire_cooking" -> CAMPFIRE;
                default -> throw new IllegalArgumentException(
                        "unknown recipe_preference '" + value
                                + "'; expected auto, fastest, preserve_rare, smelting, "
                                + "blasting, smoking or campfire");
            };
        }
    }

    public final ResourceLocation itemId;
    /** 烧炼追的最终主背包数量；公开烹饪绑定增量后变成“起始已有数 + 请求件数”，取物内部的烧炼来源直接给最终数。 */
    public int count;
    /** 公开烹饪请求“再烧几件”时的件数；为 0 表示内部调用沿用最终数量语义。 */
    public int additionalCount;
    /** 本步首次启动时主背包已有的成品数，只在增量语义下有意义。 */
    public int baselineCount;
    public final Preference preference;
    public final List<ResourceLocation> allowedFuelIds;
    public final List<SemanticAcquireTaskRecord.Source> allowedSources;
    public final boolean allowHarm;
    public final List<String> protectedLabels;
    public ProductionLineage productionLineage = ProductionLineage.ROOT;
    public List<ResourceLocation> preferredMaterials = List.of();

    /** 取物任务转入烧炼时保留同一材料倾向，随后燃料与设备的前置取物也继续继承。 */
    public SemanticCookTaskRecord withPreferredMaterials(List<ResourceLocation> materials) {
        preferredMaterials = List.copyOf(materials); return this;
    }

    /** 原料加工可以继续开炉，但每层必须保留此前的成品祖先，避免循环配方和燃料自举无限套娃。 */
    public SemanticCookTaskRecord withProductionLineage(ProductionLineage lineage) {
        productionLineage = Objects.requireNonNull(lineage); return this;
    }

    static {
        TaskFactory.register(SemanticCookTaskRecord.class, SemanticCookCompanionTask::new);
    }

    // 目标物品必须存在；明确指定的每种燃料也必须被普通熔炉燃料规则识别。列表复制并去重，防止调用方后来改动。
    public SemanticCookTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            ResourceLocation itemId,
            int count,
            Preference preference,
            List<ResourceLocation> allowedFuelIds,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            List<String> protectedLabels) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)
                || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            throw new IllegalArgumentException("cook needs a known namespaced item_id");
        }
        this.itemId = itemId;
        this.count = Math.clamp(count, 1, MAX_FINAL_COUNT);
        this.preference = preference == null ? Preference.AUTO : preference;
        LinkedHashSet<ResourceLocation> fuels = new LinkedHashSet<>();
        if (allowedFuelIds != null) {
            for (ResourceLocation id : allowedFuelIds) {
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                        || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                    throw new IllegalArgumentException("allowed_fuels contains an unknown item: " + id);
                }
                if (!AbstractFurnaceBlockEntity.isFuel(
                        new ItemStack(BuiltInRegistries.ITEM.get(id)))) {
                    throw new IllegalArgumentException("allowed_fuels contains a non-fuel item: " + id);
                }
                fuels.add(id);
            }
        }
        if (fuels.size() > MAX_FUEL_ALTERNATIVES) {
            throw new IllegalArgumentException(
                    "allowed_fuels accepts at most " + MAX_FUEL_ALTERNATIVES + " items");
        }
        this.allowedFuelIds = List.copyOf(new ArrayList<>(fuels));
        LinkedHashSet<SemanticAcquireTaskRecord.Source> sources = new LinkedHashSet<>();
        // 总允许使用现货；多段烧炼继承显式COOK许可，循环和深度由跨任务祖先链控制。
        sources.add(SemanticAcquireTaskRecord.Source.INVENTORY);
        if (allowedSources == null || allowedSources.isEmpty()) {
            for (SemanticAcquireTaskRecord.Source source
                    : SemanticAcquireTaskRecord.DEFAULT_SOURCES) {
                sources.add(source);
            }
        } else {
            for (SemanticAcquireTaskRecord.Source source : allowedSources) {
                sources.add(source);
            }
        }
        this.allowedSources = List.copyOf(sources);
        this.allowHarm = allowHarm;
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (protectedLabels != null) {
            for (String label : protectedLabels) {
                if (label != null && !label.isBlank()) labels.add(label.trim());
            }
        }
        if (labels.size() > 64) {
            throw new IllegalArgumentException("protected_labels accepts at most 64 values");
        }
        this.protectedLabels = List.copyOf(labels);
    }

    /** 读出此刻主背包里的成品数量，口径与烧炼任务每刻核对的产物数量相同。 */
    public Map<ResourceLocation, Integer> carriedByItem(LocalPlayer player) {
        return Map.of(itemId, player == null ? 0 : PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(itemId)));
    }

    /**
     * 公开烹饪的 count 表示“再烧几件”：请求件数挪到 additionalCount，最终目标改成起始已有数加请求件数。
     * 起始数由语义步骤首次启动时冻结，重试、暂停和重启沿用同一份，避免重建子任务后又多烧一轮。
     */
    public SemanticCookTaskRecord withAdditionalCount(Map<ResourceLocation, Integer> baseline) {
        if (additionalCount > 0) throw new IllegalStateException("cooking increment is already bound");
        additionalCount = count;
        baselineCount = Math.max(0, baseline.getOrDefault(itemId, 0));
        count = baselineCount + additionalCount;
        return this;
    }

    @Override
    public String describe() {
        // 公开请求按“再烧几件”描述，取物内部的烧炼来源仍说凑到背包总数。
        if (additionalCount > 0) return "再烹饪 " + itemId + " " + additionalCount + " 件（起始已有 " + baselineCount + "）";
        return "烹饪 " + itemId + " 至背包总数 " + count;
    }
}

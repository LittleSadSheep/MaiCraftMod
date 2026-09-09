// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存加工目标、设备／配方偏好、允许燃料、取材来源和是否允许伤害生物。
 * 保护标签会传给前置取材任务；具体来源估价和炉子操作由 SemanticCookCompanionTask 处理。
 */
public final class SemanticCookTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "cook";
    public static final int MAX_FINAL_COUNT = 256;
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
    public final int count;
    public final Preference preference;
    public final List<ResourceLocation> allowedFuelIds;
    public final List<SemanticAcquireTaskRecord.Source> allowedSources;
    public final boolean allowHarm;
    public final List<String> protectedLabels;

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
        // 总允许使用已有背包物品；没有给来源时采用默认来源，但剔除 COOK，避免加工为了原料再递归加工。
        sources.add(SemanticAcquireTaskRecord.Source.INVENTORY);
        if (allowedSources == null || allowedSources.isEmpty()) {
            for (SemanticAcquireTaskRecord.Source source
                    : SemanticAcquireTaskRecord.DEFAULT_SOURCES) {
                if (source != SemanticAcquireTaskRecord.Source.COOK) sources.add(source);
            }
        } else {
            for (SemanticAcquireTaskRecord.Source source : allowedSources) {
                if (source != SemanticAcquireTaskRecord.Source.COOK) sources.add(source);
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

    @Override
    public String describe() {
        return "烹饪 " + itemId + " 至背包总数 " + count;
    }
}

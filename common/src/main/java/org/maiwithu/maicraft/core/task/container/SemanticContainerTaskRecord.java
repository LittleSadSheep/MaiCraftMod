// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存存取目标、物品选择、数量、容器范围与保护地标；复制列表并检查相互冲突的参数。
 * 这里只定义要求，不存菜单槽位，具体选箱子和分配槽位由 SemanticContainerCompanionTask 完成。
 */
public final class SemanticContainerTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "manage_container";
    public static final int DEFAULT_RADIUS = 32;
    public static final int MAX_RADIUS = 64;
    public static final int MAX_COUNT = 4_096;

    public enum Operation {
        DEPOSIT, WITHDRAW, BALANCE;

        public static Operation parse(String value) {
            if (value == null) throw new IllegalArgumentException(
                    "operation must be deposit, withdraw or balance");
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "deposit" -> DEPOSIT;
                case "withdraw" -> WITHDRAW;
                case "balance" -> BALANCE;
                default -> throw new IllegalArgumentException(
                        "operation must be deposit, withdraw or balance");
            };
        }
    }

    public enum Selection {
        NEAREST, UNIQUE;

        public static Selection parse(String value) {
            if (value == null || value.isBlank()) return UNIQUE;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "nearest" -> NEAREST;
                case "unique" -> UNIQUE;
                default -> throw new IllegalArgumentException(
                        "selection must be nearest or unique");
            };
        }
    }

    public final Operation operation;
    public final List<ResourceLocation> itemIds;
    public final ResourceLocation tagId;
    /** Exact amount to move, or null for all matching source items. */
    public final Integer count;
    /** Requested final count on the operation's semantic destination side. */
    public final Integer targetCount;
    public final ResourceLocation blockId;
    public final String landmarkLabel;
    public final Selection selection;
    public final List<String> protectedLabels;
    public final int radius;

    // 明确物品编号组与物品标签只能二选一；具体搬多少与目标数量也不能同时给，balance 必须给目标数量。
    public SemanticContainerTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            Operation operation,
            List<ResourceLocation> itemIds,
            ResourceLocation tagId,
            Integer count,
            Integer targetCount,
            ResourceLocation blockId,
            String landmarkLabel,
            Selection selection,
            List<String> protectedLabels,
            int radius) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.operation = operation == null ? Operation.DEPOSIT : operation;

        LinkedHashSet<ResourceLocation> ids = new LinkedHashSet<>();
        if (itemIds != null) {
            for (ResourceLocation id : itemIds) {
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                        || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                    throw new IllegalArgumentException("item selector contains an unknown item: " + id);
                }
                ids.add(id);
            }
        }
        if (ids.isEmpty() == (tagId == null)) {
            throw new IllegalArgumentException(
                    "provide exactly one selector: item_id/item_ids, or tag");
        }
        this.itemIds = List.copyOf(ids);
        this.tagId = tagId;

        if (count != null && targetCount != null) {
            throw new IllegalArgumentException("count and target_count are mutually exclusive");
        }
        if (count != null && (count < 1 || count > MAX_COUNT)) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_COUNT);
        }
        if (targetCount != null && (targetCount < 0 || targetCount > MAX_COUNT)) {
            throw new IllegalArgumentException(
                    "target_count must be between 0 and " + MAX_COUNT);
        }
        if (this.operation == Operation.BALANCE && targetCount == null) {
            throw new IllegalArgumentException("balance requires target_count");
        }
        this.count = count;
        this.targetCount = targetCount;

        if (blockId != null && !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            throw new IllegalArgumentException("unknown container block_id: " + blockId);
        }
        this.blockId = blockId;
        this.landmarkLabel = landmarkLabel == null || landmarkLabel.isBlank()
                ? null : landmarkLabel.trim();
        // 默认要求能唯一确定容器；允许任选最近的容器时，需要明确选择 NEAREST。
        this.selection = selection == null ? Selection.UNIQUE : selection;

        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (protectedLabels != null) {
            for (String label : protectedLabels) {
                if (label != null && !label.isBlank()) labels.add(label.trim());
            }
        }
        this.protectedLabels = List.copyOf(new ArrayList<>(labels));
        this.radius = Math.clamp(radius, 1, MAX_RADIUS);
    }

    @Override
    public String describe() {
        String selector = tagId != null ? "#" + tagId : itemIds.toString();
        String amount = targetCount != null ? " target=" + targetCount
                : count != null ? " count=" + count : " all";
        return operation.name().toLowerCase(Locale.ROOT) + " " + selector + amount;
    }
}

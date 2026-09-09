// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.trade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存交易目标：背包最终要多少、可选商人类别、允许花哪些物品，以及避开的地标。
 * 没有保存商人编号或报价下标，具体对象由执行任务在已加载世界里选择。
 */
public final class SemanticTradeTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "trade_items";
    public static final int MAX_FINAL_COUNT = 256;
    public static final int DEFAULT_RADIUS = 32;
    public static final int MAX_RADIUS = 64;

    public enum MerchantKind {
        AUTO, VILLAGER, WANDERING_TRADER;

        public static MerchantKind parse(String value) {
            if (value == null || value.isBlank()) return AUTO;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto", "any" -> AUTO;
                case "villager" -> VILLAGER;
                case "wandering_trader", "wandering-trader", "trader" -> WANDERING_TRADER;
                default -> throw new IllegalArgumentException(
                        "merchant_kind must be auto, villager or wandering_trader");
            };
        }
    }

    public final ResourceLocation itemId;
    public final int count;
    public final MerchantKind merchantKind;
    public final List<ResourceLocation> allowedPaymentIds;
    public final List<String> protectedLabels;
    public final int radius;

    static {
        TaskFactory.register(SemanticTradeTaskRecord.class, SemanticTradeCompanionTask::new);
    }

    // 检查目标物品和允许支付的物品都存在，合并重复的支付物品与保护标签；最终数量限制在 1～256。
    public SemanticTradeTaskRecord(
            String toolCallId,
            long deadlineGameTime,
            ResourceLocation itemId,
            int count,
            MerchantKind merchantKind,
            List<ResourceLocation> allowedPaymentIds,
            List<String> protectedLabels,
            int radius) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)
                || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            throw new IllegalArgumentException("trade_items needs a known namespaced item_id");
        }
        this.itemId = itemId;
        this.count = Math.clamp(count, 1, MAX_FINAL_COUNT);
        this.merchantKind = merchantKind == null ? MerchantKind.AUTO : merchantKind;

        LinkedHashSet<ResourceLocation> payments = new LinkedHashSet<>();
        if (allowedPaymentIds != null) {
            for (ResourceLocation id : allowedPaymentIds) {
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)
                        || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                    throw new IllegalArgumentException(
                            "allowed_payment_items contains an unknown item: " + id);
                }
                payments.add(id);
            }
        }
        this.allowedPaymentIds = List.copyOf(payments);

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
        return "交易获得 " + itemId + " 至背包总数 " + count;
    }
}

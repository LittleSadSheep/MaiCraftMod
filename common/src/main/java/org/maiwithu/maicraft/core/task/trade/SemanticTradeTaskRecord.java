// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.trade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.PlayerInv;
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
    /** 交易追的最终主背包数量；公开交易绑定增量后变成“起始已有数 + 请求件数”，取物内部的交易来源直接给最终数。 */
    public int count;
    /** 公开交易请求“再换几件”时的件数；为 0 表示内部调用沿用最终数量语义。 */
    public int additionalCount;
    /** 本步首次启动时主背包已有的目标物品数，只在增量语义下有意义。 */
    public int baselineCount;
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

    /** 读出此刻主背包里的目标物品数量，口径与交易任务每刻核对的产物数量相同。 */
    public Map<ResourceLocation, Integer> carriedByItem(LocalPlayer player) {
        return Map.of(itemId, player == null ? 0 : PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(itemId)));
    }

    /**
     * 公开交易的 count 表示“再换几件”：请求件数挪到 additionalCount，最终目标改成起始已有数加请求件数。
     * 起始数由语义步骤首次启动时冻结，重试、暂停和重启沿用同一份，避免重建子任务后又多买一轮。
     */
    public SemanticTradeTaskRecord withAdditionalCount(Map<ResourceLocation, Integer> baseline) {
        if (additionalCount > 0) throw new IllegalStateException("trade increment is already bound");
        additionalCount = count;
        baselineCount = Math.max(0, baseline.getOrDefault(itemId, 0));
        count = baselineCount + additionalCount;
        return this;
    }

    @Override
    public String describe() {
        // 公开请求按“再换几件”描述，取物内部的交易来源仍说凑到背包总数。
        if (additionalCount > 0) return "交易再获得 " + itemId + " " + additionalCount + " 件（起始已有 " + baselineCount + "）";
        return "交易获得 " + itemId + " 至背包总数 " + count;
    }
}

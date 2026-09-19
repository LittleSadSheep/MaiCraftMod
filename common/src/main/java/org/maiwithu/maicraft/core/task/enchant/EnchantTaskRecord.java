// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.TaskRecord;

/** 固定已有附魔台、单件物品、报价档位和本次成本上限；缺料或结果未知时不自动重复消费。 */
public final class EnchantTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "enchant";
    public final ResourceLocation itemId;
    public final BlockPos table;
    public final int offerTier, maxLevelsSpent, maxLapis;
    private java.util.function.BooleanSupplier submissionBarrier;
    private boolean consumptionReserved;


    public EnchantTaskRecord(String callId, long deadline, ResourceLocation itemId, BlockPos table,
                             int offerTier, int maxLevelsSpent, int maxLapis) {
        super(TOOL_NAME, callId, deadline);
        // 任务单只接受明确可观察的台子和物品；档位是原生三档报价，零预算不会被解释为无限消费。
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId) || BuiltInRegistries.ITEM.get(itemId) == Items.AIR)
            throw new IllegalArgumentException("enchant requires a registered non-air item_id");
        if (table == null || offerTier < 1 || offerTier > 3 || maxLevelsSpent < 0 || maxLevelsSpent > 3 || maxLapis < 0 || maxLapis > 3)
            throw new IllegalArgumentException("enchant requires a table, offer_tier 1..3 and cost limits 0..3");
        this.itemId = itemId; this.table = table.immutable(); this.offerTier = offerTier;
        this.maxLevelsSpent = maxLevelsSpent; this.maxLapis = maxLapis;
    }

    @Override public String describe() { return "在附魔台为一件 " + itemId + " 选择第 " + offerTier + " 档附魔"; }

    // 总任务绑定稳定的一次消费标识；普通内部调用未绑定时不能绕过落盘屏障直接花经验。
    public void submissionBarrier(java.util.function.BooleanSupplier barrier) {
        if (submissionBarrier != null || barrier == null) throw new IllegalStateException("enchantment submission barrier must be bound exactly once");
        submissionBarrier = barrier;
    }

    public boolean prepareNativeConsumptionBoundary() {
        if (submissionBarrier == null) throw new IllegalStateException("enchantment requires a durable submission barrier");
        // 开始保留消费边界就禁止普通自动重试；落盘未结束时继续显示报价，准备好后由工作流重新核验再发按钮。
        consumptionReserved = true;
        return submissionBarrier.getAsBoolean();
    }

    public boolean nativeConsumptionReserved() { return consumptionReserved; }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.core.task.base.NativeConsumptionTaskRecord;

/** 固定已有附魔台、单件物品、报价档位和本次成本上限；缺料或结果未知时不自动重复消费。 */
public final class EnchantTaskRecord extends NativeConsumptionTaskRecord {
    public static final String TOOL_NAME = "enchant";
    public final ResourceLocation itemId;
    public final BlockPos table;
    public final int offerTier, maxLevelsSpent, maxLapis;

    static { TaskFactory.register(EnchantTaskRecord.class, EnchantCompanionTask::new); }

    public EnchantTaskRecord(String callId, long deadline, ResourceLocation itemId, BlockPos table,
                             int offerTier, int maxLevelsSpent, int maxLapis) {
        // 统一原生过程仍保留旧附魔消费命名空间，旧检查点与预约文件继续阻止同一次附魔重发。
        super(TOOL_NAME, callId, deadline, "enchant");
        // 任务单只接受明确可观察的台子和物品；档位是原生三档报价，零预算不会被解释为无限消费。
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId) || BuiltInRegistries.ITEM.get(itemId) == Items.AIR)
            throw new IllegalArgumentException("enchant requires a registered non-air item_id");
        if (table == null || offerTier < 1 || offerTier > 3 || maxLevelsSpent < 0 || maxLevelsSpent > 3 || maxLapis < 0 || maxLapis > 3)
            throw new IllegalArgumentException("enchant requires a table, offer_tier 1..3 and cost limits 0..3");
        this.itemId = itemId; this.table = table.immutable(); this.offerTier = offerTier;
        this.maxLevelsSpent = maxLevelsSpent; this.maxLapis = maxLapis;
    }

    @Override public String describe() { return "在附魔台为一件 " + itemId + " 选择第 " + offerTier + " 档附魔"; }

}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.BlockMenuCompanionTask;
import org.maiwithu.maicraft.core.task.base.BlockMenuFlow;

/** 找背包中的一件可附魔物品 → 不改地形走近已有台子 → 原生开界面 → 一次附魔 → 核验取回并关闭。 */
public final class EnchantCompanionTask extends BlockMenuCompanionTask<EnchantTaskRecord> {
    private EnchantInventory inventory;

    public EnchantCompanionTask(LocalPlayer player, EnchantTaskRecord record) { super(player, record); }

    @Override protected Names names() { return new Names("enchantment_", "enchant", "table"); }
    @Override protected BlockPos targetPos() { return r.table; }
    @Override protected Block targetBlock() { return Blocks.ENCHANTING_TABLE; }

    @Override protected String additionalStartBlocker() {
        // 背包 2x2 合成格被占用时无法安全完成附魔的装料取回；先拒绝，不清空别人的格子。
        for (int slot = 1; slot <= 4; slot++) if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
            return "enchantment_inventory_crafting_grid_in_use";
        }
        return null;
    }

    @Override protected boolean prepareInputs() {
        try { inventory = EnchantInventory.prepare(player, r); return true; }
        catch (IllegalArgumentException unavailable) {
            failIssue(unavailable.getMessage(), unavailable.getMessage().contains("space") ? FailureType.NO_SPACE : FailureType.NO_MATERIAL);
            return false;
        }
    }

    @Override protected boolean isTargetMenu(AbstractContainerMenu menu) { return menu instanceof EnchantmentMenu; }

    @Override protected boolean targetMenuOccupied(AbstractContainerMenu menu) {
        var enchanting = (EnchantmentMenu) menu;
        return !enchanting.getCarried().isEmpty() || !enchanting.getSlot(0).getItem().isEmpty() || !enchanting.getSlot(1).getItem().isEmpty();
    }

    @Override protected boolean targetMenuEmpty(AbstractContainerMenu menu) {
        var enchanting = (EnchantmentMenu) menu;
        return enchanting.getCarried().isEmpty() && enchanting.getSlot(0).getItem().isEmpty() && enchanting.getSlot(1).getItem().isEmpty();
    }

    @Override protected BlockMenuFlow createFlow(AbstractContainerMenu menu) {
        return new EnchantMenuFlow(player, r, (EnchantmentMenu) menu, inventory, r::prepareSubmission);
    }

    @Override protected String boundaryLabel() { return "enchantment opening ended"; }

    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("item_id", r.itemId.toString()); data.put("requested_count", 1); data.put("offer_tier", r.offerTier);
        data.put("max_levels_spent", r.maxLevelsSpent); data.put("max_lapis", r.maxLapis);
        data.put("mechanical_retry_allowed", !r.submissionReserved()); data.put("outcome_uncertain", false);
        if (issue() != null) data.put("issue_code", issue());
        data.putAll(flowData());
        return data;
    }

    @Override protected String successMessage() { return "enchanted one " + r.itemId + ", verified its return and costs, and closed the native menu"; }
    @Override protected String timeoutMessage() { return "enchantment timed out; inspect the recorded quote, consumption boundary and cleanup status before continuing"; }
    @Override protected String cancelledMessage() { return "enchantment interrupted; any submitted consumption is not repeated, and cleanup is reported only as observed"; }
}

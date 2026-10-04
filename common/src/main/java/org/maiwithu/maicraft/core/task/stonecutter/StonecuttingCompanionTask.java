// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.BlockMenuCompanionTask;
import org.maiwithu.maicraft.core.task.base.BlockMenuFlow;

/** 找到背包中的输入材料 → 不改地形走近已有切石机 → 原生开界面 → 选配方切制 → 核验取回并关闭。 */
public final class StonecuttingCompanionTask extends BlockMenuCompanionTask<StonecuttingTaskRecord> {
    private StonecuttingStock stock;

    public StonecuttingCompanionTask(LocalPlayer player, StonecuttingTaskRecord record) { super(player, record); }

    @Override protected Names names() { return new Names("stonecutter_", "stonecutter", "station"); }
    @Override protected BlockPos targetPos() { return r.station; }
    @Override protected Block targetBlock() { return Blocks.STONECUTTER; }

    @Override protected String additionalStartBlocker() { return null; }

    // 先盘点主背包里的原料和现有空位；缺料直接交回事实，不在切石流程里挖石头或建另一台设备。
    @Override protected boolean prepareInputs() {
        try { stock = StonecuttingStock.prepare(player, r.input, r.output, r.count); return true; }
        catch (IllegalArgumentException unavailable) {
            failIssue(unavailable.getMessage(), unavailable.getMessage().contains("space") ? FailureType.NO_SPACE : FailureType.NO_MATERIAL);
            return false;
        }
    }

    @Override protected boolean isTargetMenu(AbstractContainerMenu menu) { return menu instanceof StonecutterMenu; }

    @Override protected boolean targetMenuOccupied(AbstractContainerMenu menu) {
        var stonecutter = (StonecutterMenu) menu;
        return !stonecutter.getCarried().isEmpty() || !stonecutter.getSlot(0).getItem().isEmpty();
    }

    @Override protected boolean targetMenuEmpty(AbstractContainerMenu menu) {
        var stonecutter = (StonecutterMenu) menu;
        return stonecutter.getCarried().isEmpty() && stonecutter.getSlot(0).getItem().isEmpty();
    }

    @Override protected BlockMenuFlow createFlow(AbstractContainerMenu menu) {
        return new StonecutterMenuFlow(player, r, (StonecutterMenu) menu, stock, r::prepareSubmission);
    }

    @Override protected String boundaryLabel() { return "stonecutting opening ended"; }

    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("item_id", r.input.toString()); data.put("output_item_id", r.output.toString());
        data.put("requested_count", r.count);
        data.put("mechanical_retry_allowed", !r.submissionReserved()); data.put("outcome_uncertain", false);
        if (issue() != null) data.put("issue_code", issue());
        data.putAll(flowData());
        return data;
    }

    @Override protected String successMessage() {
        return "cut " + r.count + " " + r.input + " into " + r.output + ", verified returns and closed the native menu";
    }
    @Override protected String timeoutMessage() {
        return "stonecutting timed out; inspect the recorded crafts, consumption boundary and cleanup status before continuing";
    }
    @Override protected String cancelledMessage() {
        return "stonecutting interrupted; any submitted consumption is not repeated, and cleanup is reported only as observed";
    }
}

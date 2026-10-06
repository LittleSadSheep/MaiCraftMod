// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.interact;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EquipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 备双手 -> 完成一次原生加工 -> 核对新增产物 -> 从随身库存补料换工具；目标数按新增产物计，不按点击次数计。 */
public final class UseItemBatchCompanionTask extends AbstractCompanionTask<UseItemBatchTaskRecord> {
    private Task active;
    private TaskRecord activeRecord;
    private TaskResult lastStep;
    private String phase = "prepare_hands";
    private int outputBefore, completedOutput, completedUses;
    private boolean interrupted;

    public UseItemBatchCompanionTask(LocalPlayer player, UseItemBatchTaskRecord record) { super(player, record); }
    @Override protected void onStart() { outputBefore = PlayerInv.count(player.getInventory(), r.output); }

    @Override protected TaskState onTick() {
        // 自救或人工接管可能松开砂纸；把最后一笔真实回执交回，不能在恢复时猜测并重放消耗动作。
        if (interrupted) { fail("item batch was interrupted; inspect completed output and last step before continuing", FailureType.INTERRUPTED); return TaskState.FAILED; }
        if (active != null) return advance();
        if (completedOutput >= r.count) return TaskState.SUCCESS;
        if (PlayerInv.count(player.getInventory(), r.tool) == 0) return missing("tool_exhausted", "no carried tool remains for the next native use");
        if (r.ingredient != null && !player.getOffhandItem().is(r.ingredient)) {
            if (PlayerInv.count(player.getInventory(), r.ingredient) == 0) return missing("ingredient_exhausted", "no carried ingredient remains for the next native use");
            phase = "prepare_ingredient";
            var equip = new EquipTaskRecord(childId(), childDeadline(), r.ingredient, EquipmentSlot.OFFHAND, id(r.ingredient));
            activeRecord = equip; active = new EquipCompanionTask(player, equip); return TaskState.RUNNING;
        }
        // 工具原来在副手时，先把原料交换到副手即可把工具腾回背包/主手；耗尽后选用背包里的下一件同类工具。
        if (r.ingredient != null && !player.getMainHandItem().is(r.tool)) {
            phase = "prepare_tool";
            var equip = new EquipTaskRecord(childId(), childDeadline(), r.tool, EquipmentSlot.MAINHAND, id(r.tool));
            activeRecord = equip; active = new EquipCompanionTask(player, equip); return TaskState.RUNNING;
        }
        phase = "native_use";
        var use = new InteractAtTaskRecord(childId(), childDeadline(), MouseButton.RIGHT, null, -1, r.tool).useHeldItemOnly(r.output);
        activeRecord = use; active = new InteractAtCompanionTask(player, use); return TaskState.RUNNING;
    }

    private TaskState advance() {
        TaskState state = player.level().getGameTime() >= activeRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(active);
        if (state == null) return TaskState.RUNNING;
        lastStep = active.result(state); active = null; activeRecord = null;
        if (state != TaskState.SUCCESS || !lastStep.success()) {
            fail("item batch stopped during " + phase + ": " + lastStep.message(), state == TaskState.TIMEOUT ? FailureType.TIMED_OUT : lastFailure());
            return TaskState.FAILED;
        }
        if (phase.equals("native_use")) {
            // 只累计已完成持用子任务回执里的正库存增量，不拿整批前后总差直接当产量。
            // 单步增量仍按物品类型统计，无法独立排除并发拾取；一次多产会使 completedOutput 超过请求值。
            Object evidence = lastStep.data().get("expected_output");
            int increase = evidence instanceof Map<?, ?> counts && counts.get("observed_increase") instanceof Number n ? n.intValue() : 0;
            if (increase <= 0 || !Boolean.TRUE.equals(lastStep.data().get("native_use_completed"))) {
                fail("native item use has no confirmed output receipt", FailureType.TARGET_LOST); return TaskState.FAILED;
            }
            completedOutput += increase; completedUses++;
        }
        phase = "prepare_hands"; return TaskState.RUNNING;
    }

    private TaskState missing(String stage, String detail) { phase = stage; fail(detail, FailureType.NO_MATERIAL); return TaskState.FAILED; }
    private String childId() { return r.getToolCallId() + ":" + phase + ":" + completedUses; }
    private long childDeadline() { return Math.min(r.getDeadlineGameTime(), player.level().getGameTime() + 1400); }
    private static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }

    @Override public void stop(LocalPlayer companion, StopReason why) {
        // 暂停、抢占和取消都会锁住本批次；恢复后先报告已做数量与最后一步，不自动续做可能已消耗的一次。
        interrupted = true;
        if (active != null) active.stop(companion, why);
        super.stop(companion, why);
    }
    @Override protected void cleanup() {
        // 取消或超时也结算当前持用并松开按键；已获得的产物保留，未知的一次不加入完成计数。
        if (active != null) { lastStep = active.result(TaskState.CANCELLED); active = null; activeRecord = null; }
        super.cleanup();
    }
    /** 进度记分牌：done/total 与 phase 是门卫认的标准键，活跃子任务嵌在 child 键下供
     *  规划心跳上提——批六A/B 的流体接近静默窗里，包装任务顶层不再无话可说。 */
    @Override
    public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>(resultData());
        data.put("done", completedOutput);
        data.put("total", r.count);
        if (active != null) data.put("child", active.progress());
        return data;
    }

    /** 面板行动行的一句话汇报；工具与原料名取物品的本地化名称，子任务在跑时由一线先说话。 */
    @Override public String describeCurrentAction() {
        if (active != null) {
            String deeper = active.describeCurrentAction();
            if (deeper != null) return deeper;
        }
        return switch (phase) {
            case "prepare_tool" -> "正在装备 " + r.tool.getName(ItemStack.EMPTY).getString();
            case "prepare_ingredient" -> "正在装备 " + r.ingredient.getName(ItemStack.EMPTY).getString();
            case "native_use" -> "正在用 " + r.tool.getName(ItemStack.EMPTY).getString() + " 加工 ("
                    + completedOutput + "/" + r.count + ")";
            default -> "正在准备双手";
        };
    }

    @Override protected Map<String, Object> resultData() {
        // completedUses 只数已结清的加工次数；准备双手不计次数，剩余产量用于重新评估而非自动重放原批次。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase); data.put("item_id", id(r.tool)); data.put("expected_output_item_id", id(r.output));
        data.put("target_output_count", r.count); data.put("completed_output_count", completedOutput); data.put("completed_uses", completedUses);
        data.put("remaining_output_count", Math.max(0, r.count - completedOutput)); data.put("output_before", outputBefore);
        data.put("carried_output_now", PlayerInv.count(player.getInventory(), r.output));
        // 失败恢复必须按剩余产量重新评估，不能自动重跑原数量并重复消耗已经加工完成的部分。
        data.put("mechanical_retry_allowed", false);
        data.put("outcome_uncertain", lastStep != null && Boolean.TRUE.equals(lastStep.data().get("outcome_uncertain")));
        if (r.ingredient != null) data.put("ingredient_item_id", id(r.ingredient));
        if (lastStep != null) { data.put("last_step_message", lastStep.message()); data.put("last_step", lastStep.data()); }
        return data;
    }
    @Override protected String successMessage() { return "completed native item batch with " + completedOutput + " confirmed new " + id(r.output); }
    @Override protected String cancelledMessage() { return "item batch cancelled after " + completedOutput + " confirmed new outputs"; }
}

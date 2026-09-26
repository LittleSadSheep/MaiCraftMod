// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.task.interact.UseItemBatchCompanionTask;
import org.maiwithu.maicraft.core.task.interact.UseItemBatchTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** 原生效果由夹具注入，真实批次、装备和持用执行器负责顺序；不把此回归当成 Create 实机产物证明。 */
public final class UseItemBatchTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        runBatch(3, 3, false, false, TaskState.SUCCESS, 3);
        runBatch(1, 3, false, false, TaskState.FAILED, 1);
        runBatch(3, 1, false, false, TaskState.FAILED, 1);
        runBatch(3, 3, true, false, TaskState.FAILED, 1);
        runBatch(3, 3, false, true, TaskState.FAILED, 1);
        System.out.println("UseItemBatchTest: passed");
    }

    /** 从背包准备副手，工具用尽后换快捷栏下一份；缺料、不确定产物和抢占都不能重放消耗。 */
    private static void runBatch(int tools, int ingredients, boolean loseOutput, boolean interrupt, TaskState expected, int produced) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.selected = 0; h.inventory.setItem(0, new ItemStack(Items.HONEY_BOTTLE));
            if (tools > 1) h.inventory.setItem(1, new ItemStack(Items.HONEY_BOTTLE, tools - 1));
            h.inventory.setItem(3, new ItemStack(Items.QUARTZ, ingredients));
            h.inventory.setItem(5, new ItemStack(Items.PAPER, 10));
            h.enableInventoryTransactions(true);
            h.mode.itemUse = p -> {
                check(p.getMainHandItem().is(Items.HONEY_BOTTLE) && p.getOffhandItem().is(Items.QUARTZ), "both hands must be prepared before each use");
                p.getMainHandItem().shrink(1); p.getOffhandItem().shrink(1);
                if (!loseOutput || h.itemUses() == 1) h.inventory.getItem(5).grow(1);
            };
            var task = new UseItemBatchCompanionTask(h.player, new UseItemBatchTaskRecord("batch", 4000, Items.HONEY_BOTTLE, Items.QUARTZ, Items.PAPER, 3));
            task.start(h.player); TaskState state = TaskState.RUNNING; boolean stopped = false;
            for (int tick = 0; tick < 240 && state == TaskState.RUNNING; tick++) {
                if (interrupt && !stopped && ((Number) task.progress().get("completed_output_count")).intValue() == 1) {
                    task.stop(h.player, Task.StopReason.PREEMPTED); stopped = true;
                }
                state = task.tick(h.player); h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen);
            }
            var receipt = task.result(state);
            check(state == expected && ((Number) receipt.data().get("completed_output_count")).intValue() == produced, "batch reports confirmed partial output: " + receipt);
            check(((Number) receipt.data().get("remaining_output_count")).intValue() == 3 - produced, "remaining count is based on new output, not the ten carried items");
            check(h.itemUses() == produced + (loseOutput ? 1 : 0) && h.mode.menuClicks == 1, "each native use and the initial offhand swap occur once");
            check(h.inventory.getItem(5).getCount() == 10 + produced, "actual inventory retains all confirmed products");
            check(Boolean.FALSE.equals(receipt.data().get("mechanical_retry_allowed")), "a partial batch must not replay the original target count automatically");
            // 通知压缩后也保留已做和剩余数量，避免调用者因缺少部分进度而重新提交整批。
            var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", JsonObject.class); compact.setAccessible(true);
            var notice = ((JsonObject) compact.invoke(null, JsonParser.parseString(receipt.toJson()).getAsJsonObject())).getAsJsonObject("data");
            check(notice.get("completed_output_count").getAsInt() == produced && notice.get("remaining_output_count").getAsInt() == 3 - produced,
                    "attention preserves completed and remaining native batch output");
        }
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}

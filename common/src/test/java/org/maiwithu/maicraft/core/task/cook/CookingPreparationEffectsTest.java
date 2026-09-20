// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 已经在备料中产生效果的失败不能当成未开始；纯观察失败仍可以尝试另一份有限方案。 */
public final class CookingPreparationEffectsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        verify(false, false);
        verify(true, false);
        verify(false, true);
        System.out.println("CookingPreparationEffectsTest: passed");
    }

    private static void verify(boolean effects, boolean uncertain) throws Exception {
        try (var world = new CookingTestWorld()) {
            world.inventory(0, 16, 2);
            var task = new SemanticCookCompanionTask(world.game.player, world.request(16));
            task.start(world.game.player);
            task.tick(world.game.player);
            var child = new FailedSupply(effects, uncertain);
            CookingTestWorld.set(task, "activeChild", child);
            CookingTestWorld.set(task, "activeRecord", new TaskRecord("acquire_items", "failed-fuel", 1000) {});
            CookingTestWorld.enumValue(task, "activePurpose", "ACQUIRE_FUEL");
            check(task.tick(world.game.player) == TaskState.RUNNING, "先处理子任务的完整结果");
            check(CookingTestWorld.read(task, "phase").toString().equals(effects || uncertain ? "COMPLETE" : "RESOLVE"),
                    "只有没有发生实际效果的失败可以重新选燃料");
            check(child.results == 1, "子任务只交付一次结果");
            if (effects || uncertain) {
                check(task.tick(world.game.player) == TaskState.FAILED, "发生效果后的备料失败必须保留原原因");
                var data = task.result(TaskState.FAILED).data();
                check(data.get("outcome_uncertain").equals(uncertain), "备料不确定性必须传到父结果");
                if (effects) check(Boolean.TRUE.equals(data.get("effects_started")), "对外保留备料效果");
                else check(!data.containsKey("effects_started"), "没有确认时不能断言备料没有影响库存");
            }
        }
    }

    private static final class FailedSupply implements Task {
        private final boolean effects;
        private final boolean uncertain;
        private int results;
        FailedSupply(boolean effects, boolean uncertain) { this.effects = effects; this.uncertain = uncertain; }
        @Override public TaskState tick(LocalPlayer player) { return uncertain ? TaskState.SUCCESS : TaskState.FAILED; }
        @Override public void stop(LocalPlayer player, StopReason reason) {}
        @Override public TaskResult result(TaskState state) {
            results++;
            if (uncertain) return TaskResult.ok("inventory arrived but another effect remains unconfirmed",
                    Map.of("effects_observed", effects, "outcome_uncertain", true));
            return TaskResult.fail("fuel supply failed", Map.of("effects_observed", effects, "outcome_uncertain", uncertain));
        }
        @Override public String name() { return "failed cooking supply"; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

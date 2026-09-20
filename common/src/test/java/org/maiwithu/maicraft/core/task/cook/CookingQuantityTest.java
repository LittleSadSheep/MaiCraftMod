// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.task.TaskState;

/** 总目标可超过一炉的容量；每炉仍分批处理，已经有 256 件也不能擅自结束 300 件的任务。 */
public final class CookingQuantityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        acquisitionPassesTheWholeGoal();
        confirmedBatchesKeepTheWholeGoal();
        System.out.println("CookingQuantityTest: passed");
    }

    private static void acquisitionPassesTheWholeGoal() throws Exception {
        try (var world = new CookingTestWorld()) {
            world.inventory(256, 44, 6);
            var request = new SemanticAcquireTaskRecord("large-cooking", 100000,
                    List.of(CookingTestWorld.id("iron_ingot")), 300, List.of(Source.COOK), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var parent = new SemanticAcquireCompanionTask(world.game.player, request);
            parent.start(world.game.player);
            // 盘点现货 → 选择烹饪 → 子任务挑配方 → 准备本批；尚未进入原生打开菜单阶段。
            for (int tick = 0; tick < 4; tick++) {
                check(parent.tick(world.game.player) == TaskState.RUNNING, "300 件目标不能在 256 件时结束");
                world.game.nextTick();
            }
            var childRecord = (SemanticCookTaskRecord) CookingTestWorld.read(parent, "activeRecord");
            var child = CookingTestWorld.read(parent, "activeChild");
            check(childRecord != null && childRecord.count == 300, "取物父任务把烹饪最终目标压小，提前结束了子任务");
            check((int) CookingTestWorld.read(child, "batchRaw") == 44, "这一炉只补仍缺的四十四份");
            check(world.game.blockUses() == 0, "准备阶段不应提前点击炉子");
        }
    }

    private static void confirmedBatchesKeepTheWholeGoal() throws Exception {
        try (var world = new CookingTestWorld()) {
            int output = 100, raw = 200, fuel = 25;
            world.inventory(output, raw, fuel);
            var task = new SemanticCookCompanionTask(world.game.player, world.request(300));
            task.start(world.game.player);
            check(task.tick(world.game.player) == TaskState.RUNNING, "先选择实际配方");
            for (int expected : List.of(64, 64, 64, 8)) {
                world.game.nextTick();
                check(task.tick(world.game.player) == TaskState.RUNNING, "仍有缺额时继续准备下一炉");
                int batch = (int) CookingTestWorld.read(task, "batchRaw");
                int batchFuel = (int) CookingTestWorld.read(task, "batchFuel");
                check(batch == expected, "总目标应拆成 64、64、64、8，不能受旧 256 上限影响");
                // 在“本批取回且关闭已确认”的控制器边界提供新库存，单独验证下一批计算，不模拟原生点击确认。
                output += batch; raw -= batch; fuel -= batchFuel;
                world.inventory(output, raw, fuel);
                CookingTestWorld.invoke(task, "finishCleanup");
            }
            check(task.tick(world.game.player) == TaskState.SUCCESS && output == 300,
                    "第四炉完成后才满足原始目标");
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

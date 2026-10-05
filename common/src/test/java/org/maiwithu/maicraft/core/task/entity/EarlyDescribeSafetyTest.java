// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.entity;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineContentsTask;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineContentsTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismFilterTask;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismFilterTaskRecord;

/**
 * 任务入槽到首刻 onStart 之间存在未启动窗口，调试面板每刻沿任务槽轮询行动自述与进度。
 * 阶段类字段若留有 null 窗口，自述里的 switch 拆箱会在客户端 tick 内直接崩溃游戏；
 * 新增 CompanionTask 的阶段字段必须在声明处初始化，本测试守护这一不变量的既有实现。
 */
public final class EarlyDescribeSafetyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var search = new GenericEntitySearchCompanionTask(world.player,
                    new GenericEntitySearchTaskRecord("early-describe", 10_000,
                            List.of(ResourceLocation.withDefaultNamespace("sheep")),
                            GenericEntitySearchTaskRecord.Relation.ANY, 1, 32, false, List.of()));
            // 不调用 onStart，复现任务已入槽、首刻尚未启动的窗口。
            checkNonBlank("entity search", search.describeCurrentAction());
            Map<String, Object> searchProgress = search.progress();
            check("OBSERVE".equals(searchProgress.get("stage")),
                    "未启动窗口的实体搜索进度阶段应为初始观察阶段，实际 " + searchProgress.get("stage"));

            var filter = new MekanismFilterTask(world.player, new MekanismFilterTaskRecord(
                    "early-describe", 10_000, BlockPos.containing(world.player.position()), "minecraft:iron_ingot"));
            checkNonBlank("sorter filter", filter.describeCurrentAction());

            var contents = new MachineContentsTask(world.player, new MachineContentsTaskRecord(
                    "early-describe", 10_000, BlockPos.containing(world.player.position()),
                    "minecraft:iron_ingot", 1));
            checkNonBlank("machine contents", contents.describeCurrentAction());
        }
        System.out.println("EarlyDescribeSafetyTest: passed");
    }

    private static void checkNonBlank(String task, String action) {
        if (action == null || action.isBlank()) {
            throw new AssertionError(task + " 在未启动窗口的行动自述不应为空");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 总装的替身测试：能力清单能在没有任何真实游戏对象的情况下创建并登记；
 * 下达一个目标后控制循环每刻能推进，目标按能力自己的节奏走完。
 * 真实的端到端连通（LLM 一句话 → 角色动手）是实机验收，这里只保证装得上、推得动。
 */
class AssemblyTest {

    @TempDir
    Path tempDir;

    @Test
    void catalogRegistersEveryAssembledAbility() {
        AbilityRegistry registry = AbilityCatalog.create(OfflineCatalog.deps(tempDir));

        Set<String> registered = new LinkedHashSet<>();
        for (var module : registry.all()) {
            registered.add(module.spec().name());
        }
        // 存东西与记地点能力接上了：找容器、界面读数、整堆搬运与挖盖子都有实现方；记地点只改记忆。
        assertEquals(Set.of("use", "eat", "equip", "drop", "obtain", "gather", "deposit",
                "fight", "follow", "wait", "travel", "chat", "remember", "sequence"), registered,
                "清单里的能力要一个不少地登记上");
    }

    @Test
    void assignedGoalAdvancesThroughControlLoopEachTick() {
        var registry = AbilityCatalog.create(OfflineCatalog.deps(tempDir));
        // 手上没有生存需求（替身清单为空），控制循环只推进主任务。
        ControlLoop controlLoop = new ControlLoop(List.of());
        // 下达经目标运行表：LLM 用 execute 派的活走同一条路，目标成为控制循环的主任务。
        // 等待目标：条件是"过了 0 秒"，开工即完成——推进路径走的是真实的任务与控制循环。
        // 目标写能力的完整 ID：目标推进器按 ID 查清单。
        // 控制权交接给空实现：总装测试不接输入层，只验证目标推得动。
        GoalRunTable goals = new GoalRunTable(registry, new InMemoryGoalRunStore(),
                OfflineCatalog.deps(tempDir).memory(), controlLoop, new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() {
                        return true;
                    }

                    @Override public void requestControl() {
                    }
                });
        GoalRunTable.Launch launch = goals.launch(Goal.of("maicraft:wait", null, ParamValues.EMPTY), null);
        assertTrue(!launch.repeated(), "第一次下达要新开一个目标运行");

        // 下达后的第一刻能力做决定、开出任务，随后一刻任务走完：两刻内给结果。
        TaskResult finished = null;
        for (int tick = 0; tick < 3 && finished == null; tick++) {
            ControlLoop.Decision decision = controlLoop.tick(new OfflineTick());
            ControlLoop.Decision.Advanced advanced =
                    assertInstanceOf(ControlLoop.Decision.Advanced.class, decision);
            finished = advanced.finished();
        }
        assertNotNull(finished, "零秒等待在几刻内就该走完");
        assertEquals(TaskResult.Status.DONE, finished.status(),
                "等待完成的结算要如实写清：" + finished.summary());
    }

    /** 离线的一刻：只有游戏刻号，没有角色；等待任务按"角色不在"如实处理。 */
    private record OfflineTick() implements TickContext {
        @Override public long gameTick() {
            return 0;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }
}

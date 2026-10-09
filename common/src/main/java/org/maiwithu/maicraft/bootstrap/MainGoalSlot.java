// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRunner;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;
import org.maiwithu.maicraft.kernel.goal.RemembersPlaces;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 目标主任务槽：宿主（经 MCP 入口）下达的目标从这里落进控制循环，成为角色的主任务。
 *
 * <p>下达即派活：新目标经目标推进器启动后交控制循环换主任务，旧的主任务按"被替换"收尾，
 * 已经插进来的生存需求临时任务不受影响。目标推进器本身是一个任务，控制循环每刻推进它。
 */
public final class MainGoalSlot {

    private static final Logger LOG = LoggerFactory.getLogger(MainGoalSlot.class);

    private final ControlLoop controlLoop;
    private final AbilityRegistry registry;
    private final GoalRunStore store;
    private final RemembersPlaces memory;
    /** 最近一次下达、还在推进的目标推进器；结束与否看运行记录本身。 */
    private GoalRunner running;

    public MainGoalSlot(ControlLoop controlLoop, AbilityRegistry registry,
            GoalRunStore store, RemembersPlaces memory) {
        this.controlLoop = Objects.requireNonNull(controlLoop, "controlLoop");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    /** 下达一个新目标：替换主任务，角色从此为它干活。 */
    public void assign(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        running = GoalRunner.launch(goal, registry, store, memory);
        LOG.info("下达目标 {}（运行 {}），角色开始执行", goal.ability(), running.run().id());
        controlLoop.setMainTask(running);
    }

    /** 最近一次下达的目标推进器；还没下达过时为空。 */
    public Optional<GoalRunner> running() {
        return Optional.ofNullable(running);
    }
}

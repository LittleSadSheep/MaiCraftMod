package org.maiwithu.maicraft.core.task.build;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 把不需要等待游戏变化的相邻阶段接着处理，例如整理完队列后马上进入选目标。
 * 最多连走八步、约四毫秒；阶段不变、任务结束或调用方说需要等待时，立即交回本次更新。
 * 时间只在每步结束后检查，不能打断单步内部的长计算。
 */
final class BuildTickPipeline {
    private BuildTickPipeline() {}

    static <P> TaskState advance(Supplier<P> phase, Supplier<TaskState> step, BooleanSupplier canContinue) {
        return advance(phase, step, canContinue, System::nanoTime);
    }

    static <P> TaskState advance(Supplier<P> phase, Supplier<TaskState> step, BooleanSupplier canContinue,
                                java.util.function.LongSupplier clock) {
        long deadline = clock.getAsLong() + 4_000_000;
        for (int budget = 0; budget < 8; budget++) {
            P before = phase.get();
            TaskState result = step.get();
            if (result != TaskState.RUNNING || before.equals(phase.get()) || !canContinue.getAsBoolean()
                    || clock.getAsLong() >= deadline) return result;
        }
        return TaskState.RUNNING;
    }
}

package org.maiwithu.maicraft.core.task.build;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.maiwithu.maicraft.task.TaskState;

/** Advance bookkeeping phases without inserting empty ticks; physical receipts still yield. */
final class BuildTickPipeline {
    private BuildTickPipeline() {}

    static <P> TaskState advance(Supplier<P> phase, Supplier<TaskState> step, BooleanSupplier canContinue) {
        for (int budget = 0; budget < 8; budget++) {
            P before = phase.get();
            TaskState result = step.get();
            if (result != TaskState.RUNNING || before.equals(phase.get()) || !canContinue.getAsBoolean()) return result;
        }
        return TaskState.RUNNING;
    }
}

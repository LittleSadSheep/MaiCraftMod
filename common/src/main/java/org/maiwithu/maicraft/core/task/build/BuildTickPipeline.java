package org.maiwithu.maicraft.core.task.build;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.maiwithu.maicraft.task.TaskState;

/** Advance bookkeeping phases without inserting empty ticks; physical receipts still yield. */
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

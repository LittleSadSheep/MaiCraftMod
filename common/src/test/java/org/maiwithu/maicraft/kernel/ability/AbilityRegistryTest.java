// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 能力注册：登记能力时同时登记它的任务；同一个能力不能登记两次；能力 ID 形如 maicraft:小写名。 */
class AbilityRegistryTest {

    private record Remember(String name) implements TaskInput {
        @Override public String describe() {
            return "记住" + name;
        }
    }

    // 最小的能力：记住一个地点；登记时顺带登记它的任务。
    private static final class RememberModule implements AbilityModule {
        @Override public AbilitySpec spec() {
            return rememberSpec("maicraft:remember");
        }

        @Override public StepDecision decide(StepContext step) {
            return new StepDecision.Run(new Remember("家"));
        }

        @Override public void registerTasks(TaskFactories factories) {
            factories.register(Remember.class, input -> new Task() {
                @Override public TickResult tick(TickContext context) {
                    return TickResult.finished(TaskResult.done("记住了" + input.name()));
                }

                @Override public void pause() {}

                @Override public TaskResult close(CloseReason reason) {
                    return TaskResult.done("记住了" + input.name());
                }

                @Override public String describe() {
                    return input.describe();
                }
            });
        }
    }

    private static AbilitySpec rememberSpec(String id) {
        return new AbilitySpec(id, "记住一个地点", AbilityDoc.forAbility("remember"), ParamSpec.EMPTY,
                Set.of(TargetKind.HERE), ExecutionMode.MEMORY_ONLY, Set.of(), List.of(), Listing.LISTED);
    }

    @Test
    void registersModuleTogetherWithItsTasks() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(new RememberModule());

        assertTrue(registry.find("maicraft:remember").isPresent());
        assertTrue(registry.taskFactories().supports(Remember.class), "登记能力时必须同时登记它的任务");
        assertEquals("remember", registry.all().getFirst().spec().name());
    }

    @Test
    void rejectsDuplicateRegistration() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(new RememberModule());

        assertThrows(IllegalStateException.class, () -> registry.register(new RememberModule()));
    }

    @Test
    void rejectsMalformedAbilityIds() {
        assertThrows(IllegalArgumentException.class, () -> rememberSpec("remember"));
        assertThrows(IllegalArgumentException.class, () -> rememberSpec("maicraft:Remember"));
        assertThrows(IllegalArgumentException.class, () -> rememberSpec("other:remember"));
    }
}

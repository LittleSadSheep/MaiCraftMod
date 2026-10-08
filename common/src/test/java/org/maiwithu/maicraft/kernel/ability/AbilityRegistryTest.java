// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.IntentAction;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.outcome.Outcome;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.ExecutorRegistry;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskRecord;
import org.maiwithu.maicraft.kernel.task.TaskStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 能力注册：有能力就有执行器；同一个能力不能登记两次；能力 ID 形如 maicraft:小写名。 */
class AbilityRegistryTest {

    private record Remember(String name) implements TaskRecord {
        @Override public String describe() {
            return "记住" + name;
        }
    }

    // 最小的能力：记住一个地点；登记时顺带登记它的执行器。
    private static final class RememberModule implements AbilityModule {
        @Override public AbilityDescriptor descriptor() {
            return rememberDescriptor("maicraft:remember");
        }

        @Override public IntentAction plan(StepContext step) {
            return new IntentAction.Run(new Remember("家"));
        }

        @Override public void executors(ExecutorRegistry registry) {
            registry.register(Remember.class, record -> new Task() {
                @Override public TaskStatus tick(TickContext context) {
                    return TaskStatus.finished(Outcome.done("记住了" + record.name()));
                }

                @Override public void pause() {}

                @Override public Outcome close(CloseReason reason) {
                    return Outcome.done("记住了" + record.name());
                }

                @Override public String describe() {
                    return record.describe();
                }
            });
        }
    }

    private static AbilityDescriptor rememberDescriptor(String id) {
        return new AbilityDescriptor(id, "记住一个地点", ContractText.forAbility("remember"), ParamSpec.EMPTY,
                Set.of(TargetKind.HERE), ExecutionMode.LOCAL_MEMORY, Set.of(), List.of(), Visibility.LISTED);
    }

    @Test
    void registersModuleTogetherWithItsExecutors() {
        AbilityRegistry registry = new AbilityRegistry(new ExecutorRegistry());
        registry.register(new RememberModule());

        assertTrue(registry.find("maicraft:remember").isPresent());
        assertTrue(registry.executors().supports(Remember.class), "登记能力时必须同时登记它的执行器");
        assertEquals("remember", registry.all().getFirst().descriptor().name());
    }

    @Test
    void rejectsDuplicateRegistration() {
        AbilityRegistry registry = new AbilityRegistry(new ExecutorRegistry());
        registry.register(new RememberModule());

        assertThrows(IllegalStateException.class, () -> registry.register(new RememberModule()));
    }

    @Test
    void rejectsMalformedAbilityIds() {
        assertThrows(IllegalArgumentException.class, () -> rememberDescriptor("remember"));
        assertThrows(IllegalArgumentException.class, () -> rememberDescriptor("maicraft:Remember"));
        assertThrows(IllegalArgumentException.class, () -> rememberDescriptor("other:remember"));
    }
}

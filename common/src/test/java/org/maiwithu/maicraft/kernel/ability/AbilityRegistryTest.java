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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 能力注册：登记能力时同时登记它的任务；同一个能力不能登记两次；需要的模组没装就不登记；能力 ID 形如 maicraft:小写名。 */
class AbilityRegistryTest {

    private record Remember(String name) implements TaskInput {
        @Override public String describe() {
            return "记住" + name;
        }
    }

    // 最小的能力：记住一个地点；登记时顺带登记它的任务。
    private static final class RememberModule implements AbilityModule {
        private final Set<RequiredMod> requiredMods;

        RememberModule() {
            this(Set.of());
        }

        RememberModule(Set<RequiredMod> requiredMods) {
            this.requiredMods = requiredMods;
        }

        @Override public AbilitySpec spec() {
            return rememberSpec("maicraft:remember", requiredMods);
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
        return rememberSpec(id, Set.of());
    }

    private static AbilitySpec rememberSpec(String id, Set<RequiredMod> requiredMods) {
        return new AbilitySpec(id, "记住一个地点", AbilityDoc.forAbility("remember"), ParamSpec.EMPTY,
                Set.of(TargetKind.HERE), ExecutionMode.MEMORY_ONLY, requiredMods, List.of(), Listing.LISTED);
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
    void skipsAbilitiesWhoseRequiredModsAreMissing() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> modId.equals("create"));
        registry.register(new RememberModule(Set.of(RequiredMod.of("ae2"), RequiredMod.of("create"))));

        assertTrue(registry.find("maicraft:remember").isEmpty());
        assertTrue(registry.all().isEmpty(), "缺模组的能力不出现在能力列表里");
        assertFalse(registry.taskFactories().supports(Remember.class), "没登记的能力不该留下它的任务");
        assertEquals(List.of("ae2"), registry.missingModsFor("maicraft:remember"));
        assertTrue(registry.missingModsFor("maicraft:nothing").isEmpty());
    }

    @Test
    void registersAbilitiesWhenEveryRequiredModIsInstalled() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> true);
        registry.register(new RememberModule(Set.of(RequiredMod.of("create"))));

        assertTrue(registry.find("maicraft:remember").isPresent());
        assertTrue(registry.missingModsFor("maicraft:remember").isEmpty());
    }

    @Test
    void rejectsMalformedAbilityIds() {
        assertThrows(IllegalArgumentException.class, () -> rememberSpec("remember"));
        assertThrows(IllegalArgumentException.class, () -> rememberSpec("maicraft:Remember"));
        assertThrows(IllegalArgumentException.class, () -> rememberSpec("other:remember"));
    }
}

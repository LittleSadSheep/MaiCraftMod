// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 能力注册表：内核和 MCP 入口认识能力的唯一途径。启动时创建，按清单登记能力，再交给需要它的服务。
 *
 * <p>登记能力时同时登记它的任务，保证"有能力就能创建它的任务"，
 * 不会出现能力列表里有、真去做时却找不到任务的情况。
 *
 * <p>能力需要的联动模组有一个没装，这个能力就不登记：不出现在能力列表里，任务也不登记；
 * 按名字下达时能查到它缺的是哪个模组，回话里说清，而不是只说"没有这个能力"。
 */
public final class AbilityRegistry {
    private final Map<String, AbilityModule> modules = new LinkedHashMap<>();
    /** 因为缺模组没登记的能力：能力 ID → 缺的模组 ID，按字母序。 */
    private final Map<String, List<String>> missingMods = new LinkedHashMap<>();
    private final TaskFactories taskFactories;
    private final Predicate<String> modInstalled;

    /** 不查模组的注册表：当作需要的模组都装了；离线测试与不涉及联动的地方用。 */
    public AbilityRegistry(TaskFactories taskFactories) {
        this(taskFactories, modId -> true);
    }

    /**
     * @param modInstalled 某个模组 ID 装没装；启动时传加载器环境的回答
     */
    public AbilityRegistry(TaskFactories taskFactories, Predicate<String> modInstalled) {
        this.taskFactories = Objects.requireNonNull(taskFactories, "taskFactories");
        this.modInstalled = Objects.requireNonNull(modInstalled, "modInstalled");
    }

    /** 登记一个能力；同一个 ID 重复登记直接报错。需要的模组有一个没装就不登记，只记下缺哪个。 */
    public void register(AbilityModule module) {
        AbilitySpec spec = Objects.requireNonNull(module, "module").spec();
        if (modules.containsKey(spec.id()) || missingMods.containsKey(spec.id())) {
            throw new IllegalStateException("能力 " + spec.id() + " 重复登记");
        }
        List<String> missing = spec.requiredMods().stream().map(RequiredMod::modId)
                .filter(modId -> !modInstalled.test(modId)).sorted().toList();
        if (!missing.isEmpty()) {
            missingMods.put(spec.id(), missing);
            return;
        }
        modules.put(spec.id(), module);
        module.registerTasks(taskFactories);
    }

    /** 清空全部能力与任务登记：能力模块带着创建它的那份现场，现场丢弃时登记一并作废，换现场后重新登记。 */
    public void clearRegistered() {
        modules.clear();
        missingMods.clear();
        taskFactories.clear();
    }

    /** 这个能力因为缺模组没登记时，缺的是哪些模组；登记了的、或根本没有这个能力时为空。 */
    public List<String> missingModsFor(String id) {
        return missingMods.getOrDefault(id, List.of());
    }

    public Optional<AbilityModule> find(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    /** 按登记顺序的全部能力。 */
    public List<AbilityModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    /** 与本注册表配套的任务工厂表。 */
    public TaskFactories taskFactories() {
        return taskFactories;
    }
}

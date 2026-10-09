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

/**
 * 能力注册表：内核和 MCP 入口认识能力的唯一途径。启动时创建，按清单登记能力，再交给需要它的服务。
 *
 * <p>登记能力时同时登记它的任务，保证"有能力就能创建它的任务"，
 * 不会出现能力列表里有、真去做时却找不到任务的情况。
 */
public final class AbilityRegistry {
    private final Map<String, AbilityModule> modules = new LinkedHashMap<>();
    private final TaskFactories taskFactories;

    public AbilityRegistry(TaskFactories taskFactories) {
        this.taskFactories = Objects.requireNonNull(taskFactories, "taskFactories");
    }

    /** 登记一个能力；同一个 ID 重复登记直接报错。 */
    public void register(AbilityModule module) {
        AbilitySpec spec = Objects.requireNonNull(module, "module").spec();
        if (modules.putIfAbsent(spec.id(), module) != null) {
            throw new IllegalStateException("能力 " + spec.id() + " 重复登记");
        }
        module.registerTasks(taskFactories);
    }

    /** 清空全部能力与任务登记：能力模块带着创建它的那份现场，现场丢弃时登记一并作废，换现场后重新登记。 */
    public void clearRegistered() {
        modules.clear();
        taskFactories.clear();
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

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.kernel.task.ExecutorRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 能力注册表：内核和入口认识能力的唯一途径。由启动装配创建并按显式清单登记，再注入给需要它的服务。
 *
 * <p>登记时同时把能力的执行器登记进执行器注册表，保证"有能力就有执行器"，
 * 不会出现 v1 那种只登记了工具、漏登记执行器的情况。
 */
public final class AbilityRegistry {
    private final Map<String, AbilityModule> modules = new LinkedHashMap<>();
    private final ExecutorRegistry executors;

    public AbilityRegistry(ExecutorRegistry executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    /** 登记一个能力；同一个 ID 重复登记直接报错。 */
    public void register(AbilityModule module) {
        AbilityDescriptor descriptor = Objects.requireNonNull(module, "module").descriptor();
        if (modules.putIfAbsent(descriptor.id(), module) != null) {
            throw new IllegalStateException("能力 " + descriptor.id() + " 重复登记");
        }
        module.executors(executors);
    }

    public Optional<AbilityModule> find(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    /** 按登记顺序的全部能力。 */
    public List<AbilityModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    /** 与本注册表配套的执行器注册表。 */
    public ExecutorRegistry executors() {
        return executors;
    }
}

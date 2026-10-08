// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 任务输入类型 → 创建任务的工厂。每个能力在启动时登记自己的任务输入类型和对应的任务。
 *
 * <p>工厂只接收任务输入；任务需要的行为模型与服务，由能力在登记时通过闭包交给它，
 * 这样任务不必去全局单例里取服务。同一种任务输入重复登记直接报错，避免后登记的悄悄覆盖先登记的。
 */
public final class TaskFactories {

    /** 根据一份任务输入创建本次运行用的任务；每次都新建，不复用上一次的进度。 */
    @FunctionalInterface
    public interface Factory<I extends TaskInput> {
        Task create(I input);
    }

    private final Map<Class<? extends TaskInput>, Factory<? extends TaskInput>> factories = new HashMap<>();

    public <I extends TaskInput> void register(Class<I> type, Factory<I> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        if (factories.putIfAbsent(type, factory) != null) {
            throw new IllegalStateException("任务输入 " + type.getSimpleName() + " 已经登记过");
        }
    }

    public boolean supports(Class<? extends TaskInput> type) {
        return factories.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public Task create(TaskInput input) {
        Factory<TaskInput> factory = (Factory<TaskInput>) factories.get(input.getClass());
        if (factory == null) {
            throw new IllegalStateException("任务输入 " + input.getClass().getSimpleName() + " 没有登记对应的任务");
        }
        return factory.create(input);
    }
}

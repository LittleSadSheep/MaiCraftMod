// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 任务单类型 → 执行器工厂。每个能力模块在启动装配时登记自己的任务单与执行器。
 *
 * <p>工厂只接收任务单；执行器需要的行为模型与服务，由能力模块在登记时通过闭包注入，
 * 这样执行器不必去全局单例里取服务。同一种任务单重复登记直接报错，避免后登记的悄悄覆盖先登记的。
 */
public final class ExecutorRegistry {

    /** 根据一张任务单创建本次执行用的执行器；每次都新建，不复用上一次的进度。 */
    @FunctionalInterface
    public interface Factory<R extends TaskRecord> {
        Task create(R record);
    }

    private final Map<Class<? extends TaskRecord>, Factory<? extends TaskRecord>> factories = new HashMap<>();

    public <R extends TaskRecord> void register(Class<R> type, Factory<R> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        if (factories.putIfAbsent(type, factory) != null) {
            throw new IllegalStateException("任务单 " + type.getSimpleName() + " 已经登记过执行器");
        }
    }

    public boolean supports(Class<? extends TaskRecord> type) {
        return factories.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public Task create(TaskRecord record) {
        Factory<TaskRecord> factory = (Factory<TaskRecord>) factories.get(record.getClass());
        if (factory == null) {
            throw new IllegalStateException("任务单 " + record.getClass().getSimpleName() + " 没有登记执行器");
        }
        return factory.create(record);
    }
}

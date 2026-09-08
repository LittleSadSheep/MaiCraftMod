package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把任务记录变成执行对象：{@link TaskRecord} 保存参数和状态，{@link Task} 负责逐 tick 干活。
 *
 * <p>按记录的具体类精确匹配，不会自动寻找父类的执行器。漏注册时返回 {@link UnsupportedTask}，
 * 由它报告失败，避免把客户端 tick 循环一起中断。
 *
 * <p>同一种记录再次注册会覆盖之前的工厂；这里登记的是创建方法，每次执行都会创建任务对象。
 */
public final class TaskFactory {

    /** Builds the {@link Task} that runs a record of the registered type. */
    @FunctionalInterface
    public interface Runner<R extends TaskRecord> {
        Task create(LocalPlayer player, R record);
    }

    private static final Map<Class<? extends TaskRecord>, Runner<? extends TaskRecord>> RUNNERS =
            new ConcurrentHashMap<>();

    private TaskFactory() {}

    /** Register the runner for a concrete record type. Tick-thread + init safe. */
    public static <R extends TaskRecord> void register(Class<R> type, Runner<R> runner) {
        RUNNERS.put(type, runner);
    }

    /** How many record types are currently registered. */
    public static int size() {
        return RUNNERS.size();
    }

    @SuppressWarnings("unchecked")
    public static Task create(LocalPlayer player, TaskRecord record) {
        Runner<TaskRecord> runner = (Runner<TaskRecord>) RUNNERS.get(record.getClass());
        return runner != null ? runner.create(player, record) : new UnsupportedTask(record);
    }
}

package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按任务单的具体类型创建对应执行器，例如挖掘任务单创建挖掘任务。每次都新建执行对象，不复用上一次的动作进度。
 * 没有登记时创建一个会说明“不支持”的任务；创建方法本身抛出的异常仍交给调用者处理。
 */
public final class TaskFactory {

    /**
     * 登记用的创建方法：拿到玩家和任务单，返回这次要执行的任务。
     */
    @FunctionalInterface
    public interface Runner<R extends TaskRecord> {
        Task create(LocalPlayer player, R record);
    }

    private static final Map<Class<? extends TaskRecord>, Runner<? extends TaskRecord>> RUNNERS =
            new ConcurrentHashMap<>();

    private TaskFactory() {}

    /**
     * 登记一种具体任务单的创建方法；再次登记同一种类型会替换先前的方法。
     */
    public static <R extends TaskRecord> void register(Class<R> type, Runner<R> runner) {
        RUNNERS.put(type, runner);
    }

    /**
     * 返回已经登记了多少种任务单，不是当前正在运行多少个任务。
     */
    public static int size() {
        return RUNNERS.size();
    }

    @SuppressWarnings("unchecked")
    public static Task create(LocalPlayer player, TaskRecord record) {
        Runner<TaskRecord> runner = (Runner<TaskRecord>) RUNNERS.get(record.getClass());
        return runner != null ? runner.create(player, record) : new UnsupportedTask(record);
    }
}

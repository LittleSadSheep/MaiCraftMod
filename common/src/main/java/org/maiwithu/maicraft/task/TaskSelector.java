package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * 每次更新先选能执行的紧急自救，再选临时任务、当前任务，最后才选闲置动作。每一组里按登记顺序选第一个。
 * 例如走路时快淹死了，先把换气任务选出来；当前动作何时能安全停下，由 CompanionBrain 接着判断。
 * 当前 CompanionBrain 没有登记闲置动作，这一层只是保留入口。
 */
public final class TaskSelector {

    private TaskSelector() {}

    /**
     * 返回候选执行者；四层都没有可运行任务时返回 {@code null}。
     *
     * @param reflexes 反射,按注册序(先注册的先问)
     * @param sync     同步任务槽的代理,没有则 null
     * @param current  当前任务槽,空则 null
     * @param idle     空闲姿态,按注册序
     */
    public static Task select(List<Task> reflexes, Task sync, Task current,
                              List<Task> idle, LocalPlayer companion) {
        if (reflexes != null) {
            for (Task reflex : reflexes) {
                if (reflex != null && reflex.canRun(companion)) {
                    return reflex;
                }
            }
        }
        if (sync != null && sync.canRun(companion)) {
            return sync;
        }
        if (current != null && current.canRun(companion)) {
            return current;
        }
        if (idle != null) {
            for (Task pose : idle) {
                if (pose != null && pose.canRun(companion)) {
                    return pose;
                }
            }
        }
        return null;
    }
}

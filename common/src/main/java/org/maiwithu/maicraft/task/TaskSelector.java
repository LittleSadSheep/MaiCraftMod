package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * 按固定顺序选出本 tick 想要使用身体的任务：生存反射 → 同步任务槽 → 当前任务槽 → 空闲姿态。
 * 每层检查 canRun，第一个可运行的任务获选；全部不可运行时返回 null。
 *
 * <p>这里只做选择，不暂停任务、不清理输入。CompanionBrain 还会检查当前动作能否安全交接，
 * 因此“选中了更高优先级任务”不一定意味着它本 tick 就能接管身体。
 * 当前 CompanionBrain 没有注册空闲姿态，idle 只是保留的选择入口。
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

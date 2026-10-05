package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * 普通任务先让紧急自救参与选择，再考虑临时、当前与闲置动作；明确可运行的寻死步骤先成为候选，让保护行为暂时让位。
 * 寻死暂停或结束后恢复原选择顺序；实际能否马上交接仍由 CompanionBrain 检查，不能把候选资格当作角色已停止下落。
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
        // 没有持有信息即视为身体已释放：释放窗口反射照常参与。
        return select(reflexes, sync, current, idle, companion, false);
    }

    /**
     * 带持有信息的选择入口：{@code taskHoldsBody} 表示身体此刻被任务槽记录持有。
     * 释放窗口反射（{@link Task#onlyWhenBodyReleased}）在该状态下不参与抢占，
     * 普通自救反射不受影响。
     */
    public static Task select(List<Task> reflexes, Task sync, Task current,
                              List<Task> idle, LocalPlayer companion, boolean taskHoldsBody) {
        // 寻死是显式身体任务：仅在它仍可运行时跳过保护和临时动作，结束或暂停后自然恢复原优先顺序。
        if (current != null && current.suppressesSurvivalReflexes() && current.canRun(companion)) {
            return current;
        }
        if (reflexes != null) {
            for (Task reflex : reflexes) {
                if (reflex == null) continue;
                // 在岗任务把身体留在边缘是它的正当姿态，释放窗口反射不得按秒抢回；
                // 围困窒息这类紧急自救豁免（urgentBodyRescue）仍照常接管。
                if (taskHoldsBody && reflex.onlyWhenBodyReleased() && !reflex.urgentBodyRescue(companion)) continue;
                if (reflex.canRun(companion)) {
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

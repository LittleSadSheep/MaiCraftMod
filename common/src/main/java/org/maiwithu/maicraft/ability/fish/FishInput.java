// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 钓鱼的任务输入：钓到几次收竿，最多钓多久。
 *
 * @param count      钓到几次算完成（垃圾、宝藏也算一次）；0 表示一直钓，直到 LLM 换活或取消
 * @param maxSeconds 最多钓多少秒；0 表示不设总时限（每一竿自身有界，卡住由进度跟踪兜住）
 * @param permissions 这次目标的许可：走去钓位、弄鱼竿、腾背包都按它
 */
record FishInput(int count, long maxSeconds, Permissions permissions) implements TaskInput {

    @Override public String describe() {
        return count > 0 ? "钓 " + count + " 次鱼" : "在附近的水边钓鱼";
    }
}

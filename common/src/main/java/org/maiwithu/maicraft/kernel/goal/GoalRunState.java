// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/** 一次目标推进现在的处境：在推进、在等回答、被暂停，或已经结束。 */
public enum GoalRunState {
    /** 正在逐刻推进。 */
    RUNNING,
    /** 已向 LLM 提问，等它回答后才继续。 */
    AWAITING_ANSWER,
    /** 暂停：重启后恢复的目标运行就是这个状态，要明确解除暂停才继续推进。 */
    PAUSED,
    /** 已结束，结果定死，不再变化。 */
    FINISHED
}

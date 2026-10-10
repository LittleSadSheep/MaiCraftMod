// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次任务书动作的任务输入：做哪一种、对哪个条目、哪条要求或哪份奖励。不可变；
 * 发没发出去、任务书和背包变没变都在任务里。编号在能力做决定时已经统一成大写。
 *
 * @param operation     做哪一种：提交物品、手动勾选、领取奖励
 * @param questId       条目编号
 * @param requirementId 要求编号；提交与勾选时才有
 * @param rewardId      根奖励编号；领奖时才有
 * @param choiceId      选择奖励选的候选编号；不是选择奖励时为 null
 */
record QuestInput(Operation operation, String questId, String requirementId, String rewardId, String choiceId)
        implements TaskInput {

    /** 任务书动作的三种。 */
    enum Operation {
        /** 把身上的东西交给一条"提交物品"的要求。 */
        SUBMIT,
        /** 勾选一条手动完成的要求。 */
        CONFIRM,
        /** 领取一个任务的奖励。 */
        CLAIM;

        /** 中文名，用于面板、日志与结果。 */
        String chinese() {
            return switch (this) {
                case SUBMIT -> "提交";
                case CONFIRM -> "勾选";
                case CLAIM -> "领奖";
            };
        }
    }

    QuestInput {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(questId, "questId");
    }

    @Override
    public String describe() {
        return switch (operation) {
            case SUBMIT -> "向任务书提交：" + questId + " 的要求 " + requirementId;
            case CONFIRM -> "勾选任务书条目 " + questId + " 的要求 " + requirementId;
            case CLAIM -> "领取任务书条目 " + questId + " 的奖励 " + rewardId
                    + (choiceId == null ? "" : "（选候选 " + choiceId + "）");
        };
    }
}

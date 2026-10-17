// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest.spi;

import java.util.List;
import java.util.Objects;

/**
 * 任务书里一个条目现在的样子，只含自己队伍看得见的：能不能开始、每条要求完成没有、每份奖励领没领。
 * quest 能力按它判断"已满足""这条要求不是交物品的""还没解锁"，并在发出请求后对照前后变化结算。
 *
 * @param questId        条目编号（FTB 的 16 位十六进制）
 * @param title          条目标题
 * @param canStart       前置都满足、能开始做
 * @param lockedReason   不能开始的原因（前置没完成、被屏蔽）；能开始时为空字符串
 * @param requirements   要求，按任务书里的顺序
 * @param rewards        这个条目自己的根奖励
 * @param nestedRewardIds 奖池里的子奖励编号：它们不是能单独领的奖励，领时要领根奖励
 */
public record QuestView(String questId, String title, boolean canStart, String lockedReason,
                        List<Requirement> requirements, List<Reward> rewards, List<String> nestedRewardIds) {

    public QuestView {
        Objects.requireNonNull(questId, "questId");
        title = Objects.requireNonNullElse(title, "");
        lockedReason = Objects.requireNonNullElse(lockedReason, "");
        requirements = List.copyOf(requirements);
        rewards = List.copyOf(rewards);
        nestedRewardIds = List.copyOf(nestedRewardIds);
    }

    /**
     * 一条要求。
     *
     * @param id            要求编号
     * @param kind          原生类型，例如 item、checkmark、kill、dimension
     * @param completed     完成了没有
     * @param submittable   有提交按钮：会消耗物品的物品要求、作者开了按钮的自定义要求
     * @param manualCheck   要手动勾选
     * @param acceptedItems 交物品的要求能接受的物品 ID；不是交物品的为空列表
     * @param remaining     还差几件（或几次）；已完成为 0
     * @param howCompleted  这条要求怎么完成的一句话，例如"到 minecraft:the_nether"；给"这条不是交物品的"这类说明用
     */
    public record Requirement(String id, String kind, boolean completed, boolean submittable, boolean manualCheck,
                              List<String> acceptedItems, long remaining, String howCompleted) {
        public Requirement {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            acceptedItems = List.copyOf(acceptedItems);
            howCompleted = Objects.requireNonNullElse(howCompleted, "");
        }
    }

    /**
     * 一份根奖励。
     *
     * @param id        奖励编号
     * @param claimed   领过了
     * @param claimable 现在能领（要求都完成了、还没领）
     * @param choices   选择奖励的候选；不是选择奖励时为空列表
     */
    public record Reward(String id, boolean claimed, boolean claimable, List<Choice> choices) {
        public Reward {
            Objects.requireNonNull(id, "id");
            choices = List.copyOf(choices);
        }

        /** 是不是要先选一个候选的奖励。 */
        public boolean needsChoice() {
            return !choices.isEmpty();
        }
    }

    /** 选择奖励的一个候选：编号与一句说明（什么东西、几件）。 */
    public record Choice(String id, String description) {
        public Choice {
            Objects.requireNonNull(id, "id");
            description = Objects.requireNonNullElse(description, "");
        }
    }
}

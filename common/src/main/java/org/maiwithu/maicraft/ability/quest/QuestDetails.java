// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest;

import java.util.List;

import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 任务书动作的结果细节：动作前后这个条目的样子（每条要求差几件、每份奖励领了没有），以及请求发没发出去。
 *
 * @param entry 条目前后的样子；发出前读不到条目时只有后面一半
 * @param sent  原生请求发出去了没有；没发出去时不存在"没能确认的交互"
 */
record QuestDetails(String entry, boolean sent) implements ResultDetails {

    QuestDetails {
        if (entry == null || entry.isBlank()) throw new IllegalArgumentException("条目的样子不能为空");
    }

    /** 条目此刻的一段人话：每条要求差几件、完成没有，每份奖励领了没有、能不能领。 */
    static String entry(QuestView view) {
        StringBuilder text = new StringBuilder(view.title().isBlank() ? view.questId() : view.title())
                .append("（").append(view.questId()).append("）");
        if (!view.canStart()) text.append("，还不能开始（").append(view.lockedReason()).append("）");
        text.append("；要求：");
        List<QuestView.Requirement> requirements = view.requirements();
        if (requirements.isEmpty()) {
            text.append("（没有）");
        }
        for (QuestView.Requirement requirement : requirements) {
            text.append(requirement.completed() ? "已完成" : "还差 " + requirement.remaining() + " 件").append(' ');
        }
        text.append("；奖励：");
        List<QuestView.Reward> rewards = view.rewards();
        if (rewards.isEmpty()) {
            text.append("（没有）");
        }
        for (QuestView.Reward reward : rewards) {
            text.append(reward.claimed() ? "已领" : reward.claimable() ? "能领" : "还不能领").append(' ');
        }
        return text.toString();
    }
}

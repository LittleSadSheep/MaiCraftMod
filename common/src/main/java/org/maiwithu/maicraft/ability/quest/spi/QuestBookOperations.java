// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest.spi;

import java.util.Optional;

/**
 * 任务书的操作：读自己队伍里一个条目现在的样子，发一次提交、勾选、领奖。
 * quest 能力只认这个接口；FTB 任务的联动入口实现它（读用知识检索那一层的任务书读取接缝，发包用自己的动作接缝）。
 *
 * <p>发送只发一次原生请求，不等结果：发出去之后任务书与背包变没变，由能力逐刻观察、如实结算，不补发。
 * 调用方在本刻有交互机会时才调发送；只用 Java 的类型，模组的类只出现在 NeoForge 模块的读写端。
 */
public interface QuestBookOperations {

    /** 任务书现在能不能用：没装、还没同步到、服务器没有任务书、任务书界面被禁用、队伍被锁，都写明原因。 */
    QuestBookStatus status();

    /** 一个条目现在的样子；编号对不上任何自己看得见的条目时给空。 */
    Optional<QuestView> quest(String questId);

    /** 发一次"提交"：把这条要求要交的物品交上去。发出去了为真，没发出去（联动停用、条目不在了）为假。 */
    boolean submit(String questId, String requirementId);

    /** 发一次"勾选"：手动勾选型的要求。发出去了为真。 */
    boolean confirm(String questId, String requirementId);

    /**
     * 发一次"领奖"。
     *
     * @param choice 选择奖励时选的候选编号；不是选择奖励时为 null
     * @return 发出去了为真
     */
    boolean claim(String questId, String rewardId, String choice);
}

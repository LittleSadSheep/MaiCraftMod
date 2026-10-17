// SPDX-License-Identifier: GPL-3.0-only
/**
 * 任务书能力：对 FTB 任务书里的一个条目做一次原生按钮动作——交物品（submit）、手动勾选（confirm）、
 * 领取奖励（claim）。只做一次、不自动推进、不替 LLM 选奖励。
 *
 * <p>任务书不在世界里，本能力不接受目标对象。读任务书归查资料那一层；本能力只经
 * {@code ability.quest.spi.QuestBookOperations}（联动模组实现）读这一项现在的样子并发一次请求，
 * 之后逐刻看任务书同步与背包如实结算：FTB 对提交没有专门的回应。
 */
package org.maiwithu.maicraft.ability.quest;

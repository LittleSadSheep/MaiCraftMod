// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.List;

/**
 * 动作交回没能确认的交互：点了、游戏却一直没回音的那几下，由做这件事的动作一句一条交回来，
 * 发起它的一方转进任务结果的 unconfirmed。不交回的动作，这些事实只能拼进失败原因里，
 * 读结果的人分不清"点过没成"和"没点过"。
 *
 * <p>和 {@link Action} 放在一起，因为谁的动作都可能有这种事：拿东西的来源（开箱取货、
 * 联动模组的终端取货）由拿到物品的引擎转交，腾地方的合并、存箱、丢弃由背包空间转交。
 */
public interface ReportsUnconfirmed {

    /** 至今记下的没能确认结果的交互，一条一句：从哪拿、要动几件、点了什么、当时看到什么。 */
    List<String> unconfirmedFacts();
}

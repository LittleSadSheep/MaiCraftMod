// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import java.util.List;

/**
 * 动作交回没能确认的交互：点了、游戏却一直没回音的那几下，由做这件事的动作一句一条交回来，
 * 发起它的一方转进任务结果的 unconfirmed。不交回的动作，这些事实只能拼进失败原因里，
 * 读结果的人分不清"点过没成"和"没点过"。
 *
 * <p>给建动作时拿不到任务记账口（{@link TaskRecords}）的一方用：拿东西的来源（开箱取货、
 * 联动模组的终端取货）由来源建出动作，任务的记账口在拿到物品的引擎手里，引擎从这里取了再记进去。
 * 能直接收记账口的动作（存箱子、丢东西、腾地方）不用它。
 */
public interface ReportsUnconfirmed {

    /** 至今记下的没能确认结果的交互，一条一句：从哪拿、要动几件、点了什么、当时看到什么。 */
    List<String> unconfirmedFacts();
}

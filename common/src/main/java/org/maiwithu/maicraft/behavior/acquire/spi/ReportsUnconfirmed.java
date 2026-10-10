// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;

/**
 * 交回没能确认交互的接缝：物品来源的执行动作实现它，把"点了但没能确认结果"的交互
 * 一句一条交回来；拿到物品的引擎把它们转给发起拿东西的一方，写进任务结果的 unconfirmed。
 * 不接这条路的来源，这些事实只能拼进失败原因里，读结果的人分不清"点过没成"和"没点过"。
 *
 * <p>自带的实现是开箱取货的动作；联动模组的取货动作（例如从 ME 终端取货）实现同一个接口。
 */
public interface ReportsUnconfirmed {

    /** 至今记下的没能确认结果的交互，一条一句：从哪拿、要动几件、点了什么、当时看到什么。 */
    List<String> unconfirmedFacts();
}

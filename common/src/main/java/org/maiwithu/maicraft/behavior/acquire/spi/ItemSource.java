// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.kernel.task.Action;

import java.util.Optional;

/**
 * 物品来源：身上、容器、合成、烧炼、采集、采掘、交易……都长一个样子。
 * 来源先被问价（能拿多少、代价、风险、超不超出许可），被选中后再开始执行；
 * 两步之间世界可能变了，执行时以当时的现场为准，数量以执行后的重新清点为准。
 *
 * <p>实现由行为层与联动模组共同提供，启动时明确登记，不用 ServiceLoader。
 */
public interface ItemSource {

    /** 来源的名字，例如"身上的背包""记得的箱子""合成"。 */
    String describe();

    /**
     * 来源属于哪条途径，写法与拿东西的 via 取值一致（见 AcquireRoutes）。
     * via 给了就只走指定途径：引擎按它过滤来源；结果细节 obtained_via 也用它回答东西从哪来的。
     * 一个实现只认一条途径；合成与烧炼是两条途径，分别登记各自的来源实例。
     */
    String route();

    /** 问价：回答能拿到多少、代价、风险；给不了、超出许可或还不支持，都如实回答。 */
    SourceQuote quote(ItemRequest request, SourceContext context);

    /**
     * 按一次被选中的报价开始执行：返回的动作逐刻推进，把物品弄进背包算做完。
     * 问价与动手之间世界可能变了，来源动手前重新看现场；做不成以问题失败，由引擎换下一个来源。
     * 现场已经做不了（设施没了、记忆过时、现场动作没接上）时返回 empty，由引擎换下一个来源。
     */
    Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context);
}

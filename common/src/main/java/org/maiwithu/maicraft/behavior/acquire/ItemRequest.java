// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

/**
 * 一次拿东西的请求：要什么、要多少、拿来做什么。
 *
 * <p>数量统一是"这次要多拿几件"，不是背包里已有的总数；缺多少由引擎按身上的重新清点算。
 * 用途是一句给人看的标签，例如"施工备料""烧炼的燃料"：腾背包时它跟着写进丢弃备注，
 * 结果里 LLM 靠它知道这些东西是为什么拿的。内部来源（工具、原料、燃料）再发请求时也带用途。
 *
 * @param wanted  想要的东西（一种物品或一个标签）
 * @param count   这次要多拿几件，至少为 1
 * @param purpose 用途标签，例如"施工备料"
 */
public record ItemRequest(WantedItem wanted, int count, String purpose) {

    public ItemRequest {
        if (wanted == null) throw new IllegalArgumentException("请求里必须有想要的东西");
        if (count < 1) throw new IllegalArgumentException("要拿的数量至少为 1：" + count);
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("请求必须带用途标签");
    }

    /** 同样要的东西，只要这么几件：还缺多少就问多少，用途不变。 */
    public ItemRequest just(int fewerCount) {
        return new ItemRequest(wanted, fewerCount, purpose);
    }
}

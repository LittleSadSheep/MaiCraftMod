// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireRoute;

/**
 * 玩家行为层自带的几条拿东西途径：每个自带的物品来源自报其中一条。
 * 一个途径一个词，参数 via、来源、结果细节 obtained_via 用同一套写法，LLM 用 via 指路、从结果里认路，两边对得上。
 * 联动模组接入的途径不在这里列：由它们的来源自报，登记了才出现在 via 的可选值里。
 */
public final class AcquireRoutes {

    /** 身上已有的（主背包与副手）。 */
    public static final AcquireRoute CARRIED = new AcquireRoute("carried", "身上已有的（主背包与副手）");
    /** 记得的箱子、现场看到的容器。 */
    public static final AcquireRoute CONTAINER = new AcquireRoute("container", "记得的箱子、现场看到的容器");
    /** 工作台上做出来（含石切台）。 */
    public static final AcquireRoute CRAFT = new AcquireRoute("craft", "工作台上做出来（含石切台）");
    /** 熔炉里烧出来。 */
    public static final AcquireRoute SMELT = new AcquireRoute("smelt", "熔炉里烧出来");
    /** 挖掉会掉出它的方块。 */
    public static final AcquireRoute MINE = new AcquireRoute("mine", "挖掉会掉出它的方块");
    /** 收成熟的作物。 */
    public static final AcquireRoute HARVEST = new AcquireRoute("harvest", "收成熟的作物");
    /** 和村民交易；还没接入，问价一律回答不支持。 */
    public static final AcquireRoute TRADE = new AcquireRoute("trade", "和村民交易（还没接入，问价一律回答不支持）");

    private AcquireRoutes() {}
}

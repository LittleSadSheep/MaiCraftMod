// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

/**
 * 拿东西的途径：via 参数的取值，也是每个物品来源自报的门户。
 * 一个途径一个词，参数、来源、结果细节（obtained_via）用同一套写法，
 * LLM 用 via 指路、从结果里认路，两边对得上。
 */
public final class AcquireRoutes {

    /** 身上已有的（主背包与副手）。 */
    public static final String CARRIED = "carried";
    /** 记得的箱子、现场看到的容器。 */
    public static final String CONTAINER = "container";
    /** 工作台上做出来（含石切台）。 */
    public static final String CRAFT = "craft";
    /** 熔炉里烧出来。 */
    public static final String SMELT = "smelt";
    /** 挖掉会掉出它的方块。 */
    public static final String MINE = "mine";
    /** 收成熟的作物。 */
    public static final String HARVEST = "harvest";
    /** 和村民交易；还没接入，问价一律回答不支持。 */
    public static final String TRADE = "trade";

    private AcquireRoutes() {}
}

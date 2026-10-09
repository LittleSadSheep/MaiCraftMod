// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 拿东西的途径：via 参数的一个取值，加一句给 LLM 看的说明。
 * 每个物品来源自报自己属于哪条途径；拿东西能力的 via 可选值与说明就从登记的来源列表里收集，
 * 玩家行为层自带的几条是这里的常量，联动模组接入的途径（例如随身背包）由它们的来源自报，装了才出现。
 *
 * @param name        取值本身，小写字母与下划线，例如 craft；参数、结果细节 obtained_via 都用它
 * @param description 一句说明，写在能力说明的参数表里，例如"工作台上做出来（含石切台）"
 */
public record AcquireVia(String name, String description) {

    // 取值的写法要先定好，下面的常量在类初始化时就要经它检查。
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    /** 身上已有的（主背包与副手）。 */
    public static final AcquireVia CARRIED = new AcquireVia("carried", "身上已有的（主背包与副手）");
    /** 记得的箱子、现场看到的容器。 */
    public static final AcquireVia CONTAINER = new AcquireVia("container", "记得的箱子、现场看到的容器");
    /** 工作台上做出来（含石切台）。 */
    public static final AcquireVia CRAFT = new AcquireVia("craft", "工作台上做出来（含石切台）");
    /** 熔炉里烧出来。 */
    public static final AcquireVia SMELT = new AcquireVia("smelt", "熔炉里烧出来");
    /** 挖掉会掉出它的方块。 */
    public static final AcquireVia MINE = new AcquireVia("mine", "挖掉会掉出它的方块");
    /** 收成熟的作物。 */
    public static final AcquireVia HARVEST = new AcquireVia("harvest", "收成熟的作物");
    /** 和村民交易；还没接入，问价一律回答不支持。 */
    public static final AcquireVia TRADE = new AcquireVia("trade", "和村民交易（还没接入，问价一律回答不支持）");


    public AcquireVia {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("途径的取值要是小写字母、数字与下划线：" + name);
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("途径 " + name + " 要写一句说明");
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 拿东西的一条途径：via 参数的一个取值，加一句给 LLM 看的说明。
 * 每个物品来源自报自己属于哪条途径；拿东西能力的 via 可选值与说明就从登记的来源列表里收集，
 * 玩家行为层自带的途径（合成、烧炼、容器……）与联动模组接入的途径（例如随身背包）长一个样，装了才出现。
 *
 * @param name        取值本身，小写字母与下划线，例如 craft；参数、结果细节 obtained_via 都用它
 * @param description 一句说明，写在能力说明的参数表里，例如"工作台上做出来（含石切台）"
 */
public record AcquireRoute(String name, String description) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    public AcquireRoute {
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

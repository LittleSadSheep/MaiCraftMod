// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 出行方式的取值：travel 的 via 参数的一个取值，加一句给 LLM 看的说明（和拿东西的途径同一种写法）。
 *
 * @param name        取值本身，小写字母与下划线，例如 elevator；参数与结果细节 legs 都用它
 * @param description 一句说明，写在能力说明的参数表里，例如"坐 Create 电梯换楼层"
 */
public record TravelVia(String name, String description) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    /** 走路：自带的方式，近处与首尾都是它。 */
    public static final TravelVia WALK = new TravelVia("walk", "只走路（挖垫照许可）");

    public TravelVia {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("出行方式的取值要是小写字母、数字与下划线：" + name);
        if (description.isBlank()) throw new IllegalArgumentException("出行方式 " + name + " 要写一句说明");
    }
}

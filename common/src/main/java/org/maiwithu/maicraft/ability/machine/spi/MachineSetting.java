// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Objects;

/**
 * 一台机器的一项设置与它现在的值：Mekanism 的侧面配置、分拣机的一条过滤、置物台的模式……
 * 键和值的写法由机器类型定，能力说明里按已装的模组列出，machine_configure 原样收、原样交回。
 *
 * @param key         设置项，例如 side.north、filter.add
 * @param value       现在的值，例如 output:items；读不到为 null
 * @param choices     可选的值；值的形状不是几个选项之一（例如过滤规则）时为空列表
 * @param description 给 LLM 看的一句说明
 */
public record MachineSetting(String key, String value, List<String> choices, String description) {

    public MachineSetting {
        Objects.requireNonNull(key, "key");
        choices = List.copyOf(choices);
        description = description == null ? "" : description;
    }
}

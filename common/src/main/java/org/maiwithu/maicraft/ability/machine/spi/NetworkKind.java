// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一种网络：取值就是 machine_connect 的 network 可选值，说明写进能力说明的参数表。
 * 和拿东西的途径一样，由登记了的网络读取器自报，装了对应模组才出现。
 *
 * @param id          给 LLM 写的取值，小写字母与下划线，例如 kinetic、me、energy
 * @param description 一句说明，例如"机械动力的应力网络：轴、齿轮连起来的一组"
 */
public record NetworkKind(String id, String description) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*");

    public NetworkKind {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("网络种类只用小写字母、数字与下划线：" + id);
    }
}

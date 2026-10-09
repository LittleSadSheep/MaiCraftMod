// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 支持的模组，也就是联动清单的一行：哪个模组、验证过的版本范围、怎么创建它的联动入口。
 *
 * <p>创建写成 Supplier，是为了把引用模组类的代码推迟到确认装了、版本在范围内之后再加载：
 * 清单类自己的字段、方法签名、静态初始化里都不能出现模组的类，也不能出现直接引用模组类的读写端类型，
 * 只在 Supplier 的 lambda 体里 new。没装那个模组时，这些类一个都不会被加载。
 *
 * @param modId    模组 ID，例如 sophisticatedbackpacks；按它查装没装、装的什么版本
 * @param name     给日志与说明看的名字，例如"精妙背包"
 * @param verified 实测过的版本范围；装的版本不在范围内就不登记
 * @param create   创建联动入口（连同它的读写端）；只在前两项都通过之后调用一次
 */
public record SupportedMod(String modId, String name, VerifiedVersions verified, Supplier<CompatModule> create) {

    public SupportedMod {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(verified, "verified");
        Objects.requireNonNull(create, "create");
        if (modId.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("联动清单的一行要写模组 ID 和名字：" + modId + " / " + name);
        }
    }
}

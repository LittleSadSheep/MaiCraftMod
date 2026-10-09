// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat;

import java.util.List;

import org.maiwithu.maicraft.compat.SupportedMod;

/**
 * 客户端一侧的联动清单：每个支持的模组一行（SupportedMod）——模组 ID、验证过的版本范围、怎么创建读写端与联动入口。
 * 接一个新模组只在这里加一行。
 *
 * <p>没装那个模组时，它的读写端类一个都不能被加载，所以这个类的字段、方法签名和静态初始化里
 * 不出现模组的类，也不出现读写端的类型；读写端只在那一行的 lambda 体里 new，
 * 登记表确认装了、版本在范围内之后才会执行到。
 */
public final class ClientCompatCatalog {

    private ClientCompatCatalog() {}

    /** 清单里支持的全部模组，按接入先后排。现在还没有接任何模组。 */
    public static List<SupportedMod> mods() {
        return List.of();
    }
}

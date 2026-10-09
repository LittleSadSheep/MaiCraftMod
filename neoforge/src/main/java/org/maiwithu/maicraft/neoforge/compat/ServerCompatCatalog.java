// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat;

import java.util.List;

import org.maiwithu.maicraft.compat.SupportedMod;

/**
 * 服务端一侧的联动清单：只有客户端读不到的模组数据（机器状态、网络库存）才需要在服务端接；
 * 独立服务器和单人游戏的内置服务器都按它检查。写法与客户端清单相同，接一个新模组只加一行。
 *
 * <p>这个类同样不能在字段、方法签名和静态初始化里出现模组的类或读写端的类型，只在 lambda 体里 new。
 */
public final class ServerCompatCatalog {

    private ServerCompatCatalog() {}

    /** 清单里支持的全部模组。现在还没有需要服务端读数据的模组。 */
    public static List<SupportedMod> mods() {
        return List.of();
    }
}

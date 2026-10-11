// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat;

import java.util.List;

import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.neoforge.compat.ae2.AppliedEnergisticsGridReads;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ae2.Ae2ServerCompat;

/**
 * 服务端一侧的联动清单：只有客户端读不到的模组数据（机器状态、网络库存）才需要在服务端接；
 * 独立服务器和单人游戏的内置服务器都按它检查。写法与客户端清单相同，接一个新模组只加一行。
 *
 * <p>这个类同样不能在字段、方法签名和静态初始化里出现模组的类或读写端的类型，只在 lambda 体里 new。
 */
public final class ServerCompatCatalog {

    private ServerCompatCatalog() {}

    /** 清单里支持的全部模组：每行创建一个服务端联动入口。 */
    public static List<SupportedMod<ServerCompatModule>> mods() {
        return List.of(
                // 应用能源2：ME 网络的控制器、频道、能量、存储总量只在服务端的网格服务里，经只读操作 ae2.network 回答。
                new SupportedMod<>(Ae2ServerCompat.MOD_ID, "应用能源2", new VerifiedVersions("19.2.17", "19.3"),
                        () -> new Ae2ServerCompat(new AppliedEnergisticsGridReads())));
    }
}

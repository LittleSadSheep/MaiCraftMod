// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat;

import java.util.List;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.backpack.BackpackCompat;
import org.maiwithu.maicraft.compat.ponder.PonderCompat;
import org.maiwithu.maicraft.neoforge.compat.ae2.AppliedEnergisticsTerminalMenu;
import org.maiwithu.maicraft.neoforge.compat.ae2.AppliedEnergisticsTerminals;
import org.maiwithu.maicraft.neoforge.compat.backpack.SophisticatedBackpackItems;
import org.maiwithu.maicraft.neoforge.compat.ponder.PonderClientReads;

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

    /** 清单里支持的全部模组，按接入先后排。 */
    public static List<SupportedMod<CompatModule>> mods() {
        return List.of(
                // 精妙背包：实测过 3.25.69（精妙核心 1.4.72）；装了 3.26 及以上不登记，实测通过后再放宽。
                new SupportedMod<>(BackpackCompat.MOD_ID, "精妙背包", new VerifiedVersions("3.25.69", "3.26"),
                        () -> new BackpackCompat(new SophisticatedBackpackItems())),
                // 应用能源2：实测过 19.2.17；装了 19.3 及以上不登记，实测通过后再放宽。
                new SupportedMod<>(Ae2Compat.MOD_ID, "应用能源2", new VerifiedVersions("19.2.17", "19.3"),
                        () -> new Ae2Compat(new AppliedEnergisticsTerminals(), new AppliedEnergisticsTerminalMenu())),
                // 思索（Ponder）：随 Create 6.0.11 装的 1.0.82；装了 1.0.83 及以上不登记，实测通过后再放宽
                // （读旁白要用它的几个非公开字段，换了版本可能改名）。
                new SupportedMod<>(PonderCompat.MOD_ID, "思索（Ponder）", new VerifiedVersions("1.0.82", "1.0.83"),
                        () -> new PonderCompat(new PonderClientReads())));
    }
}

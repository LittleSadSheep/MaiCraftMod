// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Map;

/**
 * 原版"收哪种庄稼得什么"的常识对照：要收的东西对上田里种的是哪种作物。
 *
 * <p>客户端读不到方块的掉落表，成熟作物的扫描靠这一份原版对照；对照里没有的作物
 * （甘蔗、仙人掌这类不是普通庄稼的，与模组作物）如实回答收不了，不把别的方块当庄稼。
 */
public final class VanillaCropProducts {

    /** 收出来的东西 → 田里的作物方块；四种普通庄稼的对应，按原版玩家种地的常识写。 */
    private static final Map<String, String> PRODUCT_TO_CROP = Map.of(
            "minecraft:wheat", "minecraft:wheat",
            "minecraft:carrot", "minecraft:carrots",
            "minecraft:potato", "minecraft:potatoes",
            "minecraft:beetroot", "minecraft:beetroots");

    private VanillaCropProducts() {}

    /** 收了能拿到这件东西的作物方块类型；对照里没有的给空。 */
    public static List<String> cropsProducing(String itemId) {
        String crop = PRODUCT_TO_CROP.get(itemId);
        return crop == null ? List.of() : List.of(crop);
    }
}

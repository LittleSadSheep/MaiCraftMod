// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

/**
 * 精妙背包的模组读写接缝：认一种物品是不是精妙背包。
 * 只用 Java 类型描述；真正看模组物品类的读写端在 NeoForge 模块里，测试里用替身。
 */
public interface BackpackItems {

    /** 这个物品 ID 是不是精妙背包；皮革、铁、金、钻石、下界合金各种材质的背包都算。 */
    boolean isBackpack(String itemId);
}

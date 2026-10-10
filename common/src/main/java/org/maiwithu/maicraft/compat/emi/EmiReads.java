// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.emi;

import java.util.List;

import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;

/**
 * EMI 的模组读写接缝：EMI 加载完配方没有，以及按物品查它显示的配方。读写端把 EMI 的配方原样翻成项目的配方记录：
 * 类别、工作站、原料（是标签就写标签）、催化剂、产出（EMI 标的几率）、背后游戏配方的原始定义。
 * 只在客户端线程上调用。
 */
public interface EmiReads {

    /** EMI 加载完配方了：进世界后要加载几秒，这段时间索引是空的。 */
    boolean loaded();

    /** EMI 里能做出这件物品的配方。 */
    List<ShownRecipe> making(String itemId);

    /** EMI 里拿这件物品当原料或催化剂的配方。 */
    List<ShownRecipe> using(String itemId);

    /** 这件物品是工作站的那些类别里的全部配方。 */
    List<ShownRecipe> atWorkstation(String itemId);
}

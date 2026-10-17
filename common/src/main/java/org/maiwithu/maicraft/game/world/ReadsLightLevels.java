// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.OptionalInt;

import net.minecraft.core.BlockPos;

/**
 * 只读亮度读取：按格读方块光与天空光。感知的环境光、生存需求的夜晚分流与太暗、照明的量光复测
 * 读的都是同一条游戏事实，全仓只有这一处实现，"没加载不猜"也只定在这里。
 *
 * <p>读法规则一次定死：亮不亮按方块光量——方块光不受昼夜与天气影响，白天量的区域夜里照样成立；
 * 天空光只用来认"头顶被遮住没有"（地下、屋里），读的是不随昼夜变的原始值，露天白天黑夜都是 15。
 * 放光源之后光要过几刻才同步稳定，等几刻再量是调用方的纪律。
 */
public interface ReadsLightLevels {

    /** 这一格现在的方块光（0..15）；格没加载时为空，不猜。 */
    OptionalInt blockLight(BlockPos pos);

    /** 这一格的天空光（0..15，不随昼夜变）：露天是 15，钻进地下、屋里越深越低；没有天空的维度是 0。格没加载时为空。 */
    OptionalInt skyLight(BlockPos pos);
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.OptionalInt;

import net.minecraft.core.BlockPos;

/**
 * 只读亮度读取：按格读方块光。感知的环境光、生存需求的夜晚分流与太暗、照明的量光复测
 * 读的都是同一条游戏事实，全仓只有这一处实现，"没加载不猜"也只定在这里。
 *
 * <p>读法规则一次定死：只读方块光，不读天空光——方块光不受昼夜与天气影响，
 * 白天量的区域夜里照样成立。放光源之后光要过几刻才同步稳定，等几刻再量是调用方的纪律。
 */
public interface ReadsLightLevels {

    /** 这一格现在的方块光（0..15）；格没加载时为空，不猜。 */
    OptionalInt blockLight(BlockPos pos);
}

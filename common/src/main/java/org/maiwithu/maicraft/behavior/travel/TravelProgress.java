// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;

/**
 * 出行进展：逐刻向外面报告的一刻行程观察——走到进展到哪一步、脚下在哪、还差多远、
 * 到目前为止有没有泅渡。只报告观察事实，藏起路线与点击这些执行细节。
 *
 * @param stage        一句话说明此刻在做什么，例如"正在算路"、"在路上"、"到达"
 * @param feet         这一刻脚所在的方块格；正在算路还没有观察时为 null
 * @param remaining    离目的地还差多少；还没有目的地坐标可量时为 null
 * @param crossedWater 这段路到目前为止有没有泅渡
 */
public record TravelProgress(String stage, BlockPos feet, RemainingDistance remaining, boolean crossedWater) {
}

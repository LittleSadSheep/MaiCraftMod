// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 一次跨维度行程的结果细节：到的维度、这趟用的门在哪、换算预期与偏差。
 *
 * <p>主世界进末地的门是单程：回来要先打败末影龙（fight），等主岛中心的出口门亮了再用出行走出口门，
 * 这一事实原样写进细节，让调用方知道这不是走回头路的行程。走出口门回来的那一程落在重生点，没有换算预期。
 *
 * @param arrivedDimension  这次实际到达的维度；没到为目标维度时不写
 * @param portalAt          这次进出用的门的位置（入口列格，按进门那一侧的维度写）；没用上门（没门、建门失败）为 null
 * @param expectedLanding   按维度坐标换算的预期落点（主世界与下界水平 1:8）；这对维度没有换算关系为 null
 * @param landingOffset     实测落点与预期的水平偏差（格）；没有预期为 -1
 * @param oneWay            进的是主世界通末地的门：单程，回来要等打败末影龙、出口门亮了再走出口门
 * @param portalBuiltHow    这趟自己建了门时门是怎么建的：{@code obsidian} 摆黑曜石 / {@code lava_casting} 在岩浆池岸浇筑；没建为 null
 */
public record DimensionTravelDetails(String arrivedDimension, WorldPosition portalAt,
        WorldPosition expectedLanding, int landingOffset, boolean oneWay, String portalBuiltHow) implements ResultDetails {
}

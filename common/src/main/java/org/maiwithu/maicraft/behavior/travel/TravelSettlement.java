// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

import java.util.List;

/**
 * 出行的结果细节：这次行程走到没有、实测位置、没走到时差多远、垫了哪些方块、落地救援的经过、有没有泅渡。
 * 垫上的临时方块不自动收回，只如实列出；要不要收回由玩家决定。
 *
 * @param arrivedAt    实测到达的位置；没走到时为 null
 * @param remaining    没走到时的剩余水平与垂直距离、八向方位；走到时为 null
 * @param placedBlocks 这次垫上的临时方块格子（短坐标写法）；没垫过为空
 * @param landings     这次每次落地救援的经过（放了什么、收没收回、掉没掉血）；没有为空
 * @param trapEscapes  这次每次卡坑自动脱困的经过（没等许可垫方块或挖天然方块出坑，垫了几块、挖开几格）；没有为空
 * @param crossedWater 这段行程有没有泅渡
 */
public record TravelSettlement(WorldPosition arrivedAt, RemainingDistance remaining,
        List<String> placedBlocks, List<String> landings, List<String> trapEscapes, boolean crossedWater)
        implements ResultDetails {

    public TravelSettlement {
        placedBlocks = List.copyOf(placedBlocks);
        landings = List.copyOf(landings);
        trapEscapes = List.copyOf(trapEscapes);
    }
}

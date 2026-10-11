// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 换交通的一趟出行的结果细节：和只走路的出行同样的字段（到了哪、差多远、垫了什么、落地救援、脱困、泅渡，
 * 走路的几段合在一起），外加每段用了什么方式。
 *
 * @param arrivedAt    实测到达的位置；没到时为 null
 * @param remaining    没到时离目的地还差多少；到了为 null
 * @param placedBlocks 走路的几段垫上的临时方块格子
 * @param landings     走路的几段里每次落地救援的经过
 * @param trapEscapes  走路的几段里每次卡坑自动脱困的经过
 * @param crossedWater 走路的几段里有没有泅渡
 * @param legs         每段：方式、起止位置、耗时、结果
 */
public record JourneyDetails(WorldPosition arrivedAt, RemainingDistance remaining, List<String> placedBlocks,
        List<String> landings, List<String> trapEscapes, boolean crossedWater, List<Leg> legs)
        implements ResultDetails {

    public JourneyDetails {
        placedBlocks = List.copyOf(placedBlocks);
        landings = List.copyOf(landings);
        trapEscapes = List.copyOf(trapEscapes);
        legs = List.copyOf(legs);
    }

    /**
     * 一段。
     *
     * @param via    用的方式，例如 walk、elevator
     * @param from   起点（短坐标写法）
     * @param to     停下的位置（短坐标写法）
     * @param ticks  这段用了多少刻
     * @param result 到了，或停在哪、为什么；坐电梯的写上下楼层，坐车的写车名与上下车站
     */
    public record Leg(String via, String from, String to, long ticks, String result) {}
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 走到情况：一刻走到的观察。状态直接回答"现在怎么样"；脚下的方块格与这段路
 * 到目前为止有没有泅渡，交给上层结算剩余距离、方位与行程事实。
 *
 * @param state        走到进展到哪一步
 * @param feet         这一刻脚所在的方块格；还没开始观察（正在算路）时为 null
 * @param crossedWater 这段路到目前为止有没有泅渡
 * @param problem      走不下去的原因；只有失败的状态有
 */
public record WalkReport(State state, BlockPos feet, boolean crossedWater, Problem problem) {

    /** 走到进展到哪一步。正在算路与在路上还在走，到达是成功，已停下与失败是终点。 */
    public enum State {
        /** 正在算路：目标已交给寻路，还没有可走的路线或还没迈出第一步。 */
        PLANNING,
        /** 在路上：正沿着路线走。 */
        ON_THE_WAY,
        /** 到达：身体已按目标规则到位（站实落地，宽松目标允许水中到达）。 */
        ARRIVED,
        /** 已停下：中途停下的请求已完成，停在脚下这一格。 */
        STOPPED,
        /** 失败：走不过去或无法继续，原因见 problem。 */
        FAILED
    }

    public WalkReport {
        if (state == State.FAILED && problem == null) {
            throw new IllegalArgumentException("失败的走到情况必须带上问题");
        }
        if (state != State.FAILED && state != State.PLANNING && feet == null) {
            throw new IllegalArgumentException(state + " 的走到情况必须带脚下的方块格");
        }
    }

    public static WalkReport planning() {
        return new WalkReport(State.PLANNING, null, false, null);
    }

    /** 在路上：feet 是这一刻脚下的方块格。 */
    public static WalkReport onTheWay(BlockPos feet, boolean crossedWater) {
        return new WalkReport(State.ON_THE_WAY, feet, crossedWater, null);
    }

    public static WalkReport arrived(BlockPos feet, boolean crossedWater) {
        return new WalkReport(State.ARRIVED, feet, crossedWater, null);
    }

    public static WalkReport stopped(BlockPos feet, boolean crossedWater) {
        return new WalkReport(State.STOPPED, feet, crossedWater, null);
    }

    /** 失败：feet 是停下时脚下的方块格，还能取到就带上，供上层结算剩余距离。 */
    public static WalkReport failed(Problem problem, BlockPos feet, boolean crossedWater) {
        return new WalkReport(State.FAILED, feet, crossedWater, problem);
    }
}

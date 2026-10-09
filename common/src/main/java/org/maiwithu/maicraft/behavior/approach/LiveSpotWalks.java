// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走向站位的生产实现：把"站进这一格"交给走到（出行轨）逐刻执行。
 *
 * <p>靠近动作只说去哪块格子，走路、算路、卡住都归走到；本类只换算三种推进结果。
 * 走过去途中的地形许可按动土档给：靠近是为了干活，垫挖开路是玩家常识，
 * 能不能挖某一格仍由走到实现方的方块通行判断把关。
 */
public final class LiveSpotWalks implements WalksToSpot {

    private final WalkTo walks;
    private WalkRun walk;

    public LiveSpotWalks(WalkTo walks) {
        this.walks = walks;
    }

    @Override
    public void begin(BlockPos feet) {
        // 换目标前先把上一次的走向停掉，再开始站进新格子。
        stop();
        walk = walks.start(GoalCompiler.standOn(feet), TerrainPermit.NATURAL);
    }

    @Override
    public ActionStatus step(TickContext context) {
        // 站进目标格并落地是做完，还在走是进行中，这条路走不了是失败——三种情况都出自走到情况。
        return walk == null ? ActionStatus.running() : walk.tick(context);
    }

    @Override
    public void stop() {
        if (walk != null) {
            walk.stop();
            walk = null;
        }
    }
}

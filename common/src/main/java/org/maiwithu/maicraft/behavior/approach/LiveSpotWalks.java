// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Objects;

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
 * 每个靠近动作各用一份，路上能动多少地形按这次任务的许可来（不改地形的任务走过去也不挖不垫），
 * 能不能挖某一格仍由走到实现方的方块通行判断按保护把关。
 */
public final class LiveSpotWalks implements WalksToSpot {

    private final WalkTo walks;
    private final TerrainPermit permit;
    private WalkRun walk;

    /** @param permit 这次任务许可折算出的地形许可 */
    public LiveSpotWalks(WalkTo walks, TerrainPermit permit) {
        this.walks = Objects.requireNonNull(walks, "walks");
        this.permit = Objects.requireNonNull(permit, "permit");
    }

    @Override
    public void begin(BlockPos feet) {
        // 换目标前先把上一次的走向收尾、交出身体，新的一趟才上得了路。
        stop();
        walk = walks.start(GoalCompiler.standOn(feet), permit);
    }

    @Override
    public ActionStatus step(TickContext context) {
        // 站进目标格并落地是做完，还在走是进行中，这条路走不了是失败——三种情况都出自走到情况。
        return walk == null ? ActionStatus.running() : walk.tick(context);
    }

    // 被打断：走到自己松键撤路线、保留去处，恢复后接着推进时重新算路。
    @Override
    public void pause() {
        if (walk != null) walk.pause();
    }

    @Override
    public void stop() {
        if (walk != null) {
            walk.close();
            walk = null;
        }
    }
}

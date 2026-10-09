// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
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
    /** 要站进的那一格：被打断后从原地重新上路时还去这里。 */
    private BlockPos feet;
    /** 被打断过：走到停稳后报的"已停下"不是到了，要重新上路。 */
    private boolean paused;

    /** @param permit 这次任务许可折算出的地形许可 */
    public LiveSpotWalks(WalkTo walks, TerrainPermit permit) {
        this.walks = Objects.requireNonNull(walks, "walks");
        this.permit = Objects.requireNonNull(permit, "permit");
    }

    @Override
    public void begin(BlockPos feet) {
        // 换目标前先把上一次的走向收尾、交出身体，新的一趟才上得了路。
        stop();
        this.feet = feet;
        walk = walks.start(GoalCompiler.standOn(feet), permit);
    }

    @Override
    public ActionStatus step(TickContext context) {
        // 站进目标格并落地是做完，还在走是进行中，这条路走不了是失败——三种情况都出自走到情况。
        if (walk == null) return ActionStatus.running();
        ActionStatus status = walk.tick(context);
        if (paused && walk.report().state() == WalkReport.State.STOPPED) {
            // 被打断时走到停稳了、报的是"已停下"：不是到了，从原地重新上路去同一格。
            paused = false;
            walk.close();
            walk = walks.start(GoalCompiler.standOn(feet), permit);
            return ActionStatus.running();
        }
        if (!(status instanceof ActionStatus.Running)) paused = false;
        return status;
    }

    // 被打断：走到自己松键撤路线、保留去处，恢复后接着推进时重新算路。
    @Override
    public void pause() {
        if (walk != null) {
            paused = true;
            walk.pause();
        }
    }

    @Override
    public void stop() {
        if (walk != null) {
            walk.close();
            walk = null;
        }
    }
}

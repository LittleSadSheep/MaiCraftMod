// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 施工交给走到的那几段：地形许可带着"不许跑酷跳"，动土档位与落地办法照任务许可。 */
class LiveTravelsTest {

    /** 记下走到收到的许可的替身，不真走路。 */
    private static final class PermitCapturingWalks implements WalkTo {
        TerrainPermit received;

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            this.received = permit;
            return new StubRun();
        }
    }

    /** 一步都不走的走到运行替身。 */
    private static final class StubRun implements WalkRun {
        @Override public ActionStatus tick(TickContext context) { return ActionStatus.running(); }
        @Override public WalkReport report() { return WalkReport.planning(); }
        @Override public boolean stop() { return true; }
        @Override public String describe() { return "替身走到"; }
    }

    @Test
    void constructionWalksGiveUpParkour() {
        PermitCapturingWalks walks = new PermitCapturingWalks();
        LiveTravels travels = new LiveTravels(walks);

        Optional<Action> started = travels.toward(new WorldPosition(10, 64, 12, "overworld"), Permissions.DEFAULT);

        assertTrue(started.isPresent());
        // 动土档位照任务许可（默认能挖天然方块），但跑酷跳必须关掉：脚手架、墙头上一步一步挪。
        assertTrue(walks.received.mayChangeTerrain());
        assertFalse(walks.received.mayParkour());
    }
}

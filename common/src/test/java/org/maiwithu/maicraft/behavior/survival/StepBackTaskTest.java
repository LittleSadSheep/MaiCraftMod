// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 退离边沿：真退到了落点算做成；一插进来就已经站在落点上（退这一步什么都没变）算没退成，让控制循环缓一阵。 */
class StepBackTaskTest {

    private static final TickContext TICK = new TickContext() {
        @Override public long gameTick() {
            return 0;
        }

        @Override public PlayerContext player() {
            return null;
        }
    };

    /** 站在哪就是哪，走不走都不挪。 */
    private static StepBackTask.Moves standingAt(double x, double z) {
        return new StepBackTask.Moves() {
            @Override public Action walkTo(double tx, double ty, double tz) {
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "替身走到";
                    }
                };
            }

            @Override public double[] selfPosition(TickContext context) {
                return new double[] {x, 64, z};
            }
        };
    }

    @Test
    void 一插进来就已经在落点上_算没退成() {
        // 挖楼梯时落点旁边另有一道深落差：原先每刻插一次、立刻"站回了安全处"，一秒刷二十条。
        StepBackTask task = new StepBackTask(new double[] {10.5, 64, 10.5}, standingAt(10.6, 10.4), null);
        task.start(TICK);
        TickResult.Finished finished = assertInstanceOf(TickResult.Finished.class, task.tick(TICK));
        assertNotEquals(TaskResult.Status.DONE, finished.result().status());
    }
}

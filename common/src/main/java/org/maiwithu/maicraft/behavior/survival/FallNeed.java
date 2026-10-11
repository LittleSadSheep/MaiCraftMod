// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.landing.LandingRescue;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 落地防护这项生存需求：意外往下掉、预计落地会掉血（或下面是虚空）时立刻处理，
 * 派落地防护临时任务就地找落点、用身上带着的东西接住自己。走路自己在管这一掉时让开。
 */
public final class FallNeed implements SurvivalNeed {

    private final SurvivalSituation.SituationReader reader;
    /** 就地找落点与办法，交给一次空中救援；救不了为 null。 */
    private final Function<PlayerContext, LandingRescue> rescues;
    /** 走路自己正在跑落地救援。 */
    private final BooleanSupplier walkHandlesFall;

    public FallNeed(SurvivalSituation.SituationReader reader, Function<PlayerContext, LandingRescue> rescues,
                    BooleanSupplier walkHandlesFall) {
        this.reader = reader;
        this.rescues = rescues;
        this.walkHandlesFall = walkHandlesFall;
    }

    @Override public String name() { return "落地防护"; }

    @Override
    public Urgency urgency(TickContext context) {
        // 走路里的落地救援正在管这一掉（出发点备好了料、空中等着放）：这里让开，免得两边抢着放水。
        if (walkHandlesFall.getAsBoolean()) return null;
        SurvivalSituation situation = reader.read(context);
        return situation == null ? null : FallDanger.assess(situation);
    }

    @Override
    public Task createTask(TickContext context) {
        return new FallGuardTask(reader, rescues);
    }
}

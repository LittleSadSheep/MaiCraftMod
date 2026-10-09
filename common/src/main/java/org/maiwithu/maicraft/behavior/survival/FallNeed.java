// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.function.Function;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 落地防护这项生存需求：这一掉会摔死（或下面是虚空）时立刻处理，
 * 派落地防护临时任务换上水桶、等落点够得着就倒水，用落进水里抵掉这次坠落。
 */
public final class FallNeed implements SurvivalNeed {

    private final SurvivalSituation.SituationReader reader;
    private final Interactions interactions;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    private final Function<PlayerContext, BackpackView> backpacks;

    public FallNeed(SurvivalSituation.SituationReader reader, Interactions interactions,
                    Function<PlayerContext, FirstPersonScene> scenes,
                    Function<PlayerContext, BackpackView> backpacks) {
        this.reader = reader;
        this.interactions = interactions;
        this.scenes = scenes;
        this.backpacks = backpacks;
    }

    @Override public String name() { return "落地防护"; }

    @Override
    public Urgency urgency(TickContext context) {
        SurvivalSituation situation = reader.read(context);
        return situation == null ? null : FallDanger.assess(situation);
    }

    @Override
    public Task createTask(TickContext context) {
        return new FallGuardTask(reader, interactions, scenes, backpacks);
    }
}

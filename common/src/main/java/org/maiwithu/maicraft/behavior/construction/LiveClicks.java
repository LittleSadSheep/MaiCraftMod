// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 出手的生产实现：放方块是对着被点那一面的薄框瞄准再右键（交互动作自己转头、核对命中、提交、逐刻确认）；
 * 倒桶与舀水是朝那一格使用手里的物品；开关门是右键那一格。被点的方块会开界面时整个动作里按着潜行。
 */
public final class LiveClicks implements ConstructionSeams.Clicks {

    private final Interactions interactions;
    private final InputDriver inputs;
    private final Supplier<PlayerContext> context;

    public LiveClicks(Interactions interactions, InputDriver inputs, Supplier<PlayerContext> context) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.inputs = inputs;
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak, PlacementConfirmation confirmation) {
        AimAndInteract use = interactions.useBlockPart(placement.clicked(), PlacementSpots.faceBox(placement.clicked(), placement.face()), confirmation);
        return new Click(sneak && inputs != null ? new Sneaking(use) : use, use::result);
    }

    @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
        AimAndInteract use = interactions.useHeldItemToward(cell.pos(), InteractionHand.MAIN_HAND, confirmation);
        return new Click(use, use::result);
    }

    @Override public Click scoop(BlockPos source, InteractionConfirmation confirmation) {
        AimAndInteract use = interactions.useHeldItemToward(source, InteractionHand.MAIN_HAND, confirmation);
        return new Click(use, use::result);
    }

    @Override public Click use(BlockPos block, InteractionConfirmation confirmation) {
        AimAndInteract use = interactions.useBlock(block, confirmation);
        return new Click(use, use::result);
    }

    /** 整个动作里每刻按着潜行；结束时松开。被点的箱子、工作台才不会把右键截走开界面。 */
    private final class Sneaking implements Action {
        private final Action inner;

        Sneaking(Action inner) {
            this.inner = inner;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext current = context.get();
            if (current != null && current.localPlayer() != null) inputs.sneak(current.localPlayer(), true);
            return inner.tick(tick);
        }

        @Override public void pause() {
            inner.pause();
        }

        @Override public void close() {
            PlayerContext current = context.get();
            if (current != null && current.localPlayer() != null) inputs.sneak(current.localPlayer(), false);
            inner.close();
        }

        @Override public Interruptibility interruptibility() {
            return inner.interruptibility();
        }

        @Override public String describe() {
            return "潜行着" + inner.describe();
        }
    }
}

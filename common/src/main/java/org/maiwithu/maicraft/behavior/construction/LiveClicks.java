// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.LiveHoldsSneak;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 出手的生产实现：放方块是对着被点那一面的薄框瞄准再右键（交互动作自己转头、核对命中、提交、逐刻确认）；
 * 倒桶与舀水是朝那一格使用手里的物品；开关门是右键那一格。被点的方块会开界面时整个动作里按着潜行。
 */
public final class LiveClicks implements ConstructionSeams.Clicks {

    private final Interactions interactions;
    /** 按住潜行：没有角色输入（离线测试）时为 null，这时照常点、不潜行。 */
    private final HoldsSneak sneak;

    public LiveClicks(Interactions interactions, InputDriver inputs, Supplier<PlayerContext> context) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.sneak = inputs == null ? null : new LiveHoldsSneak(inputs, Objects.requireNonNull(context, "context"));
    }

    @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak, PlacementConfirmation confirmation) {
        AimAndInteract use = interactions.useBlockPart(placement.clicked(), PlacementSpots.faceBox(placement.clicked(), placement.face()), confirmation);
        // 被点的箱子、工作台会把右键截走开界面：整个动作里按着潜行，手里的方块才放得下去。
        return new Click(sneak && this.sneak != null ? this.sneak.sneaking(use) : use, use::result);
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
}

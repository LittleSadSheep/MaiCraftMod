// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 寻死的动作：走过去用共用的走到，踏进危险与跟怪站着直接按键，拿到手上用共用的换手，
 * 点火与倒岩浆用共用的瞄准—交互—确认。
 */
final class LiveSuicideMoves implements SuicideMoves {

    private final Supplier<PlayerContext> context;
    private final WalkTo walks;
    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;

    LiveSuicideMoves(Supplier<PlayerContext> context, WalkTo walks, Interactions interactions,
            ClientMovesToMainhand toMainhand) {
        this.context = Objects.requireNonNull(context, "context");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
    }

    @Override public Action walkTo(BlockPos stand, Permissions permissions) {
        return walks.start(GoalCompiler.standOn(stand), TerrainPermit.of(permissions));
    }

    @Override public Action stepInto(DeathSpot spot) {
        return new HazardStep(context, spot.way(), spot.stand(), spot.entry());
    }

    @Override public Action keepNear(int mobId, String mobType, Permissions permissions) {
        return new MobApproach(context, walks, permissions, mobId, mobType);
    }

    @Override public Action standIn(BlockPos cell) {
        return new HazardStep(context, null, cell, cell);
    }

    @Override public Optional<Action> hold(String itemId) {
        return toMainhand.actionToMainhand(itemId);
    }

    // 站在格里低头：点火对准脚下方块的顶面右键，火落在脚下这一格；倒岩浆朝这一格用桶，桶的射线落在脚下顶面，
    // 岩浆倒进站着的这一格。确认看这一格出现了火，或手上的桶空了、这一格变了。
    @Override public Action makeHazard(WayToDie way, BlockPos cell) {
        if (way == WayToDie.FIRE) {
            return interactions.useBlockFace(cell.below(), Direction.UP, InteractionConfirmation.ignitionWorldEffect(cell));
        }
        PlayerContext current = context.get();
        ItemStack handBefore = current == null || current.localPlayer() == null ? ItemStack.EMPTY
                : current.localPlayer().getMainHandItem().copy();
        return interactions.useHeldItemToward(cell, InteractionHand.MAIN_HAND, InteractionConfirmation.anyOf(
                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, handBefore),
                InteractionConfirmation.blockChanged(cell, Blocks.AIR.defaultBlockState())));
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.interaction.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 放置预测的生产实现：从角色现在的眼睛位置找被点那一面看得见的点，算出看向它的角度，
 * 再用原版的放置规则试算这一下会放成什么。只算不动手；真的转头与点击交给交互动作。
 */
public final class LivePlacements implements ConstructionSeams.PlansPlacement {

    private final Supplier<PlayerContext> context;

    public LivePlacements(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) return Optional.empty();
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = FirstPersonInteractionTargeting.visibleBlockHit(level, player, eye, clicked, InteractionRange.blockReach(player), face);
        if (hit == null) return Optional.empty();
        // 看向命中点的角度：和交互动作转头后的真实视角一致，原版按它决定朝向。
        Vec3 delta = hit.getLocation().subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        float pitch = (float) -(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG);
        boolean sneak = requiresSneak(clicked);
        return Optional.ofNullable(PlacementPrediction.predict(player, new ItemStack(cell.item()), hit, yaw, pitch, sneak, cell.pos()));
    }

    @Override public boolean requiresSneak(BlockPos clicked) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null || !current.level().hasChunkAt(clicked)) return false;
        return PlacementPrediction.requiresSneak(current.level(), clicked, current.level().getBlockState(clicked));
    }
}

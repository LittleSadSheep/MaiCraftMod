// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.game.interaction.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 放置预测的生产实现：从角色现在的眼睛位置找被点那一面看得见的点，算出看向它的角度，
 * 再用原版的放置规则试算这一下会放成什么；物品放下以后自己再改的（竖直齿轮箱定轴向）按联动的放置规则补上。
 * 只算不动手；真的转头与点击交给交互动作。
 */
public final class LivePlacements implements ConstructionSeams.PlansPlacement {

    private final Supplier<PlayerContext> context;
    private final CellPlacements placements;

    public LivePlacements(Supplier<PlayerContext> context) {
        this(context, List.of());
    }

    public LivePlacements(Supplier<PlayerContext> context, List<PlacementRules> rules) {
        this.context = Objects.requireNonNull(context, "context");
        this.placements = CellPlacements.of(rules);
    }

    @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face) {
        return predict(cell, cell.pos(), clicked, face);
    }

    @Override public Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos placeAt, BlockPos clicked, Direction face) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) return Optional.empty();
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        Vec3 eye = player.getEyePosition();
        // 点那一面按方块外形上朝这个方向的表面找（轮缘伸出格外、细轴都算），和挑站位、出手瞄准同一条规则：
        // 原先这里只试外形边界上七个点，站位按格子边界那一面挑，两边对不上，站到了又说点不到。
        BlockHitResult hit = FirstPersonInteractionTargeting.visibleFaceHit(level, player, eye, clicked, InteractionRange.blockReach(player), face);
        if (hit == null) return Optional.empty();
        // 看向命中点的角度：和交互动作转头后的真实视角一致，原版按它决定朝向。
        Vec3 delta = hit.getLocation().subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        float pitch = (float) -(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG);
        boolean sneak = requiresSneak(clicked);
        Optional<PlacementPrediction.Placement> standing = settle(cell, placeAt, player, level, hit, yaw, pitch, sneak);
        // 站着点放不成要的样子、点的又不是非蹲不可的方块：像玩家一样蹲着再试一次——机械动力的方向机器蹲着朝视线、
        // 站着背着视线，有的朝向只有蹲着才放得出（站在置物台旁边放朝下的机械手）。
        if (!sneak && standing.isPresent() && !PlacementPrediction.complete(cell, standing.get().predicted())) {
            Optional<PlacementPrediction.Placement> crouching = settle(cell, placeAt, player, level, hit, yaw, pitch, true);
            if (crouching.isPresent() && PlacementPrediction.complete(cell, crouching.get().predicted())) return crouching;
        }
        return standing;
    }

    // 原版以为在放 placeAt；落下来以后物品自己再改的样子（朝向这一下的水平方向定）由放置规则补上，再拿去和蓝图比。
    private Optional<PlacementPrediction.Placement> settle(PlannedCell cell, BlockPos placeAt, LocalPlayer player,
            ClientLevel level, BlockHitResult hit, float yaw, float pitch, boolean sneak) {
        PlacementPrediction.Placement predicted = PlacementPrediction.predict(player, new ItemStack(cell.item()), hit, yaw, pitch, sneak, placeAt);
        if (predicted == null || predicted.predicted() == null) return Optional.ofNullable(predicted);
        BlockState settled = placements.afterPlacing(cell.item(), predicted.predicted(), level, cell.pos(), Direction.fromYRot(yaw));
        return Optional.of(new PlacementPrediction.Placement(predicted.clicked(), predicted.face(), settled, sneak));
    }

    @Override public Optional<PlacementRules.Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
        return placements.turnsTo(placed, wanted);
    }

    @Override public boolean requiresSneak(BlockPos clicked) {
        PlayerContext current = context.get();
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气、地表落在世界最底层；按区块真加载没加载判。
        if (current == null || current.level() == null || !current.level().isLoaded(clicked)) return false;
        return PlacementPrediction.requiresSneak(current.level(), clicked, current.level().getBlockState(clicked));
    }
}

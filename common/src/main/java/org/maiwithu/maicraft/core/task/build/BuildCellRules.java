package org.maiwithu.maicraft.core.task.build;

import org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 检查一格能否施工：是否允许替换、是否有箱子等需要保护、是否越界或不可挖，以及是否挡住玩家或生物。
 * 这些方法只给判断结果，调用方负责在正确时机检查并决定停止或换位置。
 */
final class BuildCellRules {

    private final LocalPlayer player;
    private final BuildTaskRecord r;

    BuildCellRules(LocalPlayer player, BuildTaskRecord r) {
        this.player = player;
        this.r = r;
    }

    /** 只读已加载区域；底层视图会把未加载处当空气，因此调用者必须另行证明目标已加载。 */
    BlockState peek(BlockPos pos) {
        return LoadedOnlyView.of(player.level()).getBlockState(pos);
    }

    static boolean isAirTarget(BuildTaskRecord.Target target) {
        return target.block() == net.minecraft.world.level.block.Blocks.AIR;
    }

    /** 计费判据在 {@link BuildTaskRecord.Target#costsMaterial()}——盘点工具与这里共用。 */
    boolean costsMaterial(BuildTaskRecord.Target target) {
        return !isAirTarget(target) && target.costsMaterial();
    }

    /** 当前替换模式是否禁止修改这一格；施工预检收到 true 会报告受阻，不会把它当成已建好。 */
    boolean blockedByMode(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState current = peek(pos);
        if (!r.replaceMode.allows(current, target.desiredState())) {
            return true;
        }
        // 玩家的箱子不能被一堵墙盖掉。让路的档位管"石头挡路要不要顶掉",这一条
        // 管"带方块实体的方块要不要动"——少砌一格墙是遗憾,清掉一箱子东西是事故。
        if (r.replaceBlockEntities || current.isAir()) {
            return false;
        }
        if (current.hasBlockEntity() && !target.matches(current)) {
            return true;
        }
        // 双格方块连另一半一起看:任一半压着方块实体就都不动
        BlockPos other = otherHalfOf(pos, target.desiredState());
        return other != null && peek(other).hasBlockEntity();
    }

    /** 世界边界、高度或基岩等使目标无法施工；和“缺材料、暂时有人挡着”这种可能恢复的情况分开。 */
    boolean hopeless(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        if (!player.level().getWorldBorder().isWithinBounds(pos)) {
            return true;
        }
        // 超过游戏允许放方块的高度时，提前报告不能建，不继续尝试无效放置。
        if (player.level().isOutsideBuildHeight(pos)) {
            return true;
        }
        return unbreakableAt(pos, target.desiredState());
    }

    /** 检查基岩等无法破坏的方块；门和床要连另一半也检查，不能只证明主格放得下。 */
    private boolean unbreakableAt(BlockPos pos, BlockState desired) {
        var level = player.level();
        if (peek(pos).getDestroySpeed(level, pos) == -1) {
            return true;
        }
        BlockPos other = otherHalfOf(pos, desired);
        return other != null && peek(other).getDestroySpeed(level, other) == -1;
    }

    /**
     * 双格方块的另一半在哪:床看朝向那一格,门与高草看正上方。
     *
     * <p>只查自己那一格,会出现"下半放下去了、上半卡在基岩里"或者"下半盖住了玩家
     * 箱子的上半"这类半截货,所以砸不动与箱子保护两处都要连它一起看。
     */
    static BlockPos otherHalfOf(BlockPos pos, BlockState desired) {
        if (desired == null) {
            return null;
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART)
                && desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return pos.relative(desired.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return pos.above();
        }
        return null;
    }

    /** 新方块不能与玩家或其他生物身体重叠；玩家自己单独检查，不能只依赖世界的实体搜索结果。 */
    boolean blockedByEntity(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(player.level(), pos, CollisionContext.of(player));
        if (shape.isEmpty()) {
            return false;
        }
        VoxelShape placed = shape.move(pos.getX(), pos.getY(), pos.getZ());
        if (intersectsPlayer(placed)) {
            return true;
        }
        return !player.level().isUnobstructed(player, placed);
    }

    /** 单独判断是不是玩家自己挡住，这种情况可通过换站位解决，不必当作外部生物阻碍。 */
    boolean blockedByPlayer(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(player.level(), pos, CollisionContext.of(player));
        return !shape.isEmpty() && intersectsPlayer(
                shape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private boolean intersectsPlayer(VoxelShape placed) {
        AABB body = player.getBoundingBox();
        for (AABB piece : placed.toAabbs()) {
            if (piece.intersects(body)) {
                return true;
            }
        }
        return false;
    }
}

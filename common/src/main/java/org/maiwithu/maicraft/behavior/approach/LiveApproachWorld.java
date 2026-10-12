// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 靠近时的世界只读视图的生产实现：站位判断要问的每一件事，从当刻的角色与真实世界读出来。
 *
 * <p>只在控制循环的刻内调用；没有角色上下文时脚下给空由站位判断自行放弃，
 * 其余判定照常按"没加载、不成立"回答，不冒充看得见站得稳。
 */
public final class LiveApproachWorld implements ApproachWorldView {

    /** 往下找落脚面最多数多少格；再深就当深渊，不往下跳。 */
    private static final int MAX_DROP_SCAN = 32;

    private final Supplier<PlayerContext> context;

    public LiveApproachWorld(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public BlockPos currentFeet() {
        PlayerContext current = context.get();
        return current == null || current.localPlayer() == null ? null : current.localPlayer().blockPosition();
    }

    @Override
    public Vec3 currentEye() {
        PlayerContext current = context.get();
        return current == null || current.localPlayer() == null ? Vec3.ZERO : current.localPlayer().getEyePosition();
    }

    @Override
    public boolean onGround() {
        PlayerContext current = context.get();
        return current != null && current.localPlayer() != null && current.localPlayer().onGround();
    }

    @Override
    public boolean isLoaded(BlockPos at) {
        Level level = level();
        return level != null && level.isLoaded(at);
    }

    @Override
    public boolean solidFloor(BlockPos feet) {
        Level level = level();
        BlockPos below = feet.below();
        return level != null && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    @Override
    public boolean headroom(BlockPos feet) {
        Level level = level();
        // 脚位与头顶两格都没有碰撞，角色才站得直、跳得起来。
        return level != null
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    @Override
    public double dropBelow(BlockPos feet) {
        Level level = level();
        if (level == null) {
            return Double.MAX_VALUE;
        }
        for (int i = 1; i <= MAX_DROP_SCAN; i++) {
            BlockPos below = feet.below(i);
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return i - 1;
            }
        }
        return Double.MAX_VALUE;
    }

    @Override
    public boolean inFluid(BlockPos feet) {
        Level level = level();
        return level != null && !level.getFluidState(feet).isEmpty();
    }

    @Override
    public boolean lavaBeside(BlockPos feet) {
        Level level = level();
        if (level == null) {
            return false;
        }
        // 四个紧邻的格子挨个看，隔着一格的岩浆不算。
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(feet.relative(side)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从这只眼睛看不看得见目标，和真瞄准、真动手用同一套判断：方块试形状中心再试六个面内侧，
     * 射线先碰到的就是目标格才算（部件只在部件框里试）；目标那格是空气或液体（地上的掉落物、要放东西的空格）
     * 按视线有没有被挡在半路判断；实体试包围盒中心、偏上处与八个内缩角，有一条没被挡住就算。
     * 原先只从眼睛打一条射线到方块中心：中心被一片树叶挡住就算看不见，砍树、捡东西的站位大片被拒。
     */
    @Override
    public boolean visibleFrom(Vec3 eye, ApproachTarget target) {
        Level level = level();
        PlayerContext current = context.get();
        if (level == null || current == null || current.localPlayer() == null) {
            return false;
        }
        var player = current.localPlayer();
        if (target.kind() == InteractionKind.ENTITY) {
            return entityVisible(level, player, eye, target.bounds());
        }
        // 往一格里扔：东西从格顶上方落进去，看格顶正上方那一点有没有被挡；落不落得准由丢的时候按抛物线算。
        if (target.kind() == InteractionKind.TOSS) {
            Vec3 above = new Vec3(target.center().x, target.bounds().maxY + 0.05, target.center().z);
            return pointVisible(level, player, eye, above);
        }
        BlockPos cell = target.block();
        BlockState state = level.getBlockState(cell);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return pointVisible(level, player, eye, target.center());
        }
        // 触及距离由够得着规则单独判，这里只问视线：给一个刚好盖过这段距离的上限。
        double reach = eye.distanceTo(target.center()) + 2;
        // 放方块点的那一面：按外形上朝这个方向的表面判，和放置预测、出手瞄准同一条规则。
        if (target.face() != null) {
            return FirstPersonInteractionTargeting.visibleFaceHit(level, player, eye, cell, reach, target.face()) != null;
        }
        AABB cellBox = new AABB(cell);
        boolean part = !target.bounds().equals(cellBox);
        return part ? FirstPersonInteractionTargeting.visiblePartHit(level, player, eye, cell, reach, target.bounds()) != null
                : FirstPersonInteractionTargeting.visibleBlockHit(level, player, eye, cell, reach) != null;
    }

    // 实体：包围盒中心、偏上（头部附近）与八个内缩角，有一条视线没在半路被方块挡住就算看得见。
    private static boolean entityVisible(Level level, Entity viewer, Vec3 eye, AABB box) {
        List<Vec3> points = new ArrayList<>();
        points.add(box.getCenter());
        points.add(new Vec3(box.getCenter().x, box.minY + box.getYsize() * 0.85, box.getCenter().z));
        AABB inset = box.deflate(Math.min(0.05, box.getXsize() / 4), Math.min(0.05, box.getYsize() / 4),
                Math.min(0.05, box.getZsize() / 4));
        for (double x : new double[] {inset.minX, inset.maxX}) {
            for (double y : new double[] {inset.minY, inset.maxY}) {
                for (double z : new double[] {inset.minZ, inset.maxZ}) points.add(new Vec3(x, y, z));
            }
        }
        for (Vec3 point : points) {
            if (pointVisible(level, viewer, eye, point)) return true;
        }
        return false;
    }

    // 一个点：沿视线打一条方块射线（按选取外形，和准星一样），没碰到东西、或碰到的比这个点还远，就是没被挡。
    private static boolean pointVisible(Level level, Entity viewer, Vec3 eye, Vec3 point) {
        var hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(eye) >= point.distanceToSqr(eye) - 1.0E-6;
    }

    private Level level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }
}

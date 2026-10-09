// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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

    @Override
    public boolean visibleFrom(Vec3 eye, InteractionTarget target) {
        Level level = level();
        PlayerContext current = context.get();
        if (level == null || current == null || current.localPlayer() == null) {
            return false;
        }
        // 沿视线打一条方块射线：先撞到的方块就是视线被挡住；方块目标撞到的正是目标格才算看得见。
        var hit = level.clip(new ClipContext(eye, target.center(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, current.localPlayer()));
        if (target.block() != null) {
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target.block());
        }
        // 实体目标：撞到的方块比目标中心近，就是被墙挡住了。
        return hit.getType() == HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(eye) >= target.center().distanceToSqr(eye);
    }

    private Level level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }
}

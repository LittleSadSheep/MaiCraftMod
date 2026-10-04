// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;

/** 暗处补光只选站在原地就能照见的岩壁或地面，不为插火把挖洞、垫脚或覆盖已有方块。 */
public final class RoutineTorchPlacement {
    private RoutineTorchPlacement() {}

    public static boolean needsLight(int feetLight, int eyeLight) {
        // 这是默认阈值的纯判断入口；随行服务在 advance 中使用实际配置，脚边亮而眼部仍暗时也会尝试补光。
        return Math.min(feetLight, eyeLight) < 8;
    }

    public static BuildTaskRecord.Target find(LocalPlayer player, Set<BlockPos> protectedCells) {
        BlockPos origin = player.blockPosition();
        var candidates = new ArrayList<BuildTaskRecord.Target>();
        // 收集本来就够得着的墙面和地面，再优先选择当前视线附近的落点，避免固定方向顺序导致频繁回头。
        for (int height : new int[]{1, 0}) for (int distance = 1; distance <= 2; distance++) {
            for (Direction toward : Direction.Plane.HORIZONTAL) {
                BlockPos at = origin.relative(toward, distance).above(height);
                for (Direction wall : Direction.Plane.HORIZONTAL) {
                    Direction facing = wall.getOpposite();
                    BlockState expected = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing);
                    if (usable(player, at, expected, at.relative(wall), facing, protectedCells)) candidates.add(target(at, expected));
                }
            }
        }
        // 斜向行走也提供正前方灯位，不强迫角色从东西南北四个方向里挑一个再额外横转。
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos at = origin.offset(dx, 0, dz);
            if (usable(player, at, Blocks.TORCH.defaultBlockState(), at.below(), Direction.UP, protectedCells))
                candidates.add(target(at, Blocks.TORCH.defaultBlockState()));
        }
        return candidates.stream().max(Comparator.comparingDouble(candidate -> viewAlignment(player, candidate))).orElse(null);
    }

    private static double viewAlignment(LocalPlayer player, BuildTaskRecord.Target candidate) {
        Direction face = candidate.desiredState().is(Blocks.WALL_TORCH)
                ? candidate.desiredState().getValue(WallTorchBlock.FACING) : Direction.UP;
        Vec3 point = Vec3.atCenterOf(candidate.pos().relative(face.getOpposite()))
                .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
        return point.subtract(player.getEyePosition()).normalize().dot(player.getViewVector(1));
    }

    public static boolean usable(LocalPlayer player, BlockPos at, BlockState expected,
                                 BlockPos support, Direction face, Set<BlockPos> protectedCells) {
        // 临出手再核对空格、支撑面、保护范围和真实射线；快速火把路径只接受空气，不会先清草或替换已有方块。
        var level = player.level();
        if (!level.isLoaded(at) || !level.isLoaded(support) || protectedCells.contains(at) || protectedCells.contains(support)
                || NavigationSafetyContext.protectsMutation(at) || NavigationSafetyContext.protectsUse(support)
                || NavigationSafetyContext.forbidsBody(at) || !level.getBlockState(at).isAir()
                || !level.getFluidState(at).isEmpty() || new AABB(at).intersects(player.getBoundingBox().inflate(.1))) return false;
        BlockState base = level.getBlockState(support);
        // 箱子、机器和工作台不作默认灯座，避免打开界面；基地木板、砖墙等普通结实表面也可直接挂灯。
        if (base.hasBlockEntity() || base.getMenuProvider(level, support) != null || !base.getFluidState().isEmpty()
                || !base.isFaceSturdy(level, support, face) || !expected.canSurvive(level, at)) return false;
        Vec3 point = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
        if (player.getEyePosition().distanceToSqr(point) > Math.pow(Math.min(4.25, player.blockInteractionRange()), 2)) return false;
        var hit = level.clip(new ClipContext(player.getEyePosition(), point.add(Vec3.atLowerCornerOf(face.getNormal()).scale(-.01)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support) && hit.getDirection() == face;
    }

    public static boolean stillUsable(LocalPlayer player, BuildTaskRecord.Target target, Set<BlockPos> protectedCells) {
        BlockState expected = target.desiredState();
        Direction face = expected.is(Blocks.WALL_TORCH) ? expected.getValue(WallTorchBlock.FACING) : Direction.UP;
        return usable(player, target.pos(), expected, target.pos().relative(face.getOpposite()), face, protectedCells);
    }

    private static BuildTaskRecord.Target target(BlockPos at, BlockState expected) {
        return new BuildTaskRecord.Target(expected, Items.TORCH, at.immutable(), "routine torch", null, null, null);
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
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
        // 以观众能看清角色周围为目标；脚边亮而视线仍黑时也补光，不仅检查怪物能否生成。
        return Math.min(feetLight, eyeLight) < 8;
    }

    public static BuildTaskRecord.Target find(LocalPlayer player, Set<BlockPos> protectedCells) {
        BlockPos origin = player.blockPosition();
        // 优先把火把挂在近处墙上，空旷洞穴再落地；候选始终在当前交互距离内。
        for (int height : new int[]{1, 0}) for (int distance = 1; distance <= 2; distance++) {
            for (Direction toward : Direction.Plane.HORIZONTAL) {
                BlockPos at = origin.relative(toward, distance).above(height);
                for (Direction wall : Direction.Plane.HORIZONTAL) {
                    Direction facing = wall.getOpposite();
                    BlockState expected = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing);
                    if (usable(player, at, expected, at.relative(wall), facing, protectedCells)) return target(at, expected);
                }
            }
        }
        for (int distance = 1; distance <= 2; distance++) for (Direction toward : Direction.Plane.HORIZONTAL) {
            BlockPos at = origin.relative(toward, distance);
            if (usable(player, at, Blocks.TORCH.defaultBlockState(), at.below(), Direction.UP, protectedCells))
                return target(at, Blocks.TORCH.defaultBlockState());
        }
        return null;
    }

    public static boolean usable(LocalPlayer player, BlockPos at, BlockState expected,
                                 BlockPos support, Direction face, Set<BlockPos> protectedCells) {
        var level = player.level();
        if (!level.isLoaded(at) || !level.isLoaded(support) || protectedCells.contains(at) || protectedCells.contains(support)
                || NavigationSafetyContext.protectsMutation(at) || NavigationSafetyContext.protectsUse(support)
                || NavigationSafetyContext.forbidsBody(at) || !level.getBlockState(at).isAir()
                || !level.getFluidState(at).isEmpty() || new AABB(at).intersects(player.getBoundingBox().inflate(.1))) return false;
        BlockState base = level.getBlockState(support);
        // 机器、箱子、农田、矿石和临时装饰不作默认灯座，避免右键开菜单或把灯挂在马上要采的矿上。
        if (!terrain(base) || base.hasBlockEntity() || !base.getFluidState().isEmpty()
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

    private static boolean terrain(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT)
                || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.DIRT) || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND);
    }

    private static BuildTaskRecord.Target target(BlockPos at, BlockState expected) {
        return new BuildTaskRecord.Target(expected, Items.TORCH, at.immutable(), "routine torch", null, null, null);
    }
}

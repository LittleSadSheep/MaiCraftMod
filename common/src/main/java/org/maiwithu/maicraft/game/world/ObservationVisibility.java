// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 探索只接受从角色眼睛能直接看见的现场目标；墙、关门和未加载地形都不能被当作透明。 */
public final class ObservationVisibility {
    private ObservationVisibility() {}

    public static boolean block(LocalPlayer player, BlockPos at) {
        if (player == null || at == null || !player.level().isLoaded(at)) return false;
        // 小箱子、台阶和栅栏按各段实际外形取观察点，不能把组合外形中间的空气当成目标表面。
        var shape = player.level().getBlockState(at).getShape(player.level(), at);
        if (shape.isEmpty()) return box(player, new AABB(at), at);
        for (AABB part : shape.toAabbs()) if (box(player, part.move(at), at)) return true;
        return false;
    }

    private static boolean box(LocalPlayer player, AABB box, BlockPos targetBlock) {
        Vec3 center = box.getCenter();
        if (ray(player, center, targetBlock)) return true;
        for (Direction face : Direction.values()) {
            Vec3 point = center.add(face.getStepX() * Math.max(0, box.getXsize() / 2 - .001),
                    face.getStepY() * Math.max(0, box.getYsize() / 2 - .001),
                    face.getStepZ() * Math.max(0, box.getZsize() / 2 - .001));
            if (ray(player, point, targetBlock)) return true;
        }
        // 中心线被柱子或台阶挡住时继续检查内缩边角，让从空隙露出的方块与实体仍能被发现。
        AABB inset = box.deflate(Math.min(.001, box.getXsize() / 4),
                Math.min(.001, box.getYsize() / 4), Math.min(.001, box.getZsize() / 4));
        for (double x : new double[]{inset.minX, inset.maxX})
            for (double y : new double[]{inset.minY, inset.maxY})
                for (double z : new double[]{inset.minZ, inset.maxZ})
                    if (ray(player, new Vec3(x, y, z), targetBlock)) return true;
        return false;
    }

    public static boolean entity(LocalPlayer player, Entity target) {
        // 生物头部或身体边缘露出即可观察；整只藏在墙后时仍不能先交付身份再让角色追过去。
        return player != null && target != null && !target.isRemoved()
                && (point(player, target.getEyePosition()) || box(player, target.getBoundingBox(), null));
    }

    public static boolean point(LocalPlayer player, Vec3 point) {
        return player != null && point != null && ray(player, point, null);
    }

    private static boolean ray(LocalPlayer player, Vec3 end, BlockPos targetBlock) {
        Vec3 eye = player.getEyePosition();
        // 原生射线穿过的每一格都须已加载；不能越过未知区块看到另一侧目标。
        boolean loaded = BlockGetter.traverseBlocks(eye, end, player.level(),
                (level, cell) -> level.isLoaded(cell) ? null : Boolean.FALSE, level -> Boolean.TRUE);
        if (!loaded) return false;
        // 看见目标与准星点中目标是两件事：观察使用原生视觉外形，挖掘/交互仍由各自的原生命中射线裁决。
        var hit = player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player) {
            @Override public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
                VoxelShape visual = super.getBlockShape(state, level, pos);
                if (visual.isEmpty()) return visual;
                // 玻璃、冰、黏液与树叶的碰撞体会挡住身体，但透光材质不应挡住观察；模组注册的半透明渲染层同样适用。
                if (state.getBlock() instanceof HalfTransparentBlock || state.getBlock() instanceof LeavesBlock
                        || state.is(BlockTags.LEAVES)
                        || ItemBlockRenderTypes.getChunkRenderType(state) == RenderType.translucent()) return Shapes.empty();
                // 栅栏横杆的视觉轮廓仍可能包住下方空隙；再与原生渲染遮挡外形相交，排除为碰撞/选取扩大的体积。
                VoxelShape occlusion = state.getOcclusionShape(level, pos);
                return visual == occlusion ? visual : Shapes.join(visual, occlusion, BooleanOp.AND);
            }
        });
        return hit.getType() == HitResult.Type.MISS
                || targetBlock != null && targetBlock.equals(hit.getBlockPos());
    }
}

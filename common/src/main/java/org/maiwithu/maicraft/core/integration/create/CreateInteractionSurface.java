// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;

/** 置物台取放与机械手换物各有原生点击区域；选站位、瞄准和最终点击共用同一个判据。 */
public final class CreateInteractionSurface {
    private CreateInteractionSurface() {}

    public record Rule(Direction face, Direction handFacing) {
        public boolean constrained() { return face != null || handFacing != null; }
        public boolean accepts(BlockHitResult hit) {
            if (face != null && hit.getDirection() != face) return false;
            if (handFacing == null) return true;
            // DeployerBlock.useItemOn 从背端沿朝向量出四分之三格；前端侧面也合法，不能强制只点端盖。
            Vec3 normal = Vec3.atLowerCornerOf(handFacing.getNormal());
            return hit.getLocation().subtract(Vec3.atCenterOf(hit.getBlockPos()).subtract(normal.scale(.5)))
                    .multiply(normal).length() >= .75;
        }
        public BlockHitResult visibleHit(Level level, Entity observer, Vec3 eye, BlockPos target, double reach) {
            return FirstPersonInteractionTargeting.visibleBlockHit(level, observer, eye, target, reach, face, this::accepts);
        }
    }

    public static Rule forUse(BlockState state, Item item) {
        Direction facing = state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING) : null;
        return forUse(BuiltInRegistries.BLOCK.getKey(state.getBlock()), facing,
                BuiltInRegistries.ITEM.getKey(item == null ? Items.AIR : item));
    }

    public static Rule forUse(ResourceLocation blockId, Direction facing, ResourceLocation itemId) {
        // 扳手在原生逻辑里负责调向或切模式，手持机械手会先触发相邻放置；这两种操作不套用装料入口。
        boolean swap = blockId.toString().equals("create:deployer")
                && !itemId.toString().equals("create:wrench") && !itemId.toString().equals("create:deployer");
        return new Rule(requiredFace(blockId), swap ? facing : null);
    }
    public static Direction requiredFace(BlockState state) {
        return requiredFace(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }
    public static Direction requiredFace(ResourceLocation blockId) {
        // SharedDepotBlockMethods.onUse 对其他方向直接 PASS；不能把看见台座侧面当作已经能取放工件。
        return blockId.equals(ResourceLocation.fromNamespaceAndPath("create", "depot")) ? Direction.UP : null;
    }
}

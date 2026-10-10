// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Comparator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 施工顺序：从地基往上一层一层砌；同一层先清空、再骨架、后贴附件（梯子、火把、门这些要先有墙才挂得上）；
 * 层内按 z 逐排走，偶数排 x 递增、奇数排 x 递减，让相邻两排首尾接近，少走回头路。
 * 这只是排序，不检查现场有没有支撑；放不上的格由施工任务先做别处再回头。
 */
public final class BuildOrder {

    private BuildOrder() {}

    /** 低层先，同层清空 → 骨架 → 贴附件，再按排蛇形走。 */
    public static final Comparator<PlannedCell> LOW_TO_HIGH = Comparator
            .comparingInt((PlannedCell cell) -> cell.pos().getY())
            .thenComparingInt(cell -> needsSupport(cell.state()) ? 1 : 0)
            .thenComparingInt(BuildOrder::stage)
            .thenComparingInt(cell -> cell.pos().getZ())
            .thenComparingInt(cell -> (cell.pos().getZ() & 1) == 0 ? cell.pos().getX() : -cell.pos().getX());

    /**
     * 要先有别的方块才挂得住的东西：挂着的灯笼与告示牌、梯子、火把、牌、压力板、铁轨、红石元件、红石线、
     * 地毯、植物、花盆、雪层、门、铁栏杆、按钮拉杆这类贴面件（砂轮同属贴面件但自己立得住）。
     */
    public static boolean needsSupport(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (state.hasProperty(BlockStateProperties.HANGING)) return true;
        var block = state.getBlock();
        return block instanceof LadderBlock
                || block instanceof BaseTorchBlock
                || block instanceof SignBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof BaseRailBlock
                || block instanceof DiodeBlock
                || block instanceof RedStoneWireBlock
                || block instanceof CarpetBlock
                || block instanceof BushBlock
                || block instanceof FlowerPotBlock
                || block instanceof SnowLayerBlock
                || block instanceof DoorBlock
                || block instanceof IronBarsBlock
                || (block instanceof FaceAttachedHorizontalDirectionalBlock && !(block instanceof GrindstoneBlock));
    }

    /** 层内阶段：清空 0 → 整块骨架 1 → 其他 2。 */
    static int stage(PlannedCell cell) {
        if (cell.kind() == CellKind.AIR) return 0;
        if (cell.kind() == CellKind.FLUID_SOURCE) return 2;
        return cell.state().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? 1 : 2;
    }
}

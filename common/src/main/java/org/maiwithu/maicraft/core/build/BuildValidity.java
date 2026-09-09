package org.maiwithu.maicraft.core.build;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared block-state validity rules for construction scans, placement, and path costs. */
public final class BuildValidity {

    private static final Set<Property<?>> ORIENTATION_PROPERTIES = Set.copyOf(List.of(
            RotatedPillarBlock.AXIS,
            HorizontalDirectionalBlock.FACING,
            StairBlock.FACING,
            StairBlock.HALF,
            StairBlock.SHAPE,
            PipeBlock.NORTH,
            PipeBlock.EAST,
            PipeBlock.SOUTH,
            PipeBlock.WEST,
            PipeBlock.UP,
            TrapDoorBlock.OPEN,
            TrapDoorBlock.HALF));

    /**
     * 验收时，默认只逐项比较下面列出的摆放属性，例如楼梯朝向、半砖上下半和门的合页。
     * 列表外的属性不参与这里的比较，例如楼梯拐角、红石信号、门是否打开。
     * 注意：雪层数、蜡烛数量也没有列入；调用者若没有另外要求精确比较，同一种方块就可能算完成。
     * 因此这张表表示目前程序检查的范围，不能理解成“作者能决定的属性已经全部包括在内”。
     */
    private static final Set<Property<?>> AUTHORED_PROPERTIES = Set.copyOf(List.of(
            BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.AXIS,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.DOUBLE_BLOCK_HALF,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.BED_PART));

    private BuildValidity() {}

    public static boolean isPlacementProperty(Property<?> property) { return AUTHORED_PROPERTIES.contains(property); }

    // 先应用用户设置的放宽规则，再比较方块。desired 为 null 表示这一格没有目标要求。
    // itemVerify=true 用于检查这次准备放下的状态：不采用“保留任意已有方块”和替代材料两项放宽。
    public static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        NavSettings settings = NavSettings.get();
        if (current.getBlock() instanceof LiquidBlock && settings.okIfWater) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && settings.okIfAir().contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && settings.buildIgnoreBlocks().contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && settings.buildIgnoreExisting && !itemVerify) {
            return true;
        }
        if (!itemVerify && settings.buildValidSubstitutes()
                .getOrDefault(desired.getBlock(), List.of()).contains(current.getBlock())) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockState(current, desired);
    }

    public static boolean sameBlockState(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        NavSettings settings = NavSettings.get();
        List<String> ignoredProps = settings.buildIgnoreProperties();
        // 推开栅栏门可能让它转向相反方向，所以这里直接跳过其朝向。
        // 当前也会放过转错九十度的门，并没有只忽略开门造成的一百八十度翻转。
        boolean gate = first.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
        for (Property<?> property : AUTHORED_PROPERTIES) {
            if (!first.hasProperty(property) || !second.hasProperty(property)) {
                continue;
            }
            if (gate && property == BlockStateProperties.HORIZONTAL_FACING) {
                continue;
            }
            if (settings.buildIgnoreDirection && ORIENTATION_PROPERTIES.contains(property)) {
                continue;
            }
            // 用户还可以按属性名放宽验收；这会影响使用本方法的所有建筑任务。
            if (ignoredProps.contains(property.getName())) {
                continue;
            }
            if (first.getValue(property) != second.getValue(property)) {
                return false;
            }
        }
        return true;
    }
}

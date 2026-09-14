// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 原版没有通用竖直rotate接口；只有方向属性、台阶上下半和真实形状均能表达时才接受倾斜。 */
final class BuildingModelTiltStates {
    private BuildingModelTiltStates() {}

    static BlockState transform(BlockState before, int[] matrix) {
        if (!before.getFluidState().isEmpty()) throw BuildingModelBlockStates.unsupported(before, "vertical fluid or waterlogged transforms need a dedicated adapter");
        BlockState after;
        if (before.getBlock() instanceof StairBlock || before.getBlock() instanceof SlabBlock) {
            // 上下镜像可由top/bottom表达；倾成墙上的半砖或竖直楼梯没有相应原生方块状态。
            if (matrix[4] != -1) {
                if (before.getBlock() instanceof SlabBlock && before.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) after = before;
                else throw BuildingModelBlockStates.unsupported(before, "tilted slabs or vertical stairs are not representable");
            } else {
                int[] horizontal = matrix.clone(); horizontal[4] = 1;
                after = BuildingModelBlockStates.horizontal(before, horizontal);
                if (before.getBlock() instanceof StairBlock) after = after.setValue(StairBlock.HALF, after.getValue(StairBlock.HALF) == Half.TOP ? Half.BOTTOM : Half.TOP);
                else {
                    SlabType type = after.getValue(SlabBlock.TYPE);
                    if (type != SlabType.DOUBLE) after = after.setValue(SlabBlock.TYPE, type == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP);
                }
            }
        } else after = directional(before, matrix);
        try {
            if (!shapesMatch(before, after, matrix)) throw BuildingModelBlockStates.unsupported(before, "native shape cannot express the requested vertical transform");
        } catch (RuntimeException | LinkageError unavailable) {
            throw BuildingModelBlockStates.unsupported(before, "vertical shape proof failed: " + unavailable.getMessage());
        }
        return after;
    }

    private static BlockState directional(BlockState state, int[] matrix) {
        BlockState transformed = state;
        for (Property<?> property : state.getProperties()) {
            Comparable<?> value = state.getValue(property);
            if (value instanceof Direction && property.getPossibleValues().size() != 6)
                throw BuildingModelBlockStates.unsupported(state, "horizontal-only direction " + property.getName() + " cannot follow a vertical transform");
            if (BuildingModelBlockStates.namedDirection(property.getName()) != null) {
                // 连接面可随三维坐标交换，但必须确实有对应属性和相同值域，不能把墙柱的up开关当成east墙高。
                Property<?> target = BuildingModelBlockStates.mappedProperty(state, property.getName(), matrix);
                if (!target.getPossibleValues().equals(property.getPossibleValues()))
                    throw BuildingModelBlockStates.unsupported(state, "connection property domains differ: " + property.getName() + " -> " + target.getName());
            } else if (!(value instanceof Direction) && !(value instanceof Direction.Axis)
                    && !(property instanceof BooleanProperty) && !(property instanceof IntegerProperty && !property.getName().equals("rotation")))
                throw BuildingModelBlockStates.unsupported(state, "property " + property.getName() + " needs a vertical transform adapter");
            transformed = BuildingModelBlockStates.put(transformed, BuildingModelBlockStates.mappedProperty(state, property.getName(), matrix),
                    BuildingModelBlockStates.mappedValue(value, matrix));
        }
        return transformed;
    }

    static boolean shapesMatch(BlockState before, BlockState after, int[] matrix) {
        var world = EmptyBlockGetter.INSTANCE;
        return matches(before.getShape(world, BlockPos.ZERO), after.getShape(world, BlockPos.ZERO), matrix)
                && matches(before.getCollisionShape(world, BlockPos.ZERO), after.getCollisionShape(world, BlockPos.ZERO), matrix);
    }
    private static boolean matches(VoxelShape before, VoxelShape after, int[] matrix) {
        var boxes = before.toAabbs();
        if (boxes.size() > 128 || after.toAabbs().size() > 128) throw new IllegalArgumentException("model material shape proof budget exceeded");
        VoxelShape expected = Shapes.empty();
        for (AABB box : boxes) {
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 8; corner++) {
                double[] point = {(corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ};
                for (int row = 0; row < 3; row++) {
                    double value = .5;
                    for (int column = 0; column < 3; column++) value += matrix[row * 3 + column] * (point[column] - .5);
                    min[row] = Math.min(min[row], value); max[row] = Math.max(max[row], value);
                }
            }
            expected = Shapes.or(expected, Shapes.create(new AABB(min[0], min[1], min[2], max[0], max[1], max[2])));
        }
        // 逐体素比较整个形状，不能只比外接立方体；内外角楼梯的外接盒完全相同但实际缺角不同。
        return !Shapes.joinIsNotEmpty(expected, after, BooleanOp.NOT_SAME);
    }
}

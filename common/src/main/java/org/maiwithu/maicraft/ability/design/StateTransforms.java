// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
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

/**
 * 材料的方块状态跟着对象一起转向：水平的旋转与镜像走原版自己的 rotate/mirror；竖直的翻转与倾斜原版没有通用接口，
 * 只有方向属性、半砖上下和真实形状都能表达时才接受。只转目标数据，不碰世界，也不替施工归一运行态。
 * 输出仍是稀疏的：作者没写的 open、powered、waterlogged 不会被补上。
 */
public final class StateTransforms {

    private StateTransforms() {}

    /** matrix 按 Minecraft 的 x y z 逐行排，每行每列恰好一个 ±1，表示绕方块中心的直角旋转或镜像。 */
    public static JsonObject transform(JsonObject materialState, int[] matrix) {
        int[] checked = matrix(matrix);
        BlockState before = resolve(materialState);
        BlockState after = checked[4] == 1 ? horizontal(before, checked) : tilt(before, checked);
        verifySpatial(before, after, checked);
        JsonObject authored = materialState.has("properties") ? materialState.getAsJsonObject("properties") : new JsonObject();
        Set<String> required = new LinkedHashSet<>();
        // 作者写的 north=false 要搬到转过去的那一面，不能留在旧的 north 上，也不能把没写的连接面都补齐。
        for (String property : authored.keySet()) required.add(mappedProperty(before, property, checked).getName());
        BlockState defaults = before.getBlock().defaultBlockState();
        for (Property<?> property : after.getProperties()) {
            if (!after.getValue(property).equals(defaults.getValue(property))) required.add(property.getName());
        }
        JsonObject properties = new JsonObject();
        for (String key : required) {
            Property<?> property = after.getBlock().getStateDefinition().getProperty(key);
            properties.addProperty(key, name(property, after.getValue(property)));
        }
        JsonObject result = materialState.deepCopy();
        if (!properties.isEmpty() || materialState.has("properties")) result.add("properties", properties);
        if (!resolve(result).equals(after)) throw unsupported(before, "稀疏属性表达不出转过去的原生状态");
        return result;
    }

    static BlockState horizontal(BlockState state, int[] matrix) {
        String failure = "原版的旋转与镜像表达不出要求的朝向";
        for (Rotation first : Rotation.values()) {
            for (Mirror mirror : Mirror.values()) {
                for (Rotation last : Rotation.values()) {
                    boolean matches = true;
                    for (Direction direction : Direction.values()) {
                        if (last.rotate(mirror.mirror(first.rotate(direction))) != direction(matrix, direction)) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches) continue;
                    try {
                        BlockState transformed = state.rotate(first).mirror(mirror).rotate(last);
                        verifySpatial(state, transformed, matrix);
                        // 原版角楼梯的镜像有不等价的组合；只选和真实体素形状一致的那种，不猜 shape 枚举。
                        if (state.getBlock() instanceof StairBlock && !shapesMatch(state, transformed, matrix)) continue;
                        return transformed;
                    } catch (RuntimeException | LinkageError invalid) {
                        failure = invalid.getMessage();
                    }
                }
            }
        }
        throw unsupported(state, failure == null ? "原生变换不可用" : failure);
    }

    private static BlockState tilt(BlockState before, int[] matrix) {
        if (!before.getFluidState().isEmpty()) throw unsupported(before, "含水或流体的状态不能竖直变换");
        BlockState after;
        if (before.getBlock() instanceof StairBlock || before.getBlock() instanceof SlabBlock) {
            // 上下翻转可以用 top/bottom 表达；倾成墙上的半砖或竖直楼梯没有对应的原生方块状态。
            if (matrix[4] != -1) {
                if (before.getBlock() instanceof SlabBlock && before.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) after = before;
                else throw unsupported(before, "倾斜的半砖或竖直的楼梯没有对应的方块状态");
            } else {
                int[] horizontal = matrix.clone();
                horizontal[4] = 1;
                after = horizontal(before, horizontal);
                if (before.getBlock() instanceof StairBlock) {
                    after = after.setValue(StairBlock.HALF, after.getValue(StairBlock.HALF) == Half.TOP ? Half.BOTTOM : Half.TOP);
                } else {
                    SlabType type = after.getValue(SlabBlock.TYPE);
                    if (type != SlabType.DOUBLE) after = after.setValue(SlabBlock.TYPE, type == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP);
                }
            }
        } else {
            after = directional(before, matrix);
        }
        try {
            if (!shapesMatch(before, after, matrix)) throw unsupported(before, "原生形状表达不出要求的竖直变换");
        } catch (RuntimeException | LinkageError unavailable) {
            throw unsupported(before, "竖直变换的形状证明失败：" + unavailable.getMessage());
        }
        return after;
    }

    private static BlockState directional(BlockState state, int[] matrix) {
        BlockState transformed = state;
        for (Property<?> property : state.getProperties()) {
            Comparable<?> value = state.getValue(property);
            if (value instanceof Direction && property.getPossibleValues().size() != 6) {
                throw unsupported(state, "只有水平朝向的 " + property.getName() + " 跟不了竖直变换");
            }
            if (namedDirection(property.getName()) != null) {
                // 连接面可以随三维坐标交换，但必须真有对应属性且值域相同，不能把墙柱的 up 开关当成 east 的墙高。
                Property<?> target = mappedProperty(state, property.getName(), matrix);
                if (!target.getPossibleValues().equals(property.getPossibleValues())) {
                    throw unsupported(state, "连接面属性的值域不同：" + property.getName() + " -> " + target.getName());
                }
            } else if (!(value instanceof Direction) && !(value instanceof Direction.Axis)
                    && !(property instanceof BooleanProperty) && !(property instanceof IntegerProperty && !property.getName().equals("rotation"))) {
                throw unsupported(state, "属性 " + property.getName() + " 不知道怎么竖直变换");
            }
            transformed = put(transformed, mappedProperty(state, property.getName(), matrix), mappedValue(value, matrix));
        }
        return transformed;
    }

    static void verifySpatial(BlockState before, BlockState after, int[] matrix) {
        if (after == null || before.getBlock() != after.getBlock()) throw unsupported(before, "原生变换换掉了方块本身");
        for (Property<?> property : before.getProperties()) {
            Comparable<?> value = before.getValue(property);
            if (!(value instanceof Direction) && !(value instanceof Direction.Axis) && namedDirection(property.getName()) == null) continue;
            Property<?> target = mappedProperty(before, property.getName(), matrix);
            Comparable<?> expected = mappedValue(value, matrix);
            if (!target.getPossibleValues().contains(expected) || !after.getValue(target).equals(expected)) {
                throw unsupported(before, "原生变换没保住 " + property.getName() + " -> " + target.getName() + "=" + expected);
            }
        }
    }

    static Property<?> mappedProperty(BlockState state, String property, int[] matrix) {
        Direction side = namedDirection(property);
        String target = side == null ? property : direction(matrix, side).getName();
        Property<?> found = state.getBlock().getStateDefinition().getProperty(target);
        if (found == null) throw unsupported(state, "连接面 " + property + " 转过去是 " + target + "，方块没有这个属性");
        return found;
    }

    static Comparable<?> mappedValue(Comparable<?> value, int[] matrix) {
        if (value instanceof Direction side) return direction(matrix, side);
        if (value instanceof Direction.Axis axis) return direction(matrix, Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)).getAxis();
        return value;
    }

    static Direction namedDirection(String property) {
        for (Direction direction : Direction.values()) if (direction.getName().equals(property)) return direction;
        return null;
    }

    static Direction direction(int[] matrix, Direction direction) {
        int x = direction.getStepX(), y = direction.getStepY(), z = direction.getStepZ();
        return Direction.fromDelta(matrix[0] * x + matrix[1] * y + matrix[2] * z,
                matrix[3] * x + matrix[4] * y + matrix[5] * z, matrix[6] * x + matrix[7] * y + matrix[8] * z);
    }

    private static int[] matrix(int[] input) {
        if (input == null || input.length != 9) throw new IllegalArgumentException("变换矩阵要是 3×3 的带符号置换矩阵");
        int[] matrix = input.clone();
        for (int index = 0; index < 3; index++) {
            int row = 0, column = 0;
            for (int other = 0; other < 3; other++) {
                if (matrix[index * 3 + other] < -1 || matrix[index * 3 + other] > 1) throw new IllegalArgumentException("变换矩阵的元素只能是 -1、0、1");
                row += Math.abs(matrix[index * 3 + other]);
                column += Math.abs(matrix[other * 3 + index]);
            }
            if (row != 1 || column != 1) throw new IllegalArgumentException("变换矩阵每行每列要恰好一个 ±1");
        }
        return matrix;
    }

    /** 材料的 block_id 加 properties 对应的原生方块状态；不认识的方块或属性直接拒绝。 */
    public static BlockState resolve(JsonObject material) {
        if (material == null || !Set.of("block_id", "properties").containsAll(material.keySet())) {
            throw new IllegalArgumentException("材料只认 block_id 与 properties");
        }
        String id = DesignFormat.string(material.get("block_id"), 256, "block_id");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("block_id 要写带命名空间的方块 ID：" + id);
        BlockState state = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("没有这个方块：" + id)).defaultBlockState();
        if (material.has("properties")) {
            JsonObject properties = DesignFormat.object(material.get("properties"), "properties");
            if (properties.size() > 32) throw unsupported(state, "属性太多");
            for (var entry : properties.entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null) throw unsupported(state, "没有属性 " + entry.getKey());
                state = parse(state, property, DesignFormat.string(entry.getValue(), 128, "属性值"));
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState parse(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> unsupported(state, "属性值不对：" + property.getName() + "=" + value)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState put(BlockState state, Property property, Comparable value) {
        if (!property.getPossibleValues().contains(value)) throw unsupported(state, "表达不出 " + property.getName() + "=" + value);
        return state.setValue(property, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String name(Property property, Comparable value) {
        return property.getName(value);
    }

    static boolean shapesMatch(BlockState before, BlockState after, int[] matrix) {
        var world = EmptyBlockGetter.INSTANCE;
        return matches(before.getShape(world, BlockPos.ZERO), after.getShape(world, BlockPos.ZERO), matrix)
                && matches(before.getCollisionShape(world, BlockPos.ZERO), after.getCollisionShape(world, BlockPos.ZERO), matrix);
    }

    private static boolean matches(VoxelShape before, VoxelShape after, int[] matrix) {
        var boxes = before.toAabbs();
        if (boxes.size() > 128 || after.toAabbs().size() > 128) throw new IllegalArgumentException("形状证明的盒子太多");
        VoxelShape expected = Shapes.empty();
        for (AABB box : boxes) {
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 8; corner++) {
                double[] point = {(corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ};
                for (int row = 0; row < 3; row++) {
                    double value = .5;
                    for (int column = 0; column < 3; column++) value += matrix[row * 3 + column] * (point[column] - .5);
                    min[row] = Math.min(min[row], value);
                    max[row] = Math.max(max[row], value);
                }
            }
            expected = Shapes.or(expected, Shapes.create(new AABB(min[0], min[1], min[2], max[0], max[1], max[2])));
        }
        // 逐体素比较整个形状，不只比外接盒；内角和外角楼梯的外接盒一样，缺的角却不同。
        return !Shapes.joinIsNotEmpty(expected, after, BooleanOp.NOT_SAME);
    }

    static IllegalArgumentException unsupported(BlockState state, String reason) {
        return new IllegalArgumentException("材料 " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " 转不过去：" + reason);
    }
}

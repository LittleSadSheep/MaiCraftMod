// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** v2组件的本地材质随几何一起转向；只转换目标数据，不操作世界，也不替施工归一化运行状态。 */
public final class BuildingModelBlockStates {
    private BuildingModelBlockStates() {}

    /** matrix按Minecraft XYZ逐行排列；每行每列恰好一个±1，表示绕方块中心的直角旋转／镜像。 */
    public static JsonObject transform(JsonObject materialState, int[] matrix) {
        int[] checked = matrix(matrix);
        BlockState before = resolve(materialState);
        BlockState after = checked[4] == 1 ? horizontal(before, checked) : BuildingModelTiltStates.transform(before, checked);
        verifySpatial(before, after, checked);
        JsonObject authored = materialState.has("properties") ? materialState.getAsJsonObject("properties") : new JsonObject();
        Set<String> required = new LinkedHashSet<>();
        // 显式north=false也要移到对应连接面；不能留在旧north键上，更不能补齐所有未声明的连接开关。
        for (String property : authored.keySet()) required.add(mappedProperty(before, property, checked).getName());
        BlockState defaults = before.getBlock().defaultBlockState();
        for (Property<?> property : after.getProperties())
            if (!after.getValue(property).equals(defaults.getValue(property))) required.add(property.getName());
        JsonObject properties = new JsonObject();
        for (String key : required) {
            Property<?> property = after.getBlock().getStateDefinition().getProperty(key);
            properties.addProperty(key, name(property, after.getValue(property)));
        }
        JsonObject result = materialState.deepCopy();
        if (!properties.isEmpty() || materialState.has("properties")) result.add("properties", properties);
        // 稀疏输出仍必须还原同一原生状态；未变的open/powered/waterlogged默认值继续留给作者与施工规则。
        if (!resolve(result).equals(after)) throw unsupported(before, "sparse material properties cannot represent the transformed native state");
        return result;
    }

    static BlockState horizontal(BlockState state, int[] matrix) {
        String failure = "native rotation/mirror cannot express the requested directions";
        for (Rotation first : Rotation.values()) for (Mirror mirror : Mirror.values()) for (Rotation last : Rotation.values()) {
            boolean matches = true;
            for (Direction direction : Direction.values())
                if (last.rotate(mirror.mirror(first.rotate(direction))) != direction(matrix, direction)) { matches = false; break; }
            if (!matches) continue;
            try {
                BlockState transformed = state.rotate(first).mirror(mirror).rotate(last);
                verifySpatial(state, transformed, matrix);
                // 原生角楼梯镜像存在不等价组合；只选择与真实体素形状一致的原生组合，不猜shape枚举值。
                if (state.getBlock() instanceof StairBlock && !BuildingModelTiltStates.shapesMatch(state, transformed, matrix)) continue;
                return transformed;
            } catch (RuntimeException | LinkageError invalid) { failure = invalid.getMessage(); }
        }
        throw unsupported(state, failure == null ? "native transform is unavailable" : failure);
    }

    static void verifySpatial(BlockState before, BlockState after, int[] matrix) {
        if (after == null || before.getBlock() != after.getBlock()) throw unsupported(before, "native transform changed block identity");
        for (Property<?> property : before.getProperties()) {
            Comparable<?> value = before.getValue(property);
            if (!(value instanceof Direction) && !(value instanceof Direction.Axis) && namedDirection(property.getName()) == null) continue;
            Property<?> target = mappedProperty(before, property.getName(), matrix);
            Comparable<?> expected = mappedValue(value, matrix);
            if (!target.getPossibleValues().contains(expected) || !after.getValue(target).equals(expected))
                throw unsupported(before, "native transform does not preserve " + property.getName() + " -> " + target.getName() + "=" + expected);
        }
    }

    static Property<?> mappedProperty(BlockState state, String property, int[] matrix) {
        Direction side = namedDirection(property);
        String target = side == null ? property : direction(matrix, side).getName();
        Property<?> found = state.getBlock().getStateDefinition().getProperty(target);
        if (found == null) throw unsupported(state, "connection face " + property + " has no representable " + target + " property");
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
        if (input == null || input.length != 9) throw new IllegalArgumentException("model state transform requires a 3x3 signed permutation matrix");
        int[] matrix = input.clone();
        for (int index = 0; index < 3; index++) {
            int row = 0, column = 0;
            for (int other = 0; other < 3; other++) {
                if (matrix[index * 3 + other] < -1 || matrix[index * 3 + other] > 1)
                    throw new IllegalArgumentException("model state transform matrix entries must be -1, 0 or 1");
                row += Math.abs(matrix[index * 3 + other]); column += Math.abs(matrix[other * 3 + index]);
            }
            if (row != 1 || column != 1) throw new IllegalArgumentException("model state transform matrix must preserve one signed axis per row and column");
        }
        return matrix;
    }

    static BlockState resolve(JsonObject material) {
        if (material == null || !Set.of("block_id", "properties").containsAll(material.keySet()))
            throw new IllegalArgumentException("model material accepts block_id and properties only");
        String id = BuildingSceneGeometry.string(material.get("block_id"), 256, "block_id");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("model material needs an exact namespaced block_id");
        BlockState state = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown model material: " + id)).defaultBlockState();
        if (material.has("properties")) {
            JsonObject properties = BuildingSceneGeometry.object(material.get("properties"), "properties");
            if (properties.size() > 32) throw unsupported(state, "too many authored properties");
            for (var entry : properties.entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null) throw unsupported(state, "unknown authored property: " + entry.getKey());
                state = parse(state, property, BuildingSceneGeometry.string(entry.getValue(), 128, "property value"));
            }
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState parse(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> unsupported(state, "invalid " + property.getName() + "=" + value)));
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static BlockState put(BlockState state, Property property, Comparable value) {
        if (!property.getPossibleValues().contains(value)) throw unsupported(state, "cannot express " + property.getName() + "=" + value);
        return state.setValue(property, value);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static String name(Property property, Comparable value) { return property.getName(value); }
    static IllegalArgumentException unsupported(BlockState state, String reason) {
        return new IllegalArgumentException("Cannot transform local material " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ": " + reason);
    }
}

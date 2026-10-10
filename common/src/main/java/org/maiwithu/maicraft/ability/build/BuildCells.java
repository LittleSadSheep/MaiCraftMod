// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 逐格蓝图与单格参数变成计划格：每项 offset [x,y,z]、block、可选 properties；block 为 minecraft:air 表示清空，
 * 水源、岩浆源表示倒桶。点名的属性就是验收标准；写错一项整份拒绝，错误带下标。
 */
final class BuildCells {

    private BuildCells() {}

    /** cells 参数（JSON 数组）变成相对锚点的计划格。 */
    static List<PlannedCell> fromArray(JsonElement cells) {
        if (!cells.isJsonArray() || cells.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("cells 要是至少一项的数组");
        JsonArray array = cells.getAsJsonArray();
        List<PlannedCell> out = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement entry = array.get(index);
            if (!entry.isJsonObject()) throw new IllegalArgumentException("cells[" + index + "] 要是对象");
            try {
                out.add(cell(entry.getAsJsonObject()));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("cells[" + index + "]：" + invalid.getMessage(), invalid);
            }
        }
        return out;
    }

    /** 单格：block 配 properties，放在锚点那一格。 */
    static PlannedCell single(String blockId, JsonElement properties) {
        JsonObject spec = new JsonObject();
        JsonArray offset = new JsonArray();
        offset.add(0);
        offset.add(0);
        offset.add(0);
        spec.add("offset", offset);
        spec.addProperty("block", blockId);
        if (properties != null) spec.add("properties", properties);
        return cell(spec);
    }

    private static PlannedCell cell(JsonObject spec) {
        for (String key : spec.keySet()) {
            if (!Set.of("offset", "block", "properties").contains(key)) throw new IllegalArgumentException("不认识的字段 " + key + "；可用：offset, block, properties");
        }
        JsonElement offset = spec.get("offset");
        if (offset == null || !offset.isJsonArray() || offset.getAsJsonArray().size() != 3) throw new IllegalArgumentException("offset 要是 [x, y, z]");
        int[] xyz = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            JsonElement value = offset.getAsJsonArray().get(axis);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsDouble() != Math.rint(value.getAsDouble())) {
                throw new IllegalArgumentException("offset 要是整数");
            }
            xyz[axis] = value.getAsInt();
        }
        BlockPos pos = new BlockPos(xyz[0], xyz[1], xyz[2]);
        if (spec.get("block") == null || !spec.get("block").isJsonPrimitive()) throw new IllegalArgumentException("要写 block");
        String blockId = spec.get("block").getAsString();
        if (blockId.startsWith("#")) throw new IllegalArgumentException("block 要写具体的方块，不能写标签：" + blockId);
        ResourceLocation key = ResourceLocation.tryParse(blockId);
        if (key == null) throw new IllegalArgumentException("方块 ID 写法不对：" + blockId);
        Block block = BuiltInRegistries.BLOCK.getOptional(key).orElseThrow(() -> new IllegalArgumentException("没有这个方块：" + blockId));
        BlockState state = block.defaultBlockState();
        Set<String> required = new LinkedHashSet<>();
        if (spec.has("properties")) {
            JsonElement properties = spec.get("properties");
            if (!properties.isJsonObject()) throw new IllegalArgumentException("properties 要是对象");
            for (var entry : properties.getAsJsonObject().entrySet()) {
                Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                if (property == null) throw new IllegalArgumentException(blockId + " 没有属性 " + entry.getKey());
                state = with(state, property, entry.getValue().getAsString());
                required.add(entry.getKey());
            }
        }
        return PlannedCell.block(pos, state, required);
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value)
                .orElseThrow(() -> new IllegalArgumentException("属性 " + property.getName() + " 没有取值 " + value)));
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 机器蓝图的正文解析：LLM 写的 JSON 一次读成蓝图，错了把每一条都报出来，不进游戏。
 *
 * <p>正文长这样（全部字段可选，但至少要有 cells）：
 * <pre>{ "cells": [{"offset": [0,0,0], "block": "create:mechanical_press", "properties": {"facing": "up"}}],
 *        "parts": [{"offset": [1,0,0], "side": "north", "item": "ae2:terminal"}],
 *        "installations": [{"kind": "create:belt", "offsets": [[0,0,1], [0,0,2]]}],
 *        "settings": [{"offset": [2,0,0], "key": "side.north", "value": "output:items"}],
 *        "processes": [{"offset": [0,0,0], "item": "create:iron_sheet"}] }</pre>
 *
 * <p>block 写 minecraft:air 是清空，写 minecraft:water / lava 是倒桶，与建造的逐格清单同一套规则。
 */
final class MachineBlueprints {

    private MachineBlueprints() {
    }

    /** 解析一份蓝图正文；写错的地方一次报全（IllegalArgumentException，消息以「；」相连）。 */
    static MachineBlueprint parse(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            throw new IllegalArgumentException("blueprint 要是对象");
        }
        JsonObject body = json.getAsJsonObject();
        List<String> errors = new ArrayList<>();
        for (String key : body.keySet()) {
            if (!Set.of("cells", "parts", "installations", "settings", "processes").contains(key)) {
                errors.add("不认识的字段 " + key + "；可用：cells, parts, installations, settings, processes");
            }
        }
        List<PlannedCell> cells = body.has("cells")
                ? cells(body.get("cells"), errors) : List.of();
        List<MachineBlueprint.Part> parts = body.has("parts")
                ? parts(body.get("parts"), errors) : List.of();
        List<MachineBlueprint.Segment> installations = body.has("installations")
                ? installations(body.get("installations"), errors) : List.of();
        List<MachineBlueprint.Setting> settings = body.has("settings")
                ? settings(body.get("settings"), errors) : List.of();
        List<MachineBlueprint.Process> processes = body.has("processes")
                ? processes(body.get("processes"), errors) : List.of();
        if (cells.isEmpty()) {
            errors.add("cells 要是至少一项的数组：机器蓝图至少要说清放哪些方块");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
        return new MachineBlueprint(cells, parts, installations, settings, processes);
    }

    /** 逐格清单：每项 offset [x,y,z]、block、可选 properties；同一格写两次整份拒绝。 */
    private static List<PlannedCell> cells(JsonElement element, List<String> errors) {
        if (!element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            errors.add("cells 要是至少一项的数组");
            return List.of();
        }
        Set<BlockPos> seen = new HashSet<>();
        List<PlannedCell> out = new ArrayList<>();
        var array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            try {
                PlannedCell cell = cell(array.get(index));
                if (!seen.add(cell.pos())) {
                    throw new IllegalArgumentException("同一格写了两次：" + cell.pos().toShortString());
                }
                out.add(cell);
            } catch (IllegalArgumentException invalid) {
                errors.add("cells[" + index + "]：" + invalid.getMessage());
            }
        }
        return out;
    }

    private static PlannedCell cell(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("要是对象");
        }
        JsonObject spec = element.getAsJsonObject();
        for (String key : spec.keySet()) {
            if (!Set.of("offset", "block", "properties").contains(key)) {
                throw new IllegalArgumentException("不认识的字段 " + key + "；可用：offset, block, properties");
            }
        }
        BlockPos pos = offset(spec.get("offset"));
        if (spec.get("block") == null || !spec.get("block").isJsonPrimitive()) {
            throw new IllegalArgumentException("要写 block");
        }
        String blockId = spec.get("block").getAsString();
        if (blockId.startsWith("#")) {
            throw new IllegalArgumentException("block 要写具体的方块，不能写标签：" + blockId);
        }
        Block block = blockOf(blockId);
        BlockState state = block.defaultBlockState();
        Set<String> required = new HashSet<>();
        if (spec.has("properties")) {
            JsonElement properties = spec.get("properties");
            if (!properties.isJsonObject()) {
                throw new IllegalArgumentException("properties 要是对象");
            }
            for (var entry : properties.getAsJsonObject().entrySet()) {
                Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
                if (property == null) {
                    throw new IllegalArgumentException(blockId + " 没有属性 " + entry.getKey());
                }
                state = with(state, property, entry.getValue().getAsString());
                required.add(entry.getKey());
            }
        }
        // 清空、倒桶在计划格里换算：空气格是清空，水源与岩浆源是倒桶，其余是放方块。
        return PlannedCell.block(pos, state, required);
    }

    /** 部件条目：宿主 offset、side（六向之一）、部件物品。 */
    private static List<MachineBlueprint.Part> parts(JsonElement element, List<String> errors) {
        if (!element.isJsonArray()) {
            errors.add("parts 要是数组");
            return List.of();
        }
        List<MachineBlueprint.Part> out = new ArrayList<>();
        var array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            try {
                JsonObject spec = objectAt(array.get(index), "parts[" + index + "]");
                for (String key : spec.keySet()) {
                    if (!Set.of("offset", "side", "item").contains(key)) {
                        throw new IllegalArgumentException("不认识的字段 " + key + "；可用：offset, side, item");
                    }
                }
                BlockPos host = offset(spec.get("offset"));
                Direction side = spec.has("side")
                        ? Direction.byName(spec.get("side").getAsString().toLowerCase(Locale.ROOT))
                        : Direction.NORTH;
                if (side == null) {
                    throw new IllegalArgumentException("side 要是 six 面之一：north/south/east/west/up/down");
                }
                if (spec.get("item") == null || !spec.get("item").isJsonPrimitive()) {
                    throw new IllegalArgumentException("要写 item");
                }
                String itemId = spec.get("item").getAsString();
                if (BuiltInRegistries.ITEM.getOptional(key(itemId)).isEmpty()) {
                    throw new IllegalArgumentException("没有这个物品：" + itemId);
                }
                out.add(new MachineBlueprint.Part(host, side, itemId));
            } catch (IllegalArgumentException invalid) {
                errors.add(invalid.getMessage());
            }
        }
        return out;
    }

    /** 安装段条目：种类加至少一格相对位置。 */
    private static List<MachineBlueprint.Segment> installations(JsonElement element, List<String> errors) {
        if (!element.isJsonArray()) {
            errors.add("installations 要是数组");
            return List.of();
        }
        List<MachineBlueprint.Segment> out = new ArrayList<>();
        var array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            try {
                JsonObject spec = objectAt(array.get(index), "installations[" + index + "]");
                for (String key : spec.keySet()) {
                    if (!Set.of("kind", "offsets").contains(key)) {
                        throw new IllegalArgumentException("不认识的字段 " + key + "；可用：kind, offsets");
                    }
                }
                if (spec.get("kind") == null || !spec.get("kind").isJsonPrimitive()) {
                    throw new IllegalArgumentException("要写 kind，例如 create:belt");
                }
                String kind = spec.get("kind").getAsString();
                if (ResourceLocation.tryParse(kind) == null) {
                    throw new IllegalArgumentException("kind 要是命名空间 ID：" + kind);
                }
                JsonElement offsets = spec.get("offsets");
                if (offsets == null || !offsets.isJsonArray() || offsets.getAsJsonArray().isEmpty()) {
                    throw new IllegalArgumentException("offsets 要是至少一项的数组");
                }
                List<BlockPos> cells = new ArrayList<>();
                for (JsonElement one : offsets.getAsJsonArray()) {
                    cells.add(offset(one));
                }
                out.add(new MachineBlueprint.Segment(kind, cells));
            } catch (IllegalArgumentException invalid) {
                errors.add(invalid.getMessage());
            }
        }
        return out;
    }

    /** 安装后设置：offset、key、value；键值是什么由认领那格的机器类型定，这里只查形状。 */
    private static List<MachineBlueprint.Setting> settings(JsonElement element, List<String> errors) {
        if (!element.isJsonArray()) {
            errors.add("settings 要是数组");
            return List.of();
        }
        List<MachineBlueprint.Setting> out = new ArrayList<>();
        var array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            try {
                JsonObject spec = objectAt(array.get(index), "settings[" + index + "]");
                for (String key : spec.keySet()) {
                    if (!Set.of("offset", "key", "value").contains(key)) {
                        throw new IllegalArgumentException("不认识的字段 " + key + "；可用：offset, key, value");
                    }
                }
                BlockPos at = offset(spec.get("offset"));
                if (spec.get("key") == null || !spec.get("key").isJsonPrimitive()
                        || spec.get("key").getAsString().isBlank()) {
                    throw new IllegalArgumentException("要写 key");
                }
                if (spec.get("value") == null || !spec.get("value").isJsonPrimitive()) {
                    throw new IllegalArgumentException("value 要写字符串");
                }
                out.add(new MachineBlueprint.Setting(at, spec.get("key").getAsString(),
                        spec.get("value").getAsString()));
            } catch (IllegalArgumentException invalid) {
                errors.add(invalid.getMessage());
            }
        }
        return out;
    }

    /** 声明工序：offset 加产物；产物要是注册过的物品。 */
    private static List<MachineBlueprint.Process> processes(JsonElement element, List<String> errors) {
        if (!element.isJsonArray()) {
            errors.add("processes 要是数组");
            return List.of();
        }
        List<MachineBlueprint.Process> out = new ArrayList<>();
        var array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            try {
                JsonObject spec = objectAt(array.get(index), "processes[" + index + "]");
                for (String key : spec.keySet()) {
                    if (!Set.of("offset", "item").contains(key)) {
                        throw new IllegalArgumentException("不认识的字段 " + key + "；可用：offset, item");
                    }
                }
                BlockPos at = offset(spec.get("offset"));
                if (spec.get("item") == null || !spec.get("item").isJsonPrimitive()) {
                    throw new IllegalArgumentException("要写 item");
                }
                String itemId = spec.get("item").getAsString();
                if (BuiltInRegistries.ITEM.getOptional(key(itemId)).isEmpty()) {
                    throw new IllegalArgumentException("没有这个物品：" + itemId);
                }
                out.add(new MachineBlueprint.Process(at, itemId));
            } catch (IllegalArgumentException invalid) {
                errors.add(invalid.getMessage());
            }
        }
        return out;
    }

    private static JsonObject objectAt(JsonElement element, String where) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(where + " 要是对象");
        }
        return element.getAsJsonObject();
    }

    /** [x,y,z] 整数数组换格子。 */
    private static BlockPos offset(JsonElement element) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("offset 要是 [x, y, z]");
        }
        int[] xyz = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            JsonElement value = element.getAsJsonArray().get(axis);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || value.getAsDouble() != Math.rint(value.getAsDouble())) {
                throw new IllegalArgumentException("offset 要是整数");
            }
            xyz[axis] = value.getAsInt();
        }
        return new BlockPos(xyz[0], xyz[1], xyz[2]);
    }

    private static Block blockOf(String blockId) {
        ResourceLocation key = ResourceLocation.tryParse(blockId);
        if (key == null) {
            throw new IllegalArgumentException("方块 ID 写法不对：" + blockId);
        }
        return BuiltInRegistries.BLOCK.getOptional(key)
                .orElseThrow(() -> new IllegalArgumentException("没有这个方块：" + blockId));
    }

    private static ResourceLocation key(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            throw new IllegalArgumentException("ID 写法不对：" + id);
        }
        return parsed;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value)
                .orElseThrow(() -> new IllegalArgumentException("属性 " + property.getName() + " 没有取值 " + value)));
    }
}

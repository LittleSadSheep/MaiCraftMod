// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.ability.design.api.CompiledDesign;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/** 给设计测试用的图纸样本：固定的材料表、几种对象的写法，以及把编译结果按格查看的小工具。只生成作者输入，不碰世界。 */
final class DesignSamples {

    private DesignSamples() {}

    static JsonObject drawing(JsonObject... nodes) {
        JsonObject drawing = json("""
                {"coordinate_system":"minecraft_y_up","materials":{
                 "Body":{"block_id":"minecraft:stone"},"Glass":{"block_id":"minecraft:glass"},
                 "Trim":{"block_id":"minecraft:quartz_block"},"Accent":{"block_id":"minecraft:gold_block"},
                 "Top":{"block_id":"minecraft:red_concrete"},"Wood":{"block_id":"minecraft:oak_log","properties":{"axis":"z"}},
                 "Stair":{"block_id":"minecraft:oak_stairs","properties":{"facing":"north","half":"bottom"}}},"objects":[]}
                """);
        for (var node : nodes) drawing.getAsJsonArray("objects").add(node);
        return drawing;
    }

    static JsonObject mesh(String name, String primitive, double[] center, int[] size, String material) {
        JsonObject node = new JsonObject();
        node.addProperty("name", name);
        node.addProperty("type", "MESH");
        node.addProperty("primitive", primitive);
        JsonArray location = new JsonArray();
        JsonArray dimensions = new JsonArray();
        for (double value : center) location.add(value);
        for (int value : size) dimensions.add(value);
        node.add("location", location);
        node.add("dimensions", dimensions);
        if (material != null) node.addProperty("material", material);
        return node;
    }

    static JsonObject instance(String name, String component, int x, int y, int z) {
        JsonObject node = new JsonObject();
        node.addProperty("name", name);
        node.addProperty("type", "INSTANCE");
        node.addProperty("component", component);
        node.add("location", JsonParser.parseString("[" + x + "," + y + "," + z + "]"));
        return node;
    }

    static JsonObject roof(String name, double[] center, int[] footprint, String material) {
        JsonObject node = new JsonObject();
        node.addProperty("name", name);
        node.addProperty("type", "ROOF");
        JsonArray location = new JsonArray();
        for (double value : center) location.add(value);
        JsonArray size = new JsonArray();
        for (int value : footprint) size.add(value);
        node.add("location", location);
        node.add("footprint", size);
        node.addProperty("material", material);
        return node;
    }

    static void component(JsonObject drawing, String name, JsonObject... nodes) {
        if (!drawing.has("components")) drawing.add("components", new JsonObject());
        JsonArray objects = new JsonArray();
        for (var node : nodes) objects.add(node);
        JsonObject component = new JsonObject();
        component.add("objects", objects);
        drawing.getAsJsonObject("components").add(name, component);
    }

    static CompiledDesign compile(JsonObject drawing) {
        return DesignCompiler.compile(drawing);
    }

    static Map<BlockPos, PlannedCell> cells(JsonObject drawing) {
        Map<BlockPos, PlannedCell> result = new LinkedHashMap<>();
        for (PlannedCell cell : compile(drawing).cells()) result.put(cell.pos(), cell);
        return result;
    }

    static PlannedCell at(Map<BlockPos, PlannedCell> cells, int x, int y, int z) {
        return cells.get(new BlockPos(x, y, z));
    }

    /** 这一格的方块 ID；没声明的格是 unspecified。 */
    static String block(Map<BlockPos, PlannedCell> cells, int x, int y, int z) {
        PlannedCell cell = at(cells, x, y, z);
        return cell == null ? "unspecified" : BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString();
    }

    static long count(Map<BlockPos, PlannedCell> cells, String block) {
        return cells.values().stream().filter(cell -> BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString().equals(block)).count();
    }

    static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    static JsonArray jsonArray(String value) {
        return JsonParser.parseString(value).getAsJsonArray();
    }
}

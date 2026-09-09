// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.build.BuildShapes;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.tools.work.BuildTool;

/**
 * 保存和恢复逐格施工要求：方块、物品、绝对坐标、状态、方向提示和精确验收选项。空气保留为明确清空目标。
 */
public final class BuildProjectTargets {
    private BuildProjectTargets() {}

    // 把每格展开成可写入 JSON 的字段，属性名和属性值都使用注册定义中的名字，不保存对象引用。
    public static JsonArray encode(List<BuildTaskRecord.Target> targets) {
        JsonArray result = new JsonArray();
        for (var target : targets) {
            JsonObject row = new JsonObject();
            row.addProperty("op", "set");
            row.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(target.block()).toString());
            row.addProperty("item_id", BuiltInRegistries.ITEM.getKey(target.item()).toString());
            row.addProperty("x", target.pos().getX()); row.addProperty("y", target.pos().getY());
            row.addProperty("z", target.pos().getZ());
            JsonObject properties = new JsonObject();
            target.desiredState().getValues().forEach((property, value) ->
                    properties.addProperty(property.getName(), name(property, value)));
            row.add("properties", properties);
            if (target.facing() != null) row.addProperty("facing", target.facing().getName());
            if (target.axis() != null) row.addProperty("axis", target.axis().getName());
            if (target.topHalf() != null) row.addProperty("half", target.topHalf() ? "top" : "bottom");
            row.addProperty("item_place", target.itemPlace());
            row.addProperty("strict_identity", target.strictIdentity());
            JsonArray exact = new JsonArray();
            target.exactProperties().stream().sorted().forEach(exact::add);
            row.add("exact_properties", exact);
            if (target.finalProperties() != null) {
                JsonArray required = new JsonArray(); target.finalProperties().stream().sorted().forEach(required::add);
                row.add("final_properties", required);
            }
            result.add(row);
        }
        return result;
    }

    // 先查材料注册名，再复用建造解析。重复坐标、材料与方块不对应、状态被解析器改写时都拒绝恢复。
    public static List<BuildTaskRecord.Target> decode(JsonArray rows) {
        if (rows == null || rows.isEmpty() || rows.size() > BuildShapes.MAX_TOTAL_CELLS)
            throw new IllegalArgumentException("invalid saved build target count");
        for (var value : rows) {
            ResourceLocation block = ResourceLocation.parse(value.getAsJsonObject().get("block_id").getAsString());
            if (!BuiltInRegistries.BLOCK.containsKey(block))
                throw new IllegalArgumentException("saved build block is unavailable: " + block);
        }
        List<BuildTaskRecord.Target> parsed = BuildTool.resolvedTargets(rows);
        if (parsed.size() != rows.size()) throw new IllegalArgumentException("duplicate saved build cells");
        List<BuildTaskRecord.Target> result = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            var target = parsed.get(i);
            JsonObject row = rows.get(i).getAsJsonObject();
            if (!BuiltInRegistries.BLOCK.getKey(target.block()).toString().equals(row.get("block_id").getAsString()))
                throw new IllegalArgumentException("saved build block identity was normalized");
            for (var entry : row.getAsJsonObject("properties").entrySet()) {
                Property<?> property = target.block().getStateDefinition().getProperty(entry.getKey());
                if (property == null || !entry.getValue().getAsString().equals(
                        name(property, target.desiredState().getValue(property))))
                    throw new IllegalArgumentException("saved build state was normalized or is no longer supported");
            }
            ResourceLocation item = ResourceLocation.parse(row.get("item_id").getAsString());
            if (!BuiltInRegistries.ITEM.containsKey(item)) throw new IllegalArgumentException("saved build item is unavailable");
            if (BuiltInRegistries.ITEM.get(item) != target.item())
                throw new IllegalArgumentException("saved build material does not match its block");
            // 普通解析完成后，再恢复原来明确要求精确比较的属性及物品放置模式，避免续建时验收标准变宽。
            LinkedHashSet<String> exact = new LinkedHashSet<>();
            row.getAsJsonArray("exact_properties").forEach(value -> exact.add(value.getAsString()));
            java.util.Set<String> finalProperties = null;
            if (row.has("final_properties")) {
                var names = new LinkedHashSet<String>(); row.getAsJsonArray("final_properties").forEach(value -> names.add(value.getAsString()));
                finalProperties = names;
            } else if (row.get("strict_identity").getAsBoolean()) {
                // 旧工程没有分开保存最终属性时，把原来明确指定的属性迁移为最终要求；不把这些要求丢掉，也不在施工前强制替换。
                finalProperties = java.util.Set.copyOf(exact);
            }
            result.add(new BuildTaskRecord.Target(target.desiredState(), BuiltInRegistries.ITEM.get(item),
                    target.pos(), target.label(), target.facing(), target.axis(), target.topHalf(),
                    row.get("item_place").getAsBoolean(), exact, row.get("strict_identity").getAsBoolean(), finalProperties));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String name(Property property, Comparable value) { return property.getName(value); }
}

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

/** Preserve placement semantics alongside exact states; air remains an explicit removal target. */
public final class BuildProjectTargets {
    private BuildProjectTargets() {}

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
            result.add(row);
        }
        return result;
    }

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
            LinkedHashSet<String> exact = new LinkedHashSet<>();
            row.getAsJsonArray("exact_properties").forEach(value -> exact.add(value.getAsString()));
            result.add(new BuildTaskRecord.Target(target.desiredState(), BuiltInRegistries.ITEM.get(item),
                    target.pos(), target.label(), target.facing(), target.axis(), target.topHalf(),
                    row.get("item_place").getAsBoolean(), exact, row.get("strict_identity").getAsBoolean()));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String name(Property property, Comparable value) { return property.getName(value); }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.build.BuildStates;

/** One interpretation of material defaults for preview, construction and exported blueprints. */
public final class BuildingSceneBlocks {
    private BuildingSceneBlocks() {}

    public static BlockState resolve(JsonObject cell) {
        if (!cell.has("block_id")) throw new IllegalArgumentException("Model material requires an ordinary block_id");
        String id = cell.get("block_id").getAsString();
        var key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("Unknown model material: " + id);
        BlockState state = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
        JsonObject properties = cell.has("properties") ? cell.getAsJsonObject("properties") : new JsonObject();
        for (var property : properties.entrySet()) state = property(state, property.getKey(), property.getValue().getAsString());
        BlockState normalized = BuildStates.normalize(state);
        if (normalized.getBlock() != state.getBlock()) throw new IllegalArgumentException("Unsupported model block state: " + id);
        for (String property : properties.keySet()) {
            var definition = state.getBlock().getStateDefinition().getProperty(property);
            if (!state.getValue(definition).equals(normalized.getValue(definition)))
                throw new IllegalArgumentException("Construction cannot preserve " + id + "." + property);
        }
        return normalized;
    }

    /** Record defaults changed by construction, without inventing constraints for dynamic properties. */
    public static JsonObject export(JsonObject blueprint) {
        JsonObject result = blueprint.deepCopy();
        for (var value : result.getAsJsonArray("blocks")) {
            JsonObject cell = value.getAsJsonObject();
            BlockState state = resolve(cell), defaults = state.getBlock().defaultBlockState();
            JsonObject properties = cell.has("properties") ? cell.getAsJsonObject("properties") : new JsonObject();
            state.getValues().forEach((property, actual) -> {
                if (!actual.equals(defaults.getValue(property))) properties.addProperty(property.getName(), name(property, actual));
            });
            cell.add("properties", properties);
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String name(Property property, Comparable value) { return property.getName(value); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState property(BlockState state, String name, String value) {
        Property property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) throw new IllegalArgumentException("Unknown block property: " + name);
        var parsed = property.getValue(value);
        if (parsed.isEmpty()) throw new IllegalArgumentException("Invalid block property: " + name + "=" + value);
        return state.setValue(property, (Comparable) parsed.get());
    }
}

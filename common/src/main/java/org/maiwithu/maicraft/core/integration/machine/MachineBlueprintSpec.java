// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded wire parsing, deliberately independent of Minecraft and registry initialization. */
final class MachineBlueprintSpec {
    static final int MAX_BLOCKS = 512;
    record Offset(int x, int y, int z) {}
    record Cell(Offset offset, String blockId, Map<String, String> properties) {}

    private MachineBlueprintSpec() {}

    static List<Cell> parse(JsonObject blueprint, int radius) {
        if (radius < 0 || radius > MachineSurveyModel.MAX_RADIUS) {
            throw new IllegalArgumentException("machine blueprint radius must be 0..8");
        }
        if (blueprint == null) throw new IllegalArgumentException("blueprint is required");
        keys(blueprint, Set.of("blocks"), "blueprint");
        if (!blueprint.has("blocks") || !blueprint.get("blocks").isJsonArray()) {
            throw new IllegalArgumentException("blueprint.blocks must be an array");
        }
        JsonArray blocks = blueprint.getAsJsonArray("blocks");
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("blueprint.blocks must contain 1..512 cells");
        }
        Set<Offset> occupied = new HashSet<>();
        List<Cell> out = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            if (!blocks.get(index).isJsonObject()) throw new IllegalArgumentException("blueprint block must be an object: " + index);
            JsonObject block = blocks.get(index).getAsJsonObject();
            keys(block, Set.of("offset", "block_id", "properties"), "blueprint block " + index);
            if (!block.has("offset") || !block.get("offset").isJsonArray()
                    || block.getAsJsonArray("offset").size() != 3) {
                throw new IllegalArgumentException("block offset must be [dx,dy,dz]: " + index);
            }
            JsonArray xyz = block.getAsJsonArray("offset");
            Offset offset = new Offset(integer(xyz.get(0), radius), integer(xyz.get(1), radius), integer(xyz.get(2), radius));
            if (!occupied.add(offset)) throw new IllegalArgumentException("duplicate blueprint offset: " + offset);
            String id = string(block.get("block_id"), 256, "block_id");
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("block_id must be one namespaced registry ID: " + id);
            }
            Map<String, String> properties = new LinkedHashMap<>();
            if (block.has("properties")) {
                if (!block.get("properties").isJsonObject()) throw new IllegalArgumentException("properties must be an object");
                JsonObject values = block.getAsJsonObject("properties");
                if (values.size() > 32) throw new IllegalArgumentException("at most 32 explicit properties per block");
                for (Map.Entry<String, JsonElement> property : values.entrySet()) {
                    if (property.getKey().isBlank() || property.getKey().length() > 64) {
                        throw new IllegalArgumentException("invalid property name");
                    }
                    properties.put(property.getKey(), string(property.getValue(), 128, "property value"));
                }
            }
            out.add(new Cell(offset, id, Map.copyOf(properties)));
        }
        return List.copyOf(out);
    }

    private static int integer(JsonElement value, int radius) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("offset coordinates must be integers");
        }
        try {
            int coordinate = value.getAsBigDecimal().intValueExact();
            if (coordinate < -radius || coordinate > radius) {
                throw new IllegalArgumentException("blueprint offset extends outside the surveyed radius");
            }
            return coordinate;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("offset coordinates must be bounded integers", invalid);
        }
    }

    private static String string(JsonElement value, int limit, String label) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        String result = value.getAsString();
        if (result.isBlank() || result.length() > limit) throw new IllegalArgumentException("invalid " + label);
        return result;
    }

    private static void keys(JsonObject value, Set<String> allowed, String label) {
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("unsupported " + label + " field: " + key);
        }
    }
}

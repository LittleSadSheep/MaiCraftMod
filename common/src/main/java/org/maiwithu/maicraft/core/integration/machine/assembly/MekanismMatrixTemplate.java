// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.function.Predicate;

/** A complete bounded induction matrix layout, following Mekanism's cuboid/MatrixValidator rules. */
public final class MekanismMatrixTemplate {
    private MekanismMatrixTemplate() {}

    /**
     * Coordinates start at the lower northwest corner. Two interior cells are necessary for one
     * storage cell and one provider; a 3x3x3 cube cannot contain both. Ports never replace frame edges.
     * The caller supplies registered block/item evidence and executes configuration after building.
     */
    public static JsonObject compile(String tier, Predicate<String> blockExists, Predicate<String> itemExists) {
        return compile(tier, new JsonObject(), blockExists, itemExists);
    }

    public static JsonObject compile(String tier, JsonObject options,
            Predicate<String> blockExists, Predicate<String> itemExists) {
        if (!Set.of("basic", "advanced", "elite", "ultimate").contains(tier)) {
            throw new IllegalArgumentException("matrix tier must be basic, advanced, elite or ultimate");
        }
        String cell = "mekanism:" + tier + "_induction_cell";
        String provider = "mekanism:" + tier + "_induction_provider";
        for (String id : Set.of("mekanism:induction_casing", "mekanism:induction_port", cell, provider)) {
            if (!blockExists.test(id) || !itemExists.test(id)) throw new IllegalArgumentException("matrix component is not installed: " + id);
        }
        if (!itemExists.test("mekanism:configurator")) throw new IllegalArgumentException("matrix output requires an installed configurator");
        for (String key : options.keySet()) {
            if (!Set.of("width", "height", "depth", "cell_count", "provider_count").contains(key)) {
                throw new IllegalArgumentException("unknown matrix module option: " + key);
            }
        }
        // Mekanism CuboidStructureValidator's own bounds are 3..18 on each axis.
        int width = integer(options, "width", 3, 3, 18), height = integer(options, "height", 3, 3, 18);
        int depth = integer(options, "depth", 4, 3, 18);
        int capacity = (width - 2) * (height - 2) * (depth - 2);
        int cells = integer(options, "cell_count", 1, 1, capacity);
        int providers = integer(options, "provider_count", 1, 1, capacity);
        if (cells + providers > capacity) throw new IllegalArgumentException("matrix interior cannot contain requested cells and providers");
        boolean accessClearance = height >= 4 && capacity - cells - providers >= 2;
        JsonObject blueprint = new JsonObject();
        JsonArray blocks = new JsonArray();
        int internalIndex = 0;
        // Layer order places both internals before the roof seals access to the interior.
        for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            String id;
            if (x > 0 && x < width - 1 && y > 0 && y < height - 1 && z > 0 && z < depth - 1) {
                if (accessClearance && x == 1 && z == 1 && (y == 1 || y == 2)) id = "minecraft:air";
                else {
                    id = internalIndex < cells ? cell : internalIndex < cells + providers ? provider : "minecraft:air";
                    internalIndex++;
                }
            } else if (x == 1 && y == 1 && (z == 0 || z == depth - 1)) id = "mekanism:induction_port";
            else id = "mekanism:induction_casing";
            JsonObject block = new JsonObject(); block.add("offset", offset(x, y, z)); block.addProperty("block_id", id);
            blocks.add(block);
        }
        blueprint.add("blocks", blocks);
        JsonArray configurations = new JsonArray();
        configurations.add(configuration(1, 1, 0, "north", "input"));
        configurations.add(configuration(1, 1, depth - 1, "south", "output"));
        blueprint.add("configurations", configurations);
        JsonObject verification = new JsonObject();
        verification.addProperty("kind", "mekanism_induction_matrix");
        verification.add("min_offset", offset(0, 0, 0));
        verification.add("max_offset", offset(width - 1, height - 1, depth - 1));
        verification.addProperty("require_formed", true);
        verification.addProperty("require_input_output_modes", true);
        verification.addProperty("production_verified_by_geometry", false);
        blueprint.add("commissioning", verification);
        JsonObject ports = new JsonObject();
        ports.add("energy_input", port(1, 1, 0, "north"));
        ports.add("energy_output", port(1, 1, depth - 1, "south"));
        blueprint.add("ports", ports);
        JsonArray clearance = new JsonArray();
        if (accessClearance) { clearance.add(offset(1, 1, 1)); clearance.add(offset(1, 2, 1)); }
        blueprint.add("construction_clearance", clearance);
        blueprint.addProperty("template", "mekanism_induction_matrix");
        return blueprint;
    }

    private static int integer(JsonObject options, String key, int fallback, int min, int max) {
        if (!options.has(key)) return fallback;
        try {
            var value = options.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int parsed = value.getAsBigDecimal().intValueExact();
            if (parsed < min || parsed > max) throw new ArithmeticException();
            return parsed;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max); }
    }
    private static JsonObject port(int x, int y, int z, String face) {
        JsonObject result = new JsonObject(); result.add("offset", offset(x, y, z)); result.addProperty("face", face); return result;
    }

    private static JsonObject configuration(int x, int y, int z, String face, String mode) {
        JsonObject result = new JsonObject(); result.add("offset", offset(x, y, z));
        result.addProperty("face", face); result.addProperty("medium", "induction_port"); result.addProperty("mode", mode);
        return result;
    }
    private static JsonArray offset(int x, int y, int z) {
        JsonArray result = new JsonArray(); result.add(x); result.add(y); result.add(z); return result;
    }
}

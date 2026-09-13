// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;

/** Declared passive boundaries, never generators or proof that a utility is connected. */
public final class MachineUtilityInputs {
    public static final Set<String> MEDIA = Set.of("kinetic", "energy", "fluids", "chemicals", "items");
    private static final Set<String> REQUIREMENTS = Set.of("id", "medium", "minimum_rpm", "resource", "reason", "face");
    private MachineUtilityInputs() {}

    public record Input(String id, String medium, BlockPos offset, Direction face, String blockId,
                        Integer minimumRpm, String resource, String reason) {
        public Input { offset = offset.immutable(); }
        public JsonObject json() {
            JsonObject row = requirements(id, medium, face, minimumRpm, resource, reason);
            JsonArray at = new JsonArray(); at.add(offset.getX()); at.add(offset.getY()); at.add(offset.getZ());
            row.add("offset", at); row.addProperty("block_id", blockId); return row;
        }
    }

    public record Declaration(String id, String medium, List<String> consumers, Direction face,
                              Integer minimumRpm, String resource, String reason) {
        public Declaration { consumers = List.copyOf(consumers); }
        public JsonObject json() {
            JsonObject row = requirements(id, medium, face, minimumRpm, resource, reason);
            JsonArray names = new JsonArray(); consumers.forEach(names::add); row.add("consumers", names); return row;
        }
        public Input at(BlockPos position) {
            return new Input(id, medium, position, face, connector(medium), minimumRpm, resource, reason);
        }
    }

    public static JsonArray json(List<Input> inputs) {
        JsonArray result = new JsonArray(); inputs.forEach(input -> result.add(input.json())); return result;
    }

    public static String supplyPreference(JsonObject document) {
        String preference = document.has("supply_preference") ? text(document, "supply_preference", 16) : "external";
        if (!Set.of("external", "onsite").contains(preference)) throw bad("supply_preference must be external or onsite");
        if (preference.equals("onsite")) text(document, "onsite_reason", 512);
        else if (document.has("onsite_reason")) throw bad("onsite_reason requires supply_preference=onsite");
        return preference;
    }

    public static List<Declaration> parseDesign(JsonObject design, Set<String> components) {
        supplyPreference(design);
        List<Declaration> result = new ArrayList<>();
        for (JsonElement element : rows(design)) {
            JsonObject row = object(element); keys(row, Set.of("consumers"));
            Input common = fields(row, BlockPos.ZERO, connector(text(row, "medium", 32)), false);
            JsonElement raw = row.get("consumers");
            if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()
                    || raw.getAsJsonArray().size() > MachinePlanningBudget.current().maxComponents())
                throw bad("external input consumers must be a bounded nonempty component-name array");
            List<String> consumers = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (JsonElement name : raw.getAsJsonArray()) {
                String value = text(name, "consumer", 64);
                if (!components.contains(value) || !seen.add(value)) throw bad("unknown or duplicate external input consumer: " + value);
                consumers.add(value);
            }
            result.add(new Declaration(common.id, common.medium, consumers, common.face, common.minimumRpm, common.resource, common.reason));
        }
        validateCounts(result.stream().map(d -> d.at(BlockPos.ZERO)).toList());
        return List.copyOf(result);
    }

    /** Validate concrete declarations against the exact declared block and an unobstructed exterior ray. */
    public static List<Input> parse(JsonObject blueprint) {
        supplyPreference(blueprint);
        List<Input> declarations = parseDeclarations(rows(blueprint));
        if (declarations.isEmpty()) return declarations;
        if (!blueprint.has("blocks") || !blueprint.get("blocks").isJsonArray()) throw bad("external inputs require blueprint.blocks");
        Map<BlockPos, JsonObject> cells = new LinkedHashMap<>();
        for (JsonElement element : blueprint.getAsJsonArray("blocks")) {
            JsonObject cell = object(element); BlockPos pos = position(cell.get("offset"));
            JsonObject prior = cells.putIfAbsent(pos, cell);
            if (prior != null && (!cell.has("part") || !prior.has("part"))) throw bad("ambiguous external input target");
        }
        for (Input input : declarations) {
            JsonObject cell = cells.get(input.offset);
            if (cell == null || cell.has("part") || !cell.has("block_id")
                    || !input.blockId.equals(cell.get("block_id").getAsString())) throw bad("external input must identify one exact declared full block: " + input.id);
            if (input.medium.equals("kinetic")) {
                JsonObject state = cell.has("properties") && cell.get("properties").isJsonObject() ? cell.getAsJsonObject("properties") : new JsonObject();
                if (!state.has("axis") || !state.get("axis").isJsonPrimitive() || !state.get("axis").getAsString().equals(input.face.getAxis().getName()))
                    throw bad("kinetic input shaft axis must explicitly match its connection face");
            }
            for (var entry : cells.entrySet()) {
                BlockPos delta = entry.getKey().subtract(input.offset);
                int distance = delta.getX() * input.face.getStepX() + delta.getY() * input.face.getStepY() + delta.getZ() * input.face.getStepZ();
                if (distance > 0 && entry.getKey().equals(input.offset.relative(input.face, distance)) && !air(entry.getValue()))
                    throw bad("external input face must have a free or reserved path to the exterior: " + input.id);
            }
        }
        return declarations;
    }

    /** Stored requirements remain historical declarations; this does not verify a world or blueprint. */
    public static List<Input> parseDeclarations(JsonArray declarations) {
        return parseDeclarations(declarations, false);
    }

    /** Persistence is bounded by supported world coordinates rather than today's mutable planning radius. */
    public static List<Input> parseStoredDeclarations(JsonArray declarations) {
        return parseDeclarations(declarations, true);
    }

    private static List<Input> parseDeclarations(JsonArray declarations, boolean stored) {
        if (declarations == null || declarations.size() > 8) throw bad("external_inputs must contain at most eight inputs");
        List<Input> result = new ArrayList<>(); Set<BlockPos> positions = new HashSet<>();
        for (JsonElement element : declarations) {
            JsonObject row = object(element); keys(row, Set.of("offset", "block_id"));
            int bound = MachinePlanningBudget.current().maxRadius();
            BlockPos at = stored ? position(row.get("offset"), 30_000_000, 2048) : position(row.get("offset"), bound, bound);
            Input input = fields(row, at, identifier(row, "block_id"), true);
            if (!positions.add(input.offset)) throw bad("external inputs must have distinct physical blocks");
            if (!passiveConnector(input.medium, input.blockId)) throw bad("unsupported passive external input: " + input.blockId + " via " + input.medium);
            result.add(input);
        }
        validateCounts(result); return List.copyOf(result);
    }

    public static String connector(String medium) {
        return switch (medium) {
            case "kinetic" -> "create:shaft";
            case "energy" -> "mekanism:basic_universal_cable";
            case "fluids" -> "mekanism:basic_mechanical_pipe";
            case "chemicals" -> "mekanism:basic_pressurized_tube";
            case "items" -> "minecraft:barrel";
            default -> throw bad("unsupported external input medium: " + medium);
        };
    }

    private static boolean passiveConnector(String medium, String id) {
        if (id.equals(connector(medium))) return true;
        return switch (medium) {
            case "kinetic" -> false;
            case "energy" -> id.matches("mekanism:(basic|advanced|elite|ultimate)_universal_cable");
            case "fluids" -> id.equals("create:fluid_tank") || id.matches("mekanism:(basic|advanced|elite|ultimate)_(mechanical_pipe|fluid_tank)");
            case "chemicals" -> id.matches("mekanism:(basic|advanced|elite|ultimate)_(pressurized_tube|chemical_tank)");
            case "items" -> id.equals("minecraft:chest") || id.matches("mekanism:(basic|advanced|elite|ultimate)_logistical_transporter");
            default -> false;
        };
    }

    private static Input fields(JsonObject row, BlockPos at, String block, boolean concrete) {
        String id = text(row, "id", 64), medium = text(row, "medium", 32);
        if (!id.matches("[a-zA-Z0-9_.-]+")) throw bad("external input id must use letters, digits, underscore, dot or dash");
        if (!MEDIA.contains(medium)) throw bad("unsupported external input medium: " + medium);
        Direction face = row.has("face") ? Direction.byName(text(row, "face", 8)) : concrete ? null : medium.equals("kinetic") ? Direction.UP : Direction.WEST;
        if (face == null) throw bad("external input face must name a block side");
        Integer rpm = row.has("minimum_rpm") ? (int) number(row.get("minimum_rpm"), 1, 1_000_000, "minimum_rpm") : null;
        if (rpm != null && !medium.equals("kinetic")) throw bad("minimum_rpm only applies to kinetic inputs");
        String resource = row.has("resource") ? identifier(row, "resource") : null;
        if (resource != null && !Set.of("items", "fluids", "chemicals").contains(medium)) throw bad("resource only applies to item, fluid or chemical inputs");
        String reason = row.has("reason") ? text(row, "reason", 512) : null;
        return new Input(id, medium, at, face, block, rpm, resource, reason);
    }

    private static void validateCounts(List<Input> inputs) {
        Set<String> ids = new HashSet<>(); Map<String, Integer> counts = new HashMap<>();
        for (Input input : inputs) {
            if (!ids.add(input.id)) throw bad("duplicate external input id: " + input.id);
            if (counts.merge(input.medium, 1, Integer::sum) > 3) throw bad("at most three external inputs per medium are allowed");
        }
        for (Input input : inputs) if (counts.get(input.medium) > 1 && input.reason == null)
            throw bad("prefer one external input per medium; separate networks require a reason on each input");
    }

    private static JsonArray rows(JsonObject document) {
        if (!document.has("external_inputs")) return new JsonArray();
        JsonElement raw = document.get("external_inputs");
        if (!raw.isJsonArray() || raw.getAsJsonArray().size() > 8) throw bad("external_inputs must be an array of at most eight inputs");
        return raw.getAsJsonArray();
    }
    private static JsonObject requirements(String id, String medium, Direction face, Integer rpm, String resource, String reason) {
        JsonObject row = new JsonObject(); row.addProperty("id", id); row.addProperty("medium", medium); row.addProperty("face", face.getName());
        if (rpm != null) row.addProperty("minimum_rpm", rpm);
        if (resource != null) row.addProperty("resource", resource);
        if (reason != null) row.addProperty("reason", reason); return row;
    }
    private static BlockPos position(JsonElement value) {
        int bound = MachinePlanningBudget.current().maxRadius(); return position(value, bound, bound);
    }
    private static BlockPos position(JsonElement value, int horizontalBound, int verticalBound) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) throw bad("external input offset must be [x,y,z]");
        JsonArray p = value.getAsJsonArray();
        return new BlockPos((int) number(p.get(0), -horizontalBound, horizontalBound, "offset"),
                (int) number(p.get(1), -verticalBound, verticalBound, "offset"), (int) number(p.get(2), -horizontalBound, horizontalBound, "offset"));
    }
    private static long number(JsonElement value, long minimum, long maximum, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " must be an integer");
        try { long n = value.getAsBigDecimal().longValueExact(); if (n >= minimum && n <= maximum) return n; }
        catch (ArithmeticException | NumberFormatException invalid) { /* bounded diagnostic below */ }
        throw bad(field + " outside " + minimum + ".." + maximum);
    }
    private static String text(JsonObject object, String key, int max) { return text(object.get(key), key, max); }
    private static String text(JsonElement value, String key, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(key + " must be a string");
        String s = value.getAsString();
        if (s.isBlank() || s.length() > max || !s.equals(s.strip()) || s.codePoints().anyMatch(Character::isISOControl)) throw bad("invalid " + key);
        return s;
    }
    private static String identifier(JsonObject object, String key) {
        String id = text(object, key, 256); if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw bad(key + " requires a registry identifier"); return id;
    }
    private static JsonObject object(JsonElement value) { if (!value.isJsonObject()) throw bad("external input must be an object"); return value.getAsJsonObject(); }
    private static void keys(JsonObject object, Set<String> additional) {
        for (String key : object.keySet()) if (!REQUIREMENTS.contains(key) && !additional.contains(key)) throw bad("unsupported external input field: " + key);
    }
    private static boolean air(JsonObject cell) { return cell.has("block_id") && Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").contains(cell.get("block_id").getAsString()); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}

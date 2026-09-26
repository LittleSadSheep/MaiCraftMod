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
import org.maiwithu.maicraft.core.integration.machine.MachineAssemblyPorts;

/** 声明被动公用设施的边界，绝不代表发电机或已连通证明。 */
public final class MachineUtilityInputs {
    public static final Set<String> MEDIA = Set.of("kinetic", "energy", "fluids", "chemicals", "items");
    public static final int MAX_INPUTS = 64;
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
            return at(position, connector(medium));
        }
        /** 物品 IN 绑定工艺已有的接收端，不为供料需求额外生成箱体或指定物流模组。 */
        public Input at(BlockPos position, String receiver) {
            return new Input(id, medium, position, face, receiver, minimumRpm, resource, reason);
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
            Input common = fields(row, BlockPos.ZERO, "", false);
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
        validateCounts(result.stream().map(d -> d.at(BlockPos.ZERO, "")).toList());
        return List.copyOf(result);
    }

    /** 对照准确声明的方块和畅通的外部射线，验证具体设施声明。 */
    public static List<Input> parse(JsonObject blueprint) {
        supplyPreference(blueprint);
        JsonArray inputRows = rows(blueprint);
        // 未声明外部资源时不要求实体蓝图，只有实际接入请求才解析安装后的方块与轴面。
        if (inputRows.isEmpty()) return List.of();
        if (!blueprint.has("blocks") || !blueprint.get("blocks").isJsonArray()) throw bad("external inputs require blueprint.blocks");
        List<Input> declarations = parseDeclarations(MachineAssemblyPorts.bindInputs(blueprint, inputRows));
        Map<BlockPos, JsonObject> cells = new LinkedHashMap<>();
        for (JsonElement element : blueprint.getAsJsonArray("blocks")) {
            JsonObject cell = object(element); BlockPos pos = position(cell.get("offset"));
            JsonObject prior = cells.putIfAbsent(pos, cell);
            if (prior != null && (!cell.has("part") || !prior.has("part"))) throw bad("ambiguous external input target");
        }
        cells.putAll(MachineAssemblyPorts.finalBlocks(blueprint));
        for (Input input : declarations) {
            JsonObject cell = cells.get(input.offset);
            if (cell == null || cell.has("part") || !cell.has("block_id")
                    || !input.blockId.equals(cell.get("block_id").getAsString())) throw bad("external input must identify one exact declared full block: " + input.id);
            if (input.medium.equals("kinetic")) {
                if (input.blockId.equals("create:belt")) {
                    // 中间带轮允许从侧面接入弯折动力路线；实际相邻端口及网络状态由原生动力规划器复核。
                    if (!MachineAssemblyPorts.hasBeltPort(blueprint, input.offset, input.face)) throw bad("native_belt_power_port_unavailable: " + input.id);
                    continue;
                }
                JsonObject state = cell.has("properties") && cell.get("properties").isJsonObject() ? cell.getAsJsonObject("properties") : new JsonObject();
                if (!state.has("axis") || !state.get("axis").isJsonPrimitive() || !state.get("axis").getAsString().equals(input.face.getAxis().getName()))
                    throw bad("kinetic input shaft axis must explicitly match its connection face");
            }
            // 物品可人工递交或从局部运输器进入；只检查紧邻接口净空，不能要求机械手穿过下方工件的整条射线。
            if (input.medium.equals("items")) {
                JsonObject adjacent = cells.get(input.offset.relative(input.face));
                if (adjacent != null && !air(adjacent)) throw bad("item input face needs a free handoff cell: " + input.id);
                continue;
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

    /** 已存需求仅作为历史声明保留；此方法不会核验世界或蓝图。 */
    public static List<Input> parseDeclarations(JsonArray declarations) {
        return parseDeclarations(declarations, false);
    }

    /** 持久化范围由受支持的世界坐标决定，而非使用当前可变的规划半径。 */
    public static List<Input> parseStoredDeclarations(JsonArray declarations) {
        return parseDeclarations(declarations, true);
    }

    private static List<Input> parseDeclarations(JsonArray declarations, boolean stored) {
        if (declarations == null || declarations.size() > MAX_INPUTS) throw bad("external_inputs must contain at most " + MAX_INPUTS + " inputs");
        List<Input> result = new ArrayList<>(); Set<String> endpoints = new HashSet<>();
        for (JsonElement element : declarations) {
            JsonObject row = object(element); keys(row, Set.of("offset", "block_id"));
            int bound = MachinePlanningBudget.current().maxRadius();
            BlockPos at = stored ? position(row.get("offset"), 30_000_000, 2048) : position(row.get("offset"), bound, bound);
            Input input = fields(row, at, identifier(row, "block_id"), true);
            // 一个料盆可接收多种原料；同一介质、面和资源的重复声明才是重复端点。
            String endpoint = input.offset + ":" + input.face + ":" + input.medium + ":" + input.resource;
            if (!endpoints.add(endpoint)) throw bad("external inputs must have distinct receiver/face/medium/resource bindings");
            if (!supportedReceiver(input.medium, input.blockId)) throw bad("unsupported external receiver: " + input.blockId + " via " + input.medium);
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
            case "items" -> throw bad("item inputs bind an existing consumer; choose any storage or transport explicitly");
            default -> throw bad("unsupported external input medium: " + medium);
        };
    }

    private static boolean supportedReceiver(String medium, String id) {
        if (!medium.equals("items") && id.equals(connector(medium))) return true;
        return switch (medium) {
            case "kinetic" -> id.equals("create:belt");
            case "energy" -> id.matches("mekanism:(basic|advanced|elite|ultimate)_universal_cable");
            case "fluids" -> id.equals("create:fluid_tank") || id.matches("mekanism:(basic|advanced|elite|ultimate)_(mechanical_pipe|fluid_tank)");
            case "chemicals" -> id.matches("mekanism:(basic|advanced|elite|ultimate)_(pressurized_tube|chemical_tank)");
            // 原生库存、承载面和运输接口只说明可声明接收端；方向、过滤、余量与真正转移仍须现场验证。
            case "items" -> Set.of("minecraft:chest", "minecraft:barrel", "minecraft:hopper", "create:deployer", "create:depot",
                    "create:basin", "create:belt", "create:item_vault", "create:chute", "create:smart_chute", "create:millstone",
                    "mekanism:enrichment_chamber", "mekanism:crusher", "mekanism:energized_smelter", "mekanism:metallurgic_infuser", "ae2:interface").contains(id)
                    || id.matches("mekanism:(basic|advanced|elite|ultimate)_logistical_transporter");
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
        if (inputs.size() > MAX_INPUTS) throw bad("external_inputs must contain at most " + MAX_INPUTS + " inputs");
        Set<String> ids = new HashSet<>(); Map<String, Integer> counts = new HashMap<>();
        for (Input input : inputs) {
            if (!ids.add(input.id)) throw bad("duplicate external input id: " + input.id);
            if (counts.merge(input.medium, 1, Integer::sum) > 3 && !input.medium.equals("items")) throw bad("at most three external inputs per non-item medium are allowed");
        }
        // 配方原料和重复加工端可独立供料，不为了合并 IN 强加中心库存；其余公用网络仍要求拆分理由。
        for (Input input : inputs) if (counts.get(input.medium) > 1 && input.reason == null && !(input.medium.equals("items") && input.resource != null))
            throw bad("separate inputs require a reason; item inputs may instead identify the supplied resource");
    }

    private static JsonArray rows(JsonObject document) {
        if (!document.has("external_inputs")) return new JsonArray();
        JsonElement raw = document.get("external_inputs");
        if (!raw.isJsonArray() || raw.getAsJsonArray().size() > MAX_INPUTS) throw bad("external_inputs must be an array of at most " + MAX_INPUTS + " inputs");
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

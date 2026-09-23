// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.CreateBeltGeometry;
import org.maiwithu.maicraft.core.integration.create.CreateBeltAccess;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/** 显式蓝图的组件关系与原生安装声明；所有坐标、承载面和运输技术都由作者选择。 */
public final class MachineAssemblyDocument {
    private MachineAssemblyDocument() {}

    public static JsonObject normalize(JsonObject blueprint, int radius) {
        JsonObject result = new JsonObject();
        JsonObject source = blueprint.has("assembly") ? object(blueprint.get("assembly"), "assembly") : new JsonObject();
        keys(source, Set.of("installations", "processing"));
        JsonArray installations = array(source, "installations"), processing = array(source, "processing");
        // 实装版本负责给出带长上限；离线格式检查只用规划预算，不能借默认长度假称原生安装可用。
        int length = CreateBeltAccess.available() ? CreateBeltAccess.maximumLength() : Math.min(2 * radius + 1, MachinePlanningBudget.current().maxTargets());
        var expanded = MachineBeltRoutes.expandWithSources(installations, radius, length);
        installations = expanded.installations();
        // 作者只给带段时先推导端轴，再让原有占格、加工与禁用模组检查审查完整展开结果。
        MachineBeltAssembly.prepareShafts(blueprint, installations, radius, expanded.sourcePaths());
        JsonArray installs = new JsonArray(), relations = new JsonArray();
        Map<BlockPos, JsonObject> blocks = blocks(blueprint); Set<BlockPos> occupied = new LinkedHashSet<>(), parts = new LinkedHashSet<>();
        for (var raw : blueprint.getAsJsonArray("blocks")) if (raw.getAsJsonObject().has("part")) parts.add(position(raw.getAsJsonObject().get("offset")));
        for (var raw : installations) {
            JsonObject input = object(raw, "installation"); keys(input, Set.of("type", "first", "second", "pulleys", "flow"));
            if (!input.has("type") || !input.get("type").isJsonPrimitive() || !input.getAsJsonPrimitive("type").isString()
                    || !input.get("type").getAsString().equals("create:belt"))
                throw bad("unsupported_native_installation; read the machine assembly capability contract");
            BlockPos first = position(input.get("first"), radius), second = position(input.get("second"), radius);
            Direction.Axis axis = shaft(blocks.get(first));
            if (axis != shaft(blocks.get(second))) throw bad("belt_endpoint_axes_mismatch");
            var span = CreateBeltGeometry.between(first, second, axis, Math.min(2 * radius + 1, MachinePlanningBudget.current().maxTargets()));
            for (BlockPos at : span.cells()) {
                if (!occupied.add(at) || parts.contains(at)) throw bad("native_installation_overlap");
                var block = blocks.get(at);
                if (block != null && !block.get("block_id").getAsString().equals("minecraft:air") && shaft(block) != axis)
                    throw bad("belt_path_requires_declared_matching_shafts_or_clearance");
            }
            JsonObject row = new JsonObject(); row.addProperty("type", "create:belt");
            row.add("first", json(first)); row.add("second", json(second));
            if (input.has("pulleys")) row.add("pulleys", input.get("pulleys").deepCopy());
            if (input.has("flow")) {
                // first/second 是连接器动作顺序，期望物流另外声明，不能把几何朝向当成实测转向。
                if (!input.get("flow").isJsonPrimitive() || !input.getAsJsonPrimitive("flow").isString()
                        || !Set.of("first_to_second", "second_to_first").contains(input.get("flow").getAsString())) throw bad("invalid_belt_flow");
                row.add("flow", input.get("flow").deepCopy());
            }
            installs.add(row);
        }
        if (occupied.size() + blocks.keySet().stream().filter(at -> !occupied.contains(at)).count() > MachinePlanningBudget.current().maxTargets())
            throw bad("native_installation_target_budget_exceeded");
        Set<BlockPos> assigned = new LinkedHashSet<>();
        for (var raw : processing) {
            JsonObject input = object(raw, "processing relation"); keys(input, Set.of("processor", "surface"));
            BlockPos processor = position(input.get("processor"), radius), surface = position(input.get("surface"), radius);
            if (!blocks.containsKey(processor) || !assigned.add(processor)) throw bad("processing_requires_one_declared_processor_per_relation");
            if (!blocks.containsKey(surface) && !occupied.contains(surface)) throw bad("processing_surface_not_declared");
            JsonObject row = new JsonObject(); row.add("processor", json(processor)); row.add("surface", json(surface)); relations.add(row);
        }
        result.add("installations", installs); result.add("processing", relations); return result;
    }

    public static void review(JsonObject blueprint, SemanticMachineLayout.Registry registry, Set<String> errors) {
        if (!blueprint.has("assembly")) return;
        Map<BlockPos, JsonObject> blocks = blocks(blueprint);
        Map<BlockPos, JsonObject> finalBlocks = new LinkedHashMap<>(blocks);
        Set<BlockPos> parts = new LinkedHashSet<>();
        for (var raw : blueprint.getAsJsonArray("blocks")) if (raw.getAsJsonObject().has("part")) parts.add(position(raw.getAsJsonObject().get("offset")));
        Set<String> forbidden = MachineDesignConstraints.forbiddenMods(blueprint);
        for (var span : belts(blueprint)) {
            // 连接器和整条带都是计划的一部分，不能只审查用来准备端点的两根轴。
            for (String id : List.of("create:belt", "create:belt_connector"))
                if (!MachineDesignConstraints.allows(forbidden, id)) errors.add("forbidden_mod_dependency: " + id);
            if (!registry.blockExists("create:belt") || !registry.itemExists("create:belt_connector")) errors.add("native_belt_dependency_unavailable");
            for (BlockPos at : span.cells()) {
                boolean pulley = blocks.containsKey(at) && blocks.get(at).get("block_id").getAsString().equals("create:shaft");
                JsonObject block = new JsonObject(); block.addProperty("block_id", "create:belt");
                JsonObject properties = new JsonObject(); span.properties(at, pulley).forEach(properties::addProperty);
                block.add("properties", properties); finalBlocks.put(at, block);
                if (!registry.supportsState("create:belt", properties(block))) errors.add("unsupported_native_belt_state");
            }
        }
        for (var raw : blueprint.getAsJsonObject("assembly").getAsJsonArray("processing")) {
            var relation = raw.getAsJsonObject(); BlockPos at = position(relation.get("processor")), surfaceAt = position(relation.get("surface"));
            var processor = finalBlocks.get(at); var surface = finalBlocks.get(surfaceAt);
            var processorProperties = properties(processor);
            var action = registry.processing(processor.get("block_id").getAsString(), processorProperties);
            var carrier = registry.processing(surface.get("block_id").getAsString(), properties(surface));
            if (!action.processor() || !carrier.surface()) errors.add("native_processing_or_surface_contract_unavailable: " + at + " -> " + surfaceAt);
            if (!at.offset(action.workOffset()).equals(surfaceAt)) errors.add("processing_surface_must_match_native_work_position: " + at);
            if (!action.requiredState().entrySet().stream().allMatch(entry -> entry.getValue().equals(processorProperties.get(entry.getKey()))))
                errors.add("processor_state_incompatible_with_work_surface: " + at);
            // 检查适配器声明的动作净空，不能把所有模组的加工位置都固定成“下方两格”。
            for (BlockPos relative : action.clearance()) {
                BlockPos gapAt = at.offset(relative); JsonObject gap = finalBlocks.get(gapAt);
                if (parts.contains(gapAt)) errors.add("processing_clearance_contains_native_part: " + gapAt);
                if (gap != null && !registry.processingSpaceClear(gap.get("block_id").getAsString(), properties(gap))) errors.add("processing_space_occupied: " + gapAt);
            }
        }
    }

    public static List<CreateBeltGeometry.Span> belts(JsonObject blueprint) {
        if (!blueprint.has("assembly")) return List.of();
        Map<BlockPos, JsonObject> blocks = blocks(blueprint);
        return blueprint.getAsJsonObject("assembly").getAsJsonArray("installations").asList().stream().map(raw -> {
            JsonObject entry = raw.getAsJsonObject(); BlockPos first = position(entry.get("first"));
            return CreateBeltGeometry.between(first, position(entry.get("second")), shaft(blocks.get(first)),
                    Math.min(2 * MachinePlanningBudget.current().maxRadius() + 1, MachinePlanningBudget.current().maxTargets()));
        }).toList();
    }

    public static Map<BlockPos, JsonObject> blocks(JsonObject blueprint) {
        Map<BlockPos, JsonObject> result = new LinkedHashMap<>();
        for (var raw : blueprint.getAsJsonArray("blocks")) {
            JsonObject block = raw.getAsJsonObject();
            if (!block.has("part")) result.put(position(block.get("offset")), block);
        }
        return result;
    }
    public static Map<String, String> properties(JsonObject block) {
        Map<String, String> result = new LinkedHashMap<>();
        if (block != null && block.has("properties")) block.getAsJsonObject("properties").entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
        return result;
    }
    private static Direction.Axis shaft(JsonObject block) {
        if (block == null || !block.get("block_id").getAsString().equals("create:shaft")) throw bad("belt_endpoint_requires_authored_shaft");
        String axis = properties(block).get("axis");
        if (!Set.of("x", "y", "z").contains(axis == null ? "" : axis)) throw bad("belt_shaft_axis_must_be_explicit");
        return Direction.Axis.valueOf(axis.toUpperCase(Locale.ROOT));
    }
    public static BlockPos position(JsonElement value) { return position(value, MachinePlanningBudget.current().maxRadius()); }
    public static BlockPos position(JsonElement value, int radius) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) throw bad("assembly_position_requires_three_integers");
        int[] axes = new int[3];
        for (int i = 0; i < 3; i++) try {
            var axis = value.getAsJsonArray().get(i);
            if (!axis.isJsonPrimitive() || !axis.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            axes[i] = axis.getAsBigDecimal().intValueExact();
            if (axes[i] < -radius || axes[i] > radius) throw new ArithmeticException();
        } catch (ArithmeticException | NumberFormatException invalid) { throw bad("assembly_position_outside_budget"); }
        return new BlockPos(axes[0], axes[1], axes[2]);
    }
    public static JsonArray json(BlockPos position) { JsonArray row = new JsonArray(); row.add(position.getX()); row.add(position.getY()); row.add(position.getZ()); return row; }
    private static JsonObject object(JsonElement value, String name) { if (value == null || !value.isJsonObject()) throw bad(name + " must be an object"); return value.getAsJsonObject(); }
    private static JsonArray array(JsonObject object, String name) {
        if (!object.has(name)) return new JsonArray();
        if (!object.get(name).isJsonArray() || object.getAsJsonArray(name).size() > MachinePlanningBudget.current().maxConnections()) throw bad("invalid assembly " + name);
        return object.getAsJsonArray(name);
    }
    private static void keys(JsonObject object, Set<String> allowed) { for (String key : object.keySet()) if (!allowed.contains(key)) throw bad("unknown assembly field: " + key); }
    private static IllegalArgumentException bad(String detail) { return new IllegalArgumentException(detail); }
}

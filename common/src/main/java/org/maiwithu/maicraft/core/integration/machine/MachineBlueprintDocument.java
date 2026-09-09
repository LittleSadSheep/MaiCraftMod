// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/**
 * 处理当前版本的逐格机器蓝图：普通方块占整格，AE2 部件可以共用宿主的不同安装面。
 * 允许保存观察资料，但资料里的 NBT 和实体不会自动成为可以执行的安装操作。
 */
public final class MachineBlueprintDocument {
    private static final Set<String> FIELDS = Set.of("schema_version", "blocks", "metadata", "evidence", "entities");
    private static final Set<String> BLOCK_FIELDS = Set.of("offset", "block_id", "properties", "nbt");
    private static final Set<String> PART_FIELDS = Set.of("offset", "item_id", "part");
    private static final Set<String> SIDES = Set.of("center", "up", "down", "north", "south", "east", "west");
    private MachineBlueprintDocument() {}

    public static void validateWire(JsonObject document) { normalized(document); }

    /** No layout inference: coordinates and explicit state constraints retain the author's meaning. */
    // 先整理统一格式，再查询注册表能力；把不支持的方块、状态、NBT 和实体安装收集成错误列表，最多展示 32 条。
    public static SemanticMachineLayout.Result compile(JsonObject document, SemanticMachineLayout.Registry registry) {
        JsonObject blueprint = normalized(document);
        Set<String> errors = new LinkedHashSet<>();
        for (JsonElement element : blueprint.getAsJsonArray("blocks")) {
            JsonObject block = element.getAsJsonObject();
            if (block.has("part")) {
                String id = block.get("item_id").getAsString();
                if (!id.startsWith("ae2:") || !registry.itemExists(id)) errors.add("unsupported_native_part: " + id);
                continue;
            }
            String id = block.get("block_id").getAsString();
            Map<String, String> properties = new LinkedHashMap<>();
            block.getAsJsonObject("properties").entrySet().forEach(e -> properties.put(e.getKey(), e.getValue().getAsString()));
            if (!registry.blockExists(id)) errors.add("unknown_block: " + id);
            else if (!registry.supportsState(id, properties)) errors.add("unsupported_block_state: " + id + " " + properties);
            if (block.has("nbt") && !block.getAsJsonObject("nbt").isEmpty())
                errors.add("unsupported_native_nbt_write: " + id + "; configure through an available native operation; observed tutorial NBT belongs in evidence");
        }
        if (blueprint.has("entities") && !blueprint.getAsJsonArray("entities").isEmpty())
            errors.add("unsupported_native_entity_installation: explicit entities require an installation adapter");
        JsonObject report = new JsonObject();
        report.addProperty("compiler", "explicit_machine_blueprint_v1");
        report.addProperty("explicit_blueprint", true);
        report.addProperty("buildable", errors.isEmpty());
        report.addProperty("target_count", blueprint.getAsJsonArray("blocks").size());
        report.addProperty("machine_geometry_verified", false);
        report.addProperty("machine_production_verified", false);
        report.addProperty("verification_contract", "Declared blocks, explicit state properties and native parts only; use operate_machine to configure, start and observe production. Omitted positions are preserved; explicit minecraft:air requests clearance.");
        report.addProperty("tutorial_evidence_is_configuration", false);
        report.addProperty("evidence_attached", document.has("evidence"));
        JsonObject validation = new JsonObject(); validation.addProperty("valid", errors.isEmpty());
        JsonArray errorRows = new JsonArray(); errors.stream().limit(32).forEach(errorRows::add);
        validation.add("errors", errorRows); validation.addProperty("error_count", errors.size());
        validation.addProperty("errors_truncated", errors.size() > 32); report.add("validation", validation);
        // Evidence remains available at its source; do not duplicate large tutorial NBT into every task result.
        // 附带的教程证据用于阅读，不交给后续施工当成配置指令。
        blueprint.remove("evidence");
        return new SemanticMachineLayout.Result(errors.isEmpty(), blueprint, report);
    }

    // 复制并校验字段，补默认版本与空属性表；总目标数和坐标范围使用可配置的规划预算。
    private static JsonObject normalized(JsonObject document) {
        if (document == null) throw bad("blueprint must be an object");
        keys(document, FIELDS, "blueprint");
        if (document.has("schema_version") && integer(document.get("schema_version"), 1, 1, "schema_version") != 1)
            throw bad("unsupported blueprint schema_version");
        for (String field : Set.of("metadata", "evidence"))
            if (document.has(field) && !document.get(field).isJsonObject()) throw bad(field + " must be an object");
        if (!document.has("blocks") || !document.get("blocks").isJsonArray()) throw bad("blueprint.blocks must be an array");
        JsonArray cells = document.getAsJsonArray("blocks");
        int limit = MachinePlanningBudget.current().maxTargets();
        if (cells.isEmpty() || cells.size() > limit) throw bad("blueprint.blocks must contain 1.." + limit + " targets");
        JsonObject result = new JsonObject(); result.addProperty("schema_version", 1);
        JsonArray blocks = new JsonArray(); result.add("blocks", blocks);
        Set<String> slots = new HashSet<>(), ordinary = new HashSet<>(), parts = new HashSet<>();
        for (JsonElement element : cells) {
            if (!element.isJsonObject()) throw bad("blueprint block must be an object");
            JsonObject cell = element.getAsJsonObject(); boolean part = cell.has("part");
            keys(cell, part ? PART_FIELDS : BLOCK_FIELDS, "blueprint target");
            JsonArray offset = offset(cell.get("offset")); String position = offset.toString();
            JsonObject target = new JsonObject(); target.add("offset", offset);
            // 同一格可以放不同面的部件，但同一面不能重复，部件也不能与普通整格方块目标重叠。
            if (part) {
                String side = string(cell.get("part"), 12, "part");
                if (!SIDES.contains(side)) throw bad("invalid part side: " + side);
                if (!slots.add(position + ":" + side) || ordinary.contains(position)) throw bad("overlapping blueprint part: " + position);
                parts.add(position); target.addProperty("part", side);
                target.addProperty("item_id", id(cell.get("item_id"), "item_id"));
            } else {
                if (!ordinary.add(position) || parts.contains(position)) throw bad("overlapping blueprint block: " + position);
                target.addProperty("block_id", id(cell.get("block_id"), "block_id"));
                JsonObject properties = new JsonObject();
                if (cell.has("properties")) {
                    if (!cell.get("properties").isJsonObject()) throw bad("properties must be an object");
                    if (cell.getAsJsonObject("properties").size() > 32) throw bad("too many block state properties");
                    for (var entry : cell.getAsJsonObject("properties").entrySet()) {
                        if (entry.getKey().isBlank() || entry.getKey().length() > 64) throw bad("invalid property name");
                        properties.addProperty(entry.getKey(), string(entry.getValue(), 128, "property value"));
                    }
                }
                target.add("properties", properties);
                if (cell.has("nbt")) {
                    if (!cell.get("nbt").isJsonObject()) throw bad("nbt must be an object");
                    target.add("nbt", cell.get("nbt").deepCopy());
                }
            }
            blocks.add(target);
        }
        // 实体在格式检查时可以作为资料保存并计入数量；编译成实际装配任务时仍会报告缺少实体安装支持。
        if (document.has("entities")) {
            if (!document.get("entities").isJsonArray()) throw bad("entities must be an array");
            if ((long) cells.size() + document.getAsJsonArray("entities").size() > limit) throw bad("blueprint exceeds physical target budget");
            result.add("entities", document.get("entities").deepCopy());
        }
        for (String field : Set.of("metadata", "evidence")) if (document.has(field)) result.add(field, document.get(field).deepCopy());
        return result;
    }

    private static JsonArray offset(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) throw bad("offset must be [dx,dy,dz]");
        int radius = MachinePlanningBudget.current().maxRadius(); JsonArray result = new JsonArray();
        for (JsonElement axis : value.getAsJsonArray()) result.add(integer(axis, -radius, radius, "offset"));
        return result;
    }
    private static int integer(JsonElement value, int min, int max, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " must be an integer");
        try {
            int result = value.getAsBigDecimal().intValueExact();
            if (result < min || result > max) throw bad(field + " outside " + min + ".." + max);
            return result;
        } catch (ArithmeticException | NumberFormatException invalid) { throw bad(field + " must be a bounded integer"); }
    }
    private static String id(JsonElement value, String field) {
        String id = string(value, 256, field);
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw bad(field + " requires a namespaced registry ID");
        return id;
    }
    private static String string(JsonElement value, int max, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(field + " must be a string");
        String result = value.getAsString();
        if (result.isBlank() || result.length() > max) throw bad("invalid " + field);
        return result;
    }
    private static void keys(JsonObject object, Set<String> fields, String context) {
        for (String key : object.keySet()) if (!fields.contains(key)) throw bad("unsupported " + context + " field: " + key);
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

/** 从已取得的机器观察与差异关联部件资料，不再扫描世界，也不根据资料判断机器能否运行。 */
public final class MachineKnowledge {
    private MachineKnowledge() {}

    public static void attach(JsonObject report) { attach(report, new ItemKnowledge()); }

    static void attach(JsonObject report, ItemKnowledge knowledge) {
        JsonObject result = new JsonObject();
        try {
            Map<String, JsonObject> blocks = new LinkedHashMap<>(), items = new LinkedHashMap<>();
            // 只读已有布局、运行表和 diff 的明确身份；不递归猜测任意字符串、库存组件或机器名称的含义。
            for (JsonElement row : array(report, "palette")) block(row, "observed", blocks, items);
            JsonObject layout = object(report.get("as_built_blueprint"));
            for (String field : List.of("blocks", "palette"))
                for (JsonElement row : array(layout, field)) block(row, "observed", blocks, items);
            JsonObject operating = object(report.get("operating_state"));
            for (JsonElement row : array(operating, "other_kinetic_components")) block(row, "observed", blocks, items);
            if (!array(operating, "belts").isEmpty()) {
                JsonObject belt = new JsonObject(); belt.addProperty("block_id", "create:belt"); block(belt, "observed", blocks, items);
            }
            JsonObject diff = report.has("blueprint_diff") ? object(report.get("blueprint_diff")) : report;
            for (JsonElement row : array(diff, "differences")) for (String side : List.of("expected", "actual")) {
                JsonObject state = object(object(row).get(side)); block(state, side, blocks, items);
                if (state.has("part_item_id")) item(state.get("part_item_id").getAsString(), side, items);
            }
            if (blocks.isEmpty() && items.isEmpty()) return;
            JsonArray resources = new JsonArray(), components = new JsonArray();
            items.values().forEach(resources::add); blocks.values().forEach(components::add);
            result.add("block_resources", components); result.add("related_resources", resources);
            knowledge.attach(result, resources, "Block and part identities in this captured machine report; expected targets and observed/actual components remain separate roles.");
            result.addProperty("status", "available");
        } catch (RuntimeException | LinkageError unavailable) {
            // 资料读取异常只影响知识状态，已观察到的转速、持物、结构差异与动作成败全部保留。
            result.addProperty("status", "lookup_unavailable"); result.addProperty("reason", unavailable.getClass().getSimpleName());
        }
        report.add("component_knowledge", result);
    }

    private static void block(JsonElement value, String role, Map<String, JsonObject> blocks, Map<String, JsonObject> items) {
        JsonObject state = object(value); if (!state.has("block_id")) return;
        ResourceLocation id = ResourceLocation.tryParse(state.get("block_id").getAsString());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return;
        var block = BuiltInRegistries.BLOCK.get(id); if (block.defaultBlockState().isAir()) return;
        JsonObject reference = blocks.computeIfAbsent(id.toString(), key -> {
            JsonObject entry = KnowledgeReferences.resource(MinecraftKnowledgeSource.BLOCK + id.getNamespace() + "/" + id.getPath(),
                    "这次观察或差异中涉及的方块状态与原生用法");
            entry.addProperty("block_id", key); entry.add("roles", new JsonArray()); return entry;
        });
        role(reference, role);
        if (block.asItem() != Items.AIR) item(BuiltInRegistries.ITEM.getKey(block.asItem()).toString(), role, items);
    }

    private static void item(String id, String role, Map<String, JsonObject> items) {
        ResourceLocation item = ResourceLocation.tryParse(id);
        if (item == null || !BuiltInRegistries.ITEM.containsKey(item) || BuiltInRegistries.ITEM.get(item) == Items.AIR) return;
        JsonObject reference = items.computeIfAbsent(id, key -> {
            JsonObject entry = new JsonObject(); entry.addProperty("item_id", key); entry.add("roles", new JsonArray()); return entry;
        });
        role(reference, role);
    }

    private static void role(JsonObject entry, String role) {
        if (!entry.getAsJsonArray("roles").asList().contains(new JsonPrimitive(role))) entry.getAsJsonArray("roles").add(role);
    }
    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject(); }
    private static JsonArray array(JsonObject owner, String field) {
        return owner.has(field) && owner.get(field).isJsonArray() ? owner.getAsJsonArray(field) : new JsonArray();
    }
}

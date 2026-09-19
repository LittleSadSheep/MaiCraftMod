// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** 将原生世界加工日志与本批实体、完整原料和机器位置对账；只返回已验证生成的成品快照，不代替后续实体与拾取核验。 */
public final class WorldProcessEventEvidence {
    public record Output(UUID uuid, ItemStack stack) {
        public Output { java.util.Objects.requireNonNull(uuid); stack = stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    private WorldProcessEventEvidence() {}

    public static Output confirm(LocalPlayer player, JsonObject event, WorldProcessRecipe recipe,
                                 Map<UUID, Integer> expectedInputs, List<ItemStack> actualInputUnits, Set<BlockPos> producers) {
        if (expectedInputs.isEmpty() || expectedInputs.values().stream().anyMatch(count -> count == null || count <= 0))
            throw invalid("invalid_expected_input_entities");
        // 先只找本批 UUID 交集；上一批或其他机器的日志即使内容不同，也不能打断当前仍在等待的批次。
        if (!intersects(event, expectedInputs.keySet())) return null;
        if (!"recipe_output".equals(text(event, "kind")) || !"native_recipe_output".equals(text(event, "provenance"))
                || !event.has("completed") || !event.get("completed").isJsonPrimitive()
                || !event.getAsJsonPrimitive("completed").isBoolean() || !event.get("completed").getAsBoolean()
                || !"ae2.transform.tryTransform".equals(text(event, "native_call"))) throw invalid("native_spawn_provenance_mismatch");
        // 这个原生调用点仅在 addFreshEntity 已接受且实体确实入世时发出事件；普通生产日志没有这项生成证明。
        UUID outputId = uuid(event, "output_entity_uuid", "output_entity_missing");
        if (!recipe.id().toString().equals(text(event, "recipe_id"))) throw invalid("recipe_mismatch");
        if (number(event, "operations") != 1) throw invalid("operation_count_mismatch");
        JsonObject position = object(event, "position");
        BlockPos at = new BlockPos(integer(position, "x"), integer(position, "y"), integer(position, "z"));
        if (!producers.contains(at) || event.has("producer") && !(at.getX() + "," + at.getY() + "," + at.getZ()).equals(text(event, "producer")))
            throw invalid("producer_mismatch");

        var consumed = new LinkedHashMap<UUID, Integer>();
        for (JsonElement raw : array(event, "consumed_entities")) {
            if (!raw.isJsonObject()) throw invalid("input_entity_invalid");
            JsonObject row = raw.getAsJsonObject(); UUID id = uuid(row, "entity_uuid", "input_entity_invalid");
            int amount = integer(row, "amount");
            if (amount <= 0 || consumed.putIfAbsent(id, amount) != null) throw invalid("input_entity_invalid");
        }
        if (!consumed.equals(expectedInputs)) throw invalid("input_entities_mismatch");
        var expectedResources = new LinkedHashMap<String, Long>(); long inputCount = 0;
        for (ItemStack stack : actualInputUnits) {
            if (stack.isEmpty()) throw invalid("input_stack_empty");
            String key = ResourceIdentity.key(ResourceIdentity.item(stack, player.registryAccess()));
            expectedResources.merge(key, (long) stack.getCount(), Math::addExact); inputCount = Math.addExact(inputCount, stack.getCount());
        }
        if (inputCount != consumed.values().stream().mapToLong(Integer::longValue).sum()) throw invalid("input_quantity_mismatch");
        if (!resourceAmounts(array(event, "inputs")).equals(expectedResources)) throw invalid("input_resources_mismatch");

        JsonArray outputs = array(event, "outputs");
        if (outputs.size() != 1 || !outputs.get(0).isJsonObject()) throw invalid("output_shape_mismatch");
        JsonObject row = outputs.get(0).getAsJsonObject(); validateResource(row);
        JsonObject identity = object(row, "identity"); int amount = integer(row, "amount");
        ItemStack template = recipe.result();
        if (template.isEmpty() || amount != template.getCount()
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(template.getItem()).toString().equals(text(identity, "id")))
            throw invalid("output_item_or_quantity_mismatch");
        JsonObject encoded = new JsonObject(); encoded.add("id", identity.get("id").deepCopy()); encoded.addProperty("count", amount);
        encoded.add("components", identity.get("components").deepCopy());
        try {
            // assemble 可以赋予频率等模板未列出的真实组件；同物品同数量即可，结果身份始终来自已验证的原生输出。
            ItemStack actual = ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), encoded).getOrThrow();
            if (!ResourceIdentity.item(actual, player.registryAccess()).equals(identity)) throw invalid("output_identity_mismatch");
            return new Output(outputId, actual);
        } catch (RuntimeException malformed) { throw invalid("output_identity_invalid"); }
    }

    private static boolean intersects(JsonObject event, Set<UUID> expected) {
        if (event == null || !event.has("consumed_entities") || !event.get("consumed_entities").isJsonArray()) return false;
        for (JsonElement raw : event.getAsJsonArray("consumed_entities")) {
            if (!raw.isJsonObject()) continue;
            try { if (expected.contains(uuid(raw.getAsJsonObject(), "entity_uuid", "input_entity_invalid"))) return true; }
            catch (IllegalArgumentException unrelated) { /* 无法关联的旧日志先跳过；找到交集后完整解析会拒绝任何坏条目。 */ }
        }
        return false;
    }

    private static Map<String, Long> resourceAmounts(JsonArray rows) {
        var counts = new LinkedHashMap<String, Long>();
        for (JsonElement raw : rows) {
            if (!raw.isJsonObject()) throw invalid("resource_invalid");
            JsonObject row = raw.getAsJsonObject(); validateResource(row);
            counts.merge(text(row, "resource_id"), number(row, "amount"), Math::addExact);
        }
        return counts;
    }

    private static void validateResource(JsonObject row) {
        JsonObject identity = object(row, "identity");
        text(identity, "id");
        if (!"items".equals(text(identity, "kind")) || !identity.has("components") || !identity.get("components").isJsonObject()
                || number(row, "amount") <= 0 || !ResourceIdentity.key(identity).equals(text(row, "resource_id"))) throw invalid("resource_identity_invalid");
    }
    private static JsonArray array(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonArray() || parent.getAsJsonArray(key).size() > 128) throw invalid(key + "_invalid");
        return parent.getAsJsonArray(key);
    }
    private static JsonObject object(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) throw invalid(key + "_invalid");
        return parent.getAsJsonObject(key);
    }
    private static String text(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonPrimitive() || !parent.getAsJsonPrimitive(key).isString()) throw invalid(key + "_invalid");
        return parent.get(key).getAsString();
    }
    private static long number(JsonObject parent, String key) {
        try {
            if (!parent.has(key) || !parent.get(key).isJsonPrimitive() || !parent.getAsJsonPrimitive(key).isNumber()) throw invalid(key + "_invalid");
            return parent.get(key).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException invalid) { throw invalid(key + "_invalid"); }
    }
    private static int integer(JsonObject parent, String key) {
        try { return Math.toIntExact(number(parent, key)); }
        catch (ArithmeticException invalid) { throw invalid(key + "_invalid"); }
    }
    private static UUID uuid(JsonObject parent, String key, String reason) {
        try { return UUID.fromString(text(parent, key)); }
        catch (IllegalArgumentException invalid) { throw invalid(reason); }
    }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("world_process_" + reason); }
}

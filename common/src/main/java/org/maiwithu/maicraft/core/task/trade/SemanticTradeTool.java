// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.trade;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;

/** Internal merchant executor. Public callers express only the semantic inventory outcome. */
public final class SemanticTradeTool implements MaiCraftTool {
    @Override public String name() { return SemanticTradeTaskRecord.TOOL_NAME; }

    @Override
    public String description() {
        return "Make a final main-inventory item count true through an ordinary loaded villager "
                + "or wandering-trader offer. Declare only output, count, merchant/payment policy "
                + "and protected labels. MaiCraft selects the concrete loaded merchant and offer, "
                + "approaches in first person, uses synchronized menu receipts, and verifies the "
                + "real inventory after every trade.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", property("string", "Requested namespaced trade output."));
        properties.put("count", boundedInteger(
                "Required final main-inventory count.", 1,
                SemanticTradeTaskRecord.MAX_FINAL_COUNT));
        Map<String, Object> kind = property("string", "Allowed merchant family.");
        kind.put("enum", List.of("auto", "villager", "wandering_trader"));
        properties.put("merchant_kind", kind);
        properties.put("allowed_payment_items", arrayProperty(
                "Optional namespaced payment-item policy; omit to use any affordable offer."));
        properties.put("protected_labels", arrayProperty(
                "Remembered places whose merchants must not be selected."));
        properties.put("radius", boundedInteger(
                "Loaded-entity search radius.", 1, SemanticTradeTaskRecord.MAX_RADIUS));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("item_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        ResourceLocation itemId = resource(args.get("item_id"), "item_id");
        int count = integer(args, "count", 1, 1, SemanticTradeTaskRecord.MAX_FINAL_COUNT);
        List<ResourceLocation> payments = new ArrayList<>();
        for (String value : strings(args.get("allowed_payment_items"),
                "allowed_payment_items")) {
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) throw new IllegalArgumentException(
                    "allowed_payment_items contains an invalid resource id: " + value);
            payments.add(id);
        }
        List<String> labels = strings(args.get("protected_labels"), "protected_labels");
        int radius = integer(args, "radius", SemanticTradeTaskRecord.DEFAULT_RADIUS,
                1, SemanticTradeTaskRecord.MAX_RADIUS);
        long ticks = Math.clamp(4L * 60L * 20L + (long) count * 120L,
                4L * 60L * 20L, 30L * 60L * 20L);
        var context = ctx(toolCallId, player);
        var record = new SemanticTradeTaskRecord(
                context.toolCallId(), context.deadline(ticks), itemId, count,
                SemanticTradeTaskRecord.MerchantKind.parse(text(args, "merchant_kind")),
                payments, labels, radius);
        setTask(player, record, args, reply);
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("description", description);
        return result;
    }

    private static Map<String, Object> boundedInteger(
            String description, int minimum, int maximum) {
        Map<String, Object> result = property("integer", description);
        result.put("minimum", minimum);
        result.put("maximum", maximum);
        return result;
    }

    private static Map<String, Object> arrayProperty(String description) {
        Map<String, Object> items = property("string", "");
        items.remove("description");
        Map<String, Object> result = property("array", description);
        result.put("items", items);
        return result;
    }

    private static ResourceLocation resource(JsonElement value, String key) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " must be a namespaced item id");
        }
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        if (id == null) throw new IllegalArgumentException(
                key + " must be a namespaced item id");
        return id;
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : null;
    }

    private static int integer(
            JsonObject object, String key, int fallback, int minimum, int maximum) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return Math.clamp(object.get(key).getAsInt(), minimum, maximum);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static List<String> strings(JsonElement value, String key) {
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || element.getAsString().isBlank()) {
                throw new IllegalArgumentException(
                        key + " must contain only non-blank strings");
            }
            result.add(element.getAsString().trim());
        }
        return List.copyOf(result);
    }
}

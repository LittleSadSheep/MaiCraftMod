// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

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
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.cook.SemanticCookTaskRecord;

/** Internal furnace-family capability. Public MCP callers use maicraft:cook. */
public final class SemanticCookTool implements MaiCraftTool {
    @Override public String name() { return SemanticCookTaskRecord.TOOL_NAME; }

    @Override
    public String description() {
        return "Make one cooked output inventory fact true. Declare only output item/count, "
                + "recipe preference, allowed fuels and semantic acquisition sources. The Mod "
                + "chooses recipes, inputs, fuel quantities, workstations, paths and synchronized "
                + "menu transactions. Furnace, blast-furnace and smoker recipes are supported.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", property("string", "Requested namespaced cooked output item."));
        Map<String, Object> count = property("integer", "Required final main-inventory count.");
        count.put("minimum", 1); count.put("maximum", SemanticCookTaskRecord.MAX_FINAL_COUNT);
        properties.put("count", count);
        Map<String, Object> preference = property("string", "Semantic recipe/device preference.");
        preference.put("enum", List.of(
                "auto", "fastest", "preserve_rare", "smelting", "blasting", "smoking", "campfire"));
        properties.put("recipe_preference", preference);
        properties.put("allowed_fuels", arrayProperty(
                "Namespaced fuel items the Mod may consume; omit for safe ordinary fuels.", null));
        properties.put("allowed_sources", arrayProperty(
                "Source families allowed for inputs, fuel and a required workstation.",
                List.of("inventory", "nearby", "storage", "craft", "mine", "trade", "hunt")));
        properties.put("allow_harm", property("boolean",
                "Whether recursively acquiring cooking inputs may harm living entities; default false."));
        properties.put("protected_labels", arrayProperty(
                "Remembered places or possessions recursive acquisition must not touch.", null));
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
        int count = integer(args, "count", 1, 1, SemanticCookTaskRecord.MAX_FINAL_COUNT);
        String rawPreference = text(args, "recipe_preference");
        List<ResourceLocation> fuels = new ArrayList<>();
        for (String value : strings(args.get("allowed_fuels"), "allowed_fuels")) {
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) throw new IllegalArgumentException(
                    "allowed_fuels contains an invalid resource id: " + value);
            fuels.add(id);
        }
        List<SemanticAcquireTaskRecord.Source> sources = new ArrayList<>();
        for (String value : strings(args.get("allowed_sources"), "allowed_sources")) {
            sources.add(SemanticAcquireTaskRecord.Source.parse(value));
        }
        long ticks = Math.clamp(5L * 60L * 20L + (long) count * 260L,
                5L * 60L * 20L, 45L * 60L * 20L);
        var context = ctx(toolCallId, player);
        var record = new SemanticCookTaskRecord(
                context.toolCallId(),
                context.deadline(ticks),
                itemId,
                count,
                SemanticCookTaskRecord.Preference.parse(rawPreference),
                fuels,
                sources,
                bool(args, "allow_harm", false),
                strings(args.get("protected_labels"), "protected_labels"));
        setTask(player, record, args, reply);
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type); result.put("description", description); return result;
    }

    private static Map<String, Object> arrayProperty(String description, List<String> values) {
        Map<String, Object> items = property("string", "");
        items.remove("description");
        if (values != null) items.put("enum", values);
        Map<String, Object> result = property("array", description);
        result.put("items", items); return result;
    }

    private static ResourceLocation resource(JsonElement value, String key) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " must be a namespaced item id");
        }
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        if (id == null) throw new IllegalArgumentException(key + " must be a namespaced item id");
        return id;
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        try { return Math.clamp(object.get(key).getAsInt(), min, max); }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        if (!object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        try { return object.get(key).getAsBoolean(); }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
    }

    private static List<String> strings(JsonElement value, String key) {
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || element.getAsString().isBlank()) {
                throw new IllegalArgumentException(key + " must contain only non-blank strings");
            }
            result.add(element.getAsString().trim());
        }
        return List.copyOf(result);
    }
}

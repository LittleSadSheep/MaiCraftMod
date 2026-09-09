// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;

/**
 * 内部语义容器入口，接受“存什么、取什么、要多少和选哪个区域的容器”，不要求调用方提供槽号。
 * 参数转成任务单后，后续每刻执行和结果确认由容器任务负责。
 */
public final class SemanticContainerTool implements MaiCraftTool {
    @Override public String name() { return SemanticContainerTaskRecord.TOOL_NAME; }

    @Override
    public String description() {
        return "Deposit, withdraw or balance semantic item groups against one loaded block "
                + "container. MaiCraft selects the real container, approaches and opens it in "
                + "first person, derives safe menu sides, performs receipt-confirmed transfers, "
                + "and verifies both inventory deltas. Never provide coordinates, slots, clicks or moves.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> operation = property("string", "Semantic transfer direction.");
        operation.put("enum", List.of("deposit", "withdraw", "balance"));
        properties.put("operation", operation);
        properties.put("item_id", property("string", "One namespaced selected item."));
        properties.put("item_ids", arrayProperty(
                "A semantic group of namespaced items, such as loot to store."));
        properties.put("tag", property("string", "One namespaced item tag selector."));
        properties.put("count", boundedInteger(
                "Exact total matching count to move; omit to move all source matches.",
                1, SemanticContainerTaskRecord.MAX_COUNT));
        properties.put("target_count", boundedInteger(
                "Final matching count on the destination side; required by balance.",
                0, SemanticContainerTaskRecord.MAX_COUNT));
        properties.put("block_id", property(
                "string", "Optional namespaced block type used to filter loaded containers."));
        properties.put("landmark_label", property(
                "string", "Optional remembered place around which to choose the container."));
        Map<String, Object> selection = property(
                "string", "Nearest accepts the closest safe match; unique rejects ambiguity.");
        selection.put("enum", List.of("nearest", "unique"));
        properties.put("selection", selection);
        properties.put("protected_labels", arrayProperty(
                "Remembered places whose containers must not be touched."));
        properties.put("radius", boundedInteger(
                "Bounded loaded-container search radius.", 1,
                SemanticContainerTaskRecord.MAX_RADIUS));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("operation"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    // 合并 item_id 与 item_ids、解析标签和数量，创建语义容器任务；具体是否能找到容器由任务调查。
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        LinkedHashSet<ResourceLocation> ids = new LinkedHashSet<>();
        String one = text(args, "item_id");
        if (one != null) ids.add(resource(one, "item_id"));
        for (String value : strings(args.get("item_ids"), "item_ids")) {
            ids.add(resource(value, "item_ids"));
        }
        String tagText = text(args, "tag");
        ResourceLocation tag = tagText == null ? null : resource(tagText, "tag");
        String blockText = text(args, "block_id");
        ResourceLocation block = blockText == null ? null : resource(blockText, "block_id");
        Integer count = optionalInteger(args, "count", 1, SemanticContainerTaskRecord.MAX_COUNT);
        Integer targetCount = optionalInteger(
                args, "target_count", 0, SemanticContainerTaskRecord.MAX_COUNT);
        List<String> labels = strings(args.get("protected_labels"), "protected_labels");
        int radius = integer(args, "radius", SemanticContainerTaskRecord.DEFAULT_RADIUS,
                1, SemanticContainerTaskRecord.MAX_RADIUS);
        var context = ctx(toolCallId, player);
        var record = new SemanticContainerTaskRecord(
                context.toolCallId(), context.deadline(10L * 60L * 20L),
                SemanticContainerTaskRecord.Operation.parse(text(args, "operation")),
                new ArrayList<>(ids), tag, count, targetCount, block,
                text(args, "landmark_label"),
                SemanticContainerTaskRecord.Selection.parse(text(args, "selection")),
                labels, radius);
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

    private static ResourceLocation resource(String value, String key) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException(key + " must use a namespaced id");
        return id;
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) return null;
        String value = object.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    // 缺省返回 null；当前 getAsInt 仍会截断小数，再做范围检查，不是严格整数校验，见 A10。
    private static Integer optionalInteger(
            JsonObject object, String key, int minimum, int maximum) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        final int value;
        try {
            value = object.get(key).getAsInt();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                key + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private static int integer(
            JsonObject object, String key, int fallback, int minimum, int maximum) {
        Integer value = optionalInteger(object, key, minimum, maximum);
        return value == null ? fallback : value;
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

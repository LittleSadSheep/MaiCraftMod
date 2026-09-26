// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.core.task.cook.SemanticCookTaskRecord;

/**
 * 内部加工入口，接收最终要多少成品及可使用哪些原料来源和燃料。
 * 当前能执行普通熔炉、高炉和烟熏炉；营火偏好会被解析，但执行任务会明确报告尚不支持。
 */
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
                List.of("inventory", "nearby", "wireless", "storage", "craft", "cook", "mine", "trade", "hunt")));
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
    // 解析目标、燃料与来源策略，按数量给初始时限，再建立持续加工任务；这里不会直接往炉子里放物品。
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = SemanticCookApi.newRecord(ctx(toolCallId, player), args);
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

}

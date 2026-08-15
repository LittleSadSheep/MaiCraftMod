// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;

/** Internal semantic work tool; the public MCP exposes the ability, not this implementation. */
public final class SemanticAcquireTool implements MaiCraftTool {
    @Override
    public String name() {
        return "acquire_items";
    }

    @Override
    public String description() {
        return "Make one final main-inventory fact true for any acceptable item alternative. "
                + "Declare only item ids or live item tags, the final count, allowed source families and semantic "
                + "safety constraints. The Mod observes inventory before every step, stops as soon "
                + "as the fact is true, and owns source selection, recipe recursion, loaded-world "
                + "evidence, progress-driven first-person source exploration, paths, menus and receipts. "
                + "Defaults cover ordinary survival: inventory, "
                + "provably unowned nearby drops, crafting, cooking and protection-aware mining. "
                + "Hunting may be identified as a possible source, but never starts without explicit "
                + "allow_harm; if no acceptable source entity is loaded, the Mod performs a generic "
                + "type-and-relationship entity search and re-verifies before attacking. Storage "
                + "extraction and trading require explicit allowed_sources. "
                + "Ambiguous ownership, protection or source evidence stops with "
                + "semantic recovery_options instead of silently choosing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("item_id", property("string",
                "One acceptable namespaced item id; item_ids may be used instead."));
        properties.put("item_ids", arrayProperty("string",
                "Acceptable item alternatives. Their main-inventory counts are aggregated.",
                null));
        properties.put("item_tag", property("string",
                "One namespaced item tag whose live members are acceptable alternatives, with or without #."));
        properties.put("item_tags", arrayProperty("string",
                "Namespaced item tags whose live members form one acceptable alternative set.",
                null));
        properties.put("count", boundedInteger(
                "Required final aggregate main-inventory count (default 1).", 1, 256));
        properties.put("allowed_sources", arrayProperty("string",
                "Source families the Mod may try automatically, in preferred order.",
                List.of("inventory", "nearby", "storage", "craft", "cook", "mine", "trade", "hunt")));
        properties.put("allow_harm", property("boolean",
                "Explicit semantic consent to harm living entities. Default false."));
        properties.put("protected_labels", arrayProperty("string",
                "Remembered places or possessions that must not be touched.", null));
        properties.put("radius", boundedInteger(
                "Loaded-world radius for nearby evidence (default 16).", 1, 48));

        Map<String, Object> hintProperties = new LinkedHashMap<>();
        hintProperties.put("block_ids", arrayProperty("string",
                "Semantic source block variants; never coordinates.", null));
        hintProperties.put("block_tags", arrayProperty("string",
                "Semantic block tags, with or without leading #.", null));
        hintProperties.put("entity_type_ids", arrayProperty("string",
                "Semantic entity type families; never runtime entity ids.", null));
        hintProperties.put("expected_item_ids", arrayProperty("string",
                "Expected products asserted by the semantic hint; the Mod still verifies inventory.",
                null));
        hintProperties.put("trade_profession_ids", arrayProperty("string",
                "Semantic villager profession families, not offer slots.", null));
        hintProperties.put("description", property("string",
                "Short semantic relationship between source and requested items."));
        Map<String, Object> hint = property("object",
                "Optional semantic source evidence. It never contains positions, routes, clicks or slots.");
        hint.put("properties", hintProperties);
        hint.put("required", List.of());
        hint.put("additionalProperties", false);
        properties.put("source_hint", hint);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = SemanticAcquireApi.newRecord(ctx(toolCallId, player), args, player);
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

    private static Map<String, Object> arrayProperty(
            String itemType, String description, List<String> itemEnum) {
        Map<String, Object> items = property(itemType, "");
        items.remove("description");
        if (itemEnum != null) items.put("enum", new ArrayList<>(itemEnum));
        Map<String, Object> result = property("array", description);
        result.put("items", items);
        return result;
    }
}

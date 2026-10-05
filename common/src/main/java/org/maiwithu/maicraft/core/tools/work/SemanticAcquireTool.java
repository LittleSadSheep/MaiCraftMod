// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;

/** 取物能力对应的内部工具；数量上限与真实任务一致，来源清单表达许可而非操作顺序。 */
public final class SemanticAcquireTool implements MaiCraftTool {
    @Override
    public String name() {
        return "acquire_items";
    }

    @Override
    public String description() {
        // 默认取料先按记忆翻可见箱子，再继续其他来源；实际原生拒绝与明确保护按现场事实处理。
        return "Make one final main-inventory fact true for any acceptable item alternative. "
                + "Declare only item ids or live item tags, the final count, allowed source families and semantic "
                + "safety constraints. The Mod observes inventory before every step, stops as soon "
                + "as the fact is true, and owns source selection, recipe recursion, loaded-world "
                + "evidence, progress-driven first-person source exploration, paths, menus and receipts. "
                + "Defaults cover ordinary survival: inventory, visible ordinary containers, "
                + "matching nearby drops regardless of ownership, crafting, cooking and protection-aware mining. "
                + "Hunting may be identified as a possible source, but never starts without explicit "
                + "allow_harm; if no acceptable source entity is loaded, the Mod performs a generic "
                + "type-and-relationship entity search and re-verifies before attacking. Containers are checked "
                + "in remembered-present, unvisited, remembered-absent order; each synchronized menu refreshes durable memory. "
                + "Container investigation and approach stay within 32 blocks of the request origin and require direct line of sight. "
                + "Trading requires explicit allowed_sources. "
                + "Unresolved protection or acquisition-source evidence stops with "
                + "semantic recovery_options instead of silently choosing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("item_id",
                        "One acceptable namespaced item id; item_ids may be used instead.")
                .optionalStringArray("item_ids",
                        "Acceptable item alternatives. Their main-inventory counts are aggregated.")
                .optionalString("item_tag",
                        "One namespaced item tag whose live members are acceptable alternatives, with or without #.")
                .optionalStringArray("item_tags",
                        "Namespaced item tags whose live members form one acceptable alternative set.")
                // 与公开能力共享材料倾向，内部调用也能保留模型选定的递归路线提示。
                .optionalStringArray("preferred_materials",
                        "Soft namespaced item-ID preferences for recipe routes and intermediates; available stock stays first and allowed sources are unchanged.")
                // 内部工具仍收最终合计数（合成、取工具等内部组合直接给目标总数）；公开取物能力在语义步骤启动时把它绑定成“再拿几件”。
                .optionalInteger("count",
                        "Required final aggregate main-inventory count (default 1). The public maicraft:acquire_items ability binds it as an additional count over the step's starting inventory.",
                        1, SemanticAcquireTaskRecord.MAX_FINAL_COUNT)
                .optionalEnumStringArray("allowed_sources",
                        "Optional hard source restriction: omit for ordinary acquisition, including visible containers. Carried inventory is checked first, then containers before other external sources. Only narrow this list for an explicit user restriction; it is not execution order.",
                        "inventory", "nearby", "wireless", "storage", "harvest", "craft", "cook", "mine", "trade", "hunt")
                .optionalBool("allow_harm",
                        "Explicit semantic consent to harm living entities. Default false.")
                .optionalStringArray("protected_labels",
                        "Remembered places or possessions that must not be touched.")
                .optionalInteger("radius",
                        "Explicit nearby search radius. If omitted, mining searches the effective loaded view, other nearby evidence defaults to 16 blocks, and containers to 32 from the fixed request origin.", 1, 48)
                .optionalObject("source_hint",
                        "Optional semantic source evidence. It never contains positions, routes, clicks or slots.",
                        hint -> hint
                                .optionalStringArray("block_ids",
                                        "Semantic source block variants; never coordinates.")
                                .optionalStringArray("block_tags",
                                        "Semantic block tags, with or without leading #.")
                                .optionalStringArray("entity_type_ids",
                                        "Semantic entity type families; never runtime entity ids.")
                                .optionalStringArray("expected_item_ids",
                                        "Expected products asserted by the semantic hint; the Mod still verifies inventory.")
                                .optionalStringArray("trade_profession_ids",
                                        "Semantic villager profession families, not offer slots.")
                                .optionalString("description",
                                        "Short semantic relationship between source and requested items."))
                .build();
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = SemanticAcquireApi.newRecord(ctx(toolCallId, player), args, player);
        setTask(player, record, args, reply);
    }
}

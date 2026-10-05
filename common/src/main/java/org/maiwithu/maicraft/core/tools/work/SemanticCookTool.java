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
        return Schema.object()
                .string("item_id", "Requested namespaced cooked output item.")
                // 内部工具仍收最终合计数（取物里的烧炼来源直接给目标总数）；公开烹饪能力在语义步骤启动时把它绑定成“再烧几件”。
                .integer("count", "Required final main-inventory count. The public maicraft:cook ability binds it as an additional count over the step's starting inventory.", 1,
                        SemanticCookTaskRecord.MAX_FINAL_COUNT)
                .enumStr("recipe_preference", "Semantic recipe/device preference.",
                        "auto", "fastest", "preserve_rare", "smelting", "blasting", "smoking", "campfire")
                .optionalStringArray("allowed_fuels",
                        "Namespaced fuel items the Mod may consume; omit for safe ordinary fuels.")
                .optionalEnumStringArray("allowed_sources",
                        "Source families allowed for inputs, fuel and a required workstation.",
                        "inventory", "nearby", "wireless", "storage", "harvest", "craft", "cook", "mine", "trade", "hunt")
                .optionalBool("allow_harm",
                        "Whether recursively acquiring cooking inputs may harm living entities; default false.")
                .optionalStringArray("protected_labels",
                        "Remembered places or possessions recursive acquisition must not touch.")
                .build();
    }

    @Override
    // 解析目标、燃料与来源策略，按数量给初始时限，再建立持续加工任务；这里不会直接往炉子里放物品。
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = SemanticCookApi.newRecord(ctx(toolCallId, player), args);
        setTask(player, record, args, reply);
    }
}

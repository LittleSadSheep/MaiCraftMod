package org.maiwithu.maicraft.core.tools.work;
import org.maiwithu.maicraft.core.task.collect.CollectItemsRequest;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** 世界操作工具（原始 MaiCraftTool）：拾取附近地面上的掉落物。 */
public final class CollectItemsTool implements MaiCraftTool {

    @Override
    public String name() {
        return "collect_items";
    }

    @Override
    public String description() {
        // 模型选类型或观察引用；执行器完成扫描、跟随、接触、等待和核验，范围扫空与点名收取的成功含义要分开说明。
        return "Collect loaded loose item entities from the actor's current surroundings through native contact pickup. "
                + "For MCP use goal.ability=maicraft:collect_items and put these arguments in goal.parameters; "
                + "the internal collect_items tool receives the argument object directly. "
                + "Copy drop_ref from perceive(view=surroundings,sections=[nearby_entities]) to select one dimension-bound UUID; "
                + "item_ids is an optional non-empty registered item filter and intersects drop_ref when both are supplied. "
                + "Omit both selectors to sweep all matching loaded drops. Explicit null values are invalid for all four fields. "
                + "radius is a JSON integer from 1 to 48 blocks, default 16: each scan expands the current player body box on all axes. "
                + "It is not a fixed site boundary or a limit on following an already selected moving entity. "
                + "may_alter_terrain defaults to false; true permits normal route digging, bridging or pillaring subject to the clearance whitelist and protections. "
                + "The same task selects contact stances, waits for pickup cooldown and inventory synchronization, and briefly retries no-path outcomes. "
                + "Selected drop_ref completion requires disappearance, matching inventory increase and this player's same-UUID pickup packet; "
                + "an unavailable selected stack is unconfirmed, never silently replaced. Unselected sweeps use disappearance plus inventory evidence and may succeed with zero collected when no candidates remain. "
                + "Incidental native pickups can occur outside the filter and need not be included in collected_items. "
                + "This is not a requested final inventory count and does not withdraw from backpacks or containers. "
                + "Inspect collected, collected_items, drop_collection when selected, pickup_navigation and failure evidence. "
                + "BACKGROUND: accepted means registered, not completed; follow next_attention or task get, do not resend to recover output. "
                + "On a lost reference re-observe only the needed nearby entities; answer an actual pending decision with its current decision_id, or submit a revised goal after terminal failure.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("drop_ref", "Optional complete dimension|UUID string copied from nearby_entities. Omit for a type/all-items sweep; null, empty or malformed strings are rejected. The selected stack must initially be loaded, within the scan box and match item_ids if present; its live position is then followed.")
                .optionalStringArray("item_ids", "Optional non-empty array of registered item IDs, preferably namespaced. Omit to apply no type filter; null, [], non-string entries, air and unknown IDs are rejected. Duplicates collapse. Tags, component filters and desired counts are unsupported; applies together with drop_ref.")
                .optionalInteger("radius", "Scan expansion in blocks around the actor's current body box on x/y/z; default 16, inclusive range 1..48. Null, zero, negative, fractional values and numeric strings are rejected; already selected drops can be followed beyond this range.", 1, 48)
                // 一格洞口不足以通行时，模型可在同一拾取目标中明确允许原生开路，执行器自行选择落脚点。
                .optionalBool("may_alter_terrain", "JSON boolean, default false when omitted. False uses existing routes; true permits route digging, bridging or pillaring under the clearance whitelist, body restrictions and native conditions. Null, numbers and strings are rejected. Confirmed route changes remain in the receipt even on cancellation or failure.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        // 内部工具与 MCP 使用同一选择检查，避免一个入口拒绝拼写错误、另一个却开始全捡。
        setTask(companion, CollectItemsRequest.parse(args).task(companion,
                ctx(toolCallId, companion)), args, reply);
    }
}

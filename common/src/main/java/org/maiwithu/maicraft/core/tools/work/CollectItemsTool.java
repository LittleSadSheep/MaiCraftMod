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
        // 模型决定捡哪一堆，执行器追踪其真实位置并靠近；原版吸取范围仍可能同时收入旁边的物品。
        return "Walk to dropped items nearby and let native contact pickup collect them. "
                + "Copy drop_ref from scan_nearby_entities to target one exact stack even when it moves. "
                + "A lost reference is reported as unconfirmed, never replaced with another stack. "
                + "Without drop_ref, optionally restrict item_ids (omit both to collect everything). "
                + "Nearby incidental pickups remain possible under native pickup rules. "
                + "may_alter_terrain=true permits normal navigation to clear permitted natural obstacles on the pickup approach. "
                + "Optional radius (default 16). Use after manual interactions; attack collects its own drops. "
                + "BACKGROUND: acceptance means collection is already running; wait for task_finished, do not poll or resend unchanged.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("drop_ref", "One exact observed stack reference; follows its current position.")
                .optionalStringArray("item_ids", "Optional namespaced item id(s) to collect; omit to collect all.")
                .optionalInteger("radius", "Optional search radius in blocks (default 16).", 1, 48)
                // 一格洞口不足以通行时，模型可在同一拾取目标中明确允许原生开路，执行器自行选择落脚点。
                .optionalBool("may_alter_terrain", "Allow pickup navigation to dig, bridge or pillar under the clearance whitelist and protections; default false.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        // 内部工具与 MCP 使用同一选择检查，避免一个入口拒绝拼写错误、另一个却开始全捡。
        setTask(companion, CollectItemsRequest.parse(args).task(companion,
                ctx(toolCallId, companion)), args, reply);
    }
}

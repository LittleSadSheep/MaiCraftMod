package org.maiwithu.maicraft.core.tools.inventory;
import org.maiwithu.maicraft.core.tools.InventoryOps;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 内部丢弃入口：请求把一定数量的指定物品丢到地上，拥有量不够时最多丢现有数量。
 * 工具层只解析参数和安排任务，不直接扣除物品或生成地上实体。
 */
public final class DropItemsTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final InventoryOps impl = new InventoryOps();

    private record Args(String item_id, int count) {}

    @Override
    public String name() {
        return "drop_items";
    }

    @Override
    public String description() {
        // 调用者只给精确数量；执行器负责停步朝远处、原生分堆和后续拾取范围避让，无需模型逐件下达指令。
        return "Drop an exact quantity by looking toward open space and throwing whole batches. Partial stacks "
                + "are split in the inventory and thrown together, including when the inventory is full. "
                + "Observed discarded entities are avoided by later navigation while they remain in this world. "
                + "Prefer depositing in a nearby chest when items should be kept. count above what you carry "
                + "drops everything you have of it. Returns confirmed dropped count, remaining inventory, "
                + "batch count and observed discard avoidance; an unobserved landing remains unknown.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to drop, e.g. minecraft:cobblestone.")
                .integer("count", "How many to drop (1-999).", 1, 999)
                .build();
    }

    @Override
    // 把物品种类和数量交给任务处理，等待结果后回复；具体槽位选择与菜单点击在 DropCompanionTask。
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.dropItems(a.item_id(), a.count(), ctx(toolCallId, companion)), reply);
    }
}

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
        // 先保住必经通道，再整份丢弃；已有打火石可原生销毁，点火和未烧掉的余物都必须如实回报并扑火。
        return "Discard an exact quantity in whole batches while keeping passages usable. Partial stacks "
                + "are split in the inventory and thrown together, including when the inventory is full. "
                + "Observed discarded entities are avoided by later navigation while they remain in this world. "
                + "If flint and steel is carried, ignite the actual landing cell when surrounding items and blocks can be preserved, "
                + "observe whether the items disappear, then extinguish the fire with a native left click. "
                + "Without usable ignition, prefer an open area or a small excavated side pocket. "
                + "If an attempted burn leaves items blocking the only passage, recover those exact entities before relocating them. "
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

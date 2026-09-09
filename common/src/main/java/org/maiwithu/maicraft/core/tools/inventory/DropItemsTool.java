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
        return "Drop items from your inventory onto the ground in front of you — to hand something to "
                + "a nearby player, or to shed junk when your inventory is full and no chest is nearby (when "
                + "one is, prefer depositing: interact_at the chest, then transfer — dropped items despawn "
                + "after 5 minutes). count above what you carry drops everything you have of it. Returns "
                + "how many were dropped and how many remain.";
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

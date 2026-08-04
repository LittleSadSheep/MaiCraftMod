package org.maiwithu.maicraft.core.tools.inventory;

import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskResult;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Creative-only material request. Execution is a receipt-owning, cross-tick task so the
 * reply reflects the synchronized inventory instead of a local inventory mutation.
 */
public final class TakeItemsTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    /** 一次最多一背包量级(36 格 × 64)。 */
    private static final int MAX_COUNT = 2304;

    private record Args(String item_id, Integer count) {}

    @Override
    public String name() {
        return "take_items";
    }

    @Override
    public String description() {
        return "CREATIVE MODE ONLY: conjure items directly into your inventory, like a creative player "
                + "pulling from the creative menu. Fails in survival mode — there you must mine, craft, "
                + "loot or trade for items instead. If the main inventory is full, it fails without "
                + "dropping overflow into the world.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item, e.g. minecraft:diamond.")
                .integer("count", "How many to take (1-" + MAX_COUNT + ").", 1, MAX_COUNT)
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (!WorkProfile.of(companion).freeMaterials()) {
            reply.accept(TaskResult.fail("survival mode can't conjure items — mine, craft, loot or trade"
                    + " for " + (a == null ? null : a.item_id())
                    + " instead (take_items works only in creative mode)").toJson());
            return;
        }
        Item item;
        try {
            item = org.maiwithu.maicraft.agent.tool.ToolArgs.parseItem(
                    a == null || a.item_id() == null ? "" : a.item_id());
        } catch (IllegalArgumentException bad) {
            reply.accept(TaskResult.fail("unknown item id: "
                    + (a == null ? null : a.item_id())).toJson());
            return;
        }
        int want = Math.clamp(a.count() == null ? 1 : a.count(), 1, MAX_COUNT);
        var context = TaskDispatch.ctx(toolCallId, companion);
        TaskDispatch.runSync(companion, new CreativeTakeItemsTaskRecord(
                toolCallId, context.deadline(60L * 20L), new ItemStack(item), want), reply);
    }
}

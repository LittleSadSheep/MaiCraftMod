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
 * 吃/喝背包里的东西。<b>异步</b> —— 咀嚼要三十二刻,一口一口吃到饱可能更久,
 * 占着一轮对话不合理;完事发 task_finished。
 */
public final class EatItemTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final InventoryOps impl = new InventoryOps();

    private record Args(String item_id) {}

    @Override
    public String name() {
        return "eat";
    }

    @Override
    public String description() {
        return "Eat or drink a consumable from your inventory. It's a real timed action — chewing "
                + "animation, particles and sound play over the eat duration, and only when it finishes "
                + "does it restore your hunger + saturation and apply the item's effects (e.g. a golden "
                + "apple's regeneration/absorption). Your HP then regenerates naturally from saturation, "
                + "the same as a real player — so eat to refill hunger and let health recover. Fails if "
                + "you don't carry it, it isn't a consumable, or your hunger is already full (the food is kept).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the food to eat, e.g. minecraft:cooked_beef.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.eatItem(a.item_id(), ctx(toolCallId, companion)), args, reply);
    }
}

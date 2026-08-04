package org.maiwithu.maicraft.core.tools.work;
import org.maiwithu.maicraft.core.tools.InventoryOps;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw MaiCraftTool): pick up dropped items off the ground nearby. */
public final class CollectItemsTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final InventoryOps impl = new InventoryOps();

    private record Args(List<String> item_ids, Integer radius) {}

    @Override
    public String name() {
        return "collect_items";
    }

    @Override
    public String description() {
        return "Pick up dropped items off the ground nearby. The entity travels to each dropped item "
                + "(it auto-absorbs items it gets close to) until none remain in range — terrain is "
                + "handled automatically: it digs and bridges on its own if drops landed in a pit or "
                + "across a gap. Optionally restrict to specific item_ids (omit to collect everything). "
                + "Optional radius (default 16). Use after manual interactions; attack collects its own drops. "
                + "BACKGROUND: acceptance means collection is already running; wait for task_finished, do not poll or resend unchanged.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalStringArray("item_ids", "Optional namespaced item id(s) to collect; omit to collect all.")
                .optionalInteger("radius", "Optional search radius in blocks (default 16).", 1, 48)
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.collectItems(a.item_ids(), a.radius(),
                ctx(toolCallId, companion)), args, reply);
    }
}

package org.maiwithu.maicraft.core.tools.inventory;
import org.maiwithu.maicraft.core.tools.CraftOps;
import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.runSync;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Plan a client-known recipe and dispatch its receipt-owned crafting task. */
public final class CraftTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final CraftOps impl = new CraftOps();

    private record Args(String item_id, Integer count) {}

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public String description() {
        return "Reach a requested main-inventory count through a client-known ordinary crafting recipe. "
                + "If that inventory condition is already true, it stops immediately without exploring "
                + "another recipe. Otherwise candidates prefer material-complete routes whose physical "
                + "workstation is ready or internally preparable, then the smallest material gap; failures "
                + "report structured accepted item ids and satisfied/missing counts. Execution uses "
                + "the real synchronized crafting menu across ticks. A 2x2 recipe returns to your inventory "
                + "grid if another menu is open. A 3x3 recipe reuses any compatible open modded crafting "
                + "surface, approaches a loaded crafting table, or places a carried table. When none is "
                + "available it reports the workstation prerequisite; the semantic craft/acquire coordinator "
                + "owns obtaining it before retrying this unchanged recipe. Only for "
                + "[crafting] recipes; other recipe types use their own visible station workflow.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to craft, e.g. minecraft:iron_pickaxe.")
                .optionalInteger("count", "How many of the item you want (default 1).", 1, 256)
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        CraftOps.Plan plan = impl.plan(
                a.item_id(), a.count(), self, ctx(toolCallId, self));
        if (!plan.executable()) {
            reply.accept(plan.immediate().toJson());
            return;
        }
        runSync(self, plan.task(), reply);
    }
}

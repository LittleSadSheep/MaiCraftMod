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
 * 内部丢弃入口：公开目标先由 GeneralAbilityAdapter 核对物品与数量，这里只转交原生执行任务。
 * 任务可能走到空地、挖侧袋或使用已有打火石；内部执行时缺料会缩小本轮目标，不能等同于公开入口允许超量请求。
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
        // 先说明实际副作用和确认边界，避免把清理垃圾当成原地赠物，或把投掷完成误读为全部烧毁。
        return "Internal executor for maicraft:drop_items. Use the public semantic ability for unwanted items, "
                + "not delivery to a player or an exact receiving cell. Public item_id and count belong in goal.parameters; "
                + "this internal tool takes them directly. Public adaptation requires both and requests a decision if "
                + "the normalized count exceeds readable inventory. InventoryOps clamps internal count to 1..999, "
                + "and the executor targets at most the inventory available after closing the previous GUI. "
                + "Matching uses the registered item type, including readable armor and offhand slots; it does not "
                + "select a particular name, enchantment or component variant, or unpack nested containers. "
                + "The actor may walk to a loaded open area, excavate a four-block-deep, two-block-high side pocket, "
                + "or use carried flint and steel to attempt disposal by fire. These choices have no per-call switches. "
                + "After settling and looking along the chosen direction, whole stacks are thrown together; a partial "
                + "quantity is split through visible native menu clicks and then thrown once, including with a full inventory. "
                + "Exact menu postconditions are confirmed by synchronization or the existing stable round-trip window; "
                + "ground entities are observed separately. Fire handling checks observed entities and fire blocks, "
                + "attempts native extinguishing, and records surviving items or uncertain ignition. Only a site selected "
                + "as requiring burning to clear its passage triggers recovery of surviving tracked UUIDs and a new non-burning site. "
                + "Tracked discard pickup areas remain excluded from later navigation in this body/world session. "
                + "Returns dropped, confirmed_thrown_units, recovered_after_burn, remaining_in_inventory, batch and attempt counts, "
                + "discard_site, discarded_item_avoidance, disposal_attempts, and discard_fire when reached. Success is not "
                + "proof that every item burned or every fire was extinguished. outcome_uncertain concerns unsettled tosses "
                + "and any added GUI uncertainty; also inspect entity observation flags and discard_fire.issues/cleanup_queued. "
                + "Do not blindly replay the original quantity after interruption or restart; reconcile actual inventory and effects first.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Required registered item ID, e.g. minecraft:cobblestone; one item type, not a tag, slot, UUID or component filter.")
                .integer("count", "Required integer number of individual items, 1..999; not stacks or a final inventory target. Neither 0 nor null means all.", 1, 999)
                .build();
    }

    @Override
    // 这里只登记任务单，runSync 并不当场完成投掷；语义父任务捕获后持续推进，数量和点火事实由后续回执结算。
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.dropItems(a.item_id(), a.count(), ctx(toolCallId, companion)), reply);
    }
}

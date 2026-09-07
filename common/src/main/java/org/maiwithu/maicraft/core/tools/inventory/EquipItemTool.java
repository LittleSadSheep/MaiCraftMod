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

/** World-action tool (raw MaiCraftTool): equip an item (tool/weapon/armor/accessory) from the inventory. */
public final class EquipItemTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final InventoryOps impl = new InventoryOps();

    private record Args(String action, String item_id, String slot) {}

    @Override
    public String name() {
        return "equip_item";
    }

    @Override
    public String description() {
        return "Equip an item from your OWN inventory: native armor to its equipment slot, "
                + "other items to the main hand; holding an item does not use it. The previous item is "
                + "stowed back. Or take gear OFF: action=unequip with a slot stows it into the "
                + "inventory ('armor' strips all four pieces, 'mainhand' frees your hand); fails if "
                + "there is no room.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "equip (default): wear/wield item_id. "
                        + "unequip: empty a slot back into the inventory.",
                        "equip", "unequip")
                .optionalString("item_id", "Namespaced id of the item to equip; must be in the "
                        + "inventory. Required to equip, ignored for unequip.")
                .optionalEnum("slot", "equip: omit to auto-route by item type, set only to force a "
                        + "hand or a specific armor piece. unequip: required — the slot to empty; "
                        + "'armor' means all four armor pieces.",
                        "mainhand", "offhand", "head", "chest", "legs", "feet", "armor")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.equipItem(a.action(), a.item_id(), a.slot(), ctx(toolCallId, companion)), reply);
    }
}

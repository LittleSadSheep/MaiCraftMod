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
 * 内部装备入口，穿戴与卸下共用一个工具名；参数中的 action 决定建立哪种任务。
 * 它自己不选择更好的装备，也不直接移动物品。
 */
public final class EquipItemTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final InventoryOps impl = new InventoryOps();

    private record Args(String action, String item_id, String slot) {}

    @Override
    public String name() {
        return "equip_item";
    }

    @Override
    // 穿戴要看目标部位的实际物品；卸下还要看仍穿着的清单，当前成功外壳不保证所有部位已清空。
    public String description() {
        return "穿戴或手持主背包中的指定物品：省略 slot 按物品自然部位，主手只拿着不使用，副手走原生交换。"
                + "action=unequip 需要 slot，armor 按头胸腿脚卸下四件，mainhand 优先切到空快捷栏。"
                + "不丢弃物品腾空间；当前没有空格会保留装备，仍可能返回 success，必须同时读 removed 与 still_worn。"
                + "按物品 ID 判断，不选择最优附魔或耐久；已经穿戴但主背包没有另一件时，当前仍可能报缺料。";
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
    // 解析穿戴／卸下要求，交给 InventoryOps 创建对应任务。runSync 仍由任务调度逐刻执行，不是当场改装备栏。
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.equipItem(a.action(), a.item_id(), a.slot(), ctx(toolCallId, companion)), reply);
    }
}

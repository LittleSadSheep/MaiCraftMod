// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.task.TaskDispatch;

/** 内部附魔执行入口：台子坐标由语义适配器确定，实际按钮和槽位仍由可见菜单任务处理。 */
public final class EnchantTool implements MaiCraftTool {
    private static final Set<String> FIELDS = Set.of("item_id","offer_tier","max_levels_spent","max_lapis","x","y","z");
    @Override public String name() { return EnchantTaskRecord.TOOL_NAME; }
    @Override public String description() {
        return "Enchant one carried item at an existing enchanting table using a visible native menu and one bounded quote. "
                + "Requires explicit level-spend and lapis limits; no automatic reroll or retry after reservation.";
    }
    @Override public Map<String,Object> parameterSchema() {
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("item_id", Map.of("type","string","description","Exact carried item type to enchant once."));
        for (String axis : List.of("x","y","z")) fields.put(axis, Map.of("type","integer","description","Resolved existing enchanting table coordinate."));
        fields.put("offer_tier", Map.of("type","integer","minimum",1,"maximum",3,"default",1));
        for (String limit : List.of("max_levels_spent","max_lapis")) fields.put(limit, Map.of("type","integer","minimum",0,"maximum",3));
        return Map.of("type","object","properties",fields,"required",List.of("item_id","max_levels_spent","max_lapis","x","y","z"),"additionalProperties",false);
    }
    @Override public void onGameCall(String callId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        for (String key : args.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException("unexpected enchant execution field: " + key);
        EnchantParameters choice = EnchantParameters.parse(args);
        BlockPos table = new BlockPos(coordinate(args,"x"),coordinate(args,"y"),coordinate(args,"z"));
        var context = TaskDispatch.ctx(callId, player);
        // 只创建有界任务单，第一次消耗必须等总任务绑定的一次性记录落盘，不能在此直接改经验或物品。
        var record = new EnchantTaskRecord(context.toolCallId(), context.deadline(120L * 20L),
                choice.itemId(), table, choice.offerTier(), choice.maxLevelsSpent(), choice.maxLapis());
        TaskDispatch.setTask(player, record, args, reply);
    }
    private static int coordinate(JsonObject args,String axis) {
        return EnchantParameters.integer(args,axis,null,Integer.MIN_VALUE,Integer.MAX_VALUE);
    }
}

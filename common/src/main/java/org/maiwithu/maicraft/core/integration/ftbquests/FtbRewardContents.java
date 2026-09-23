// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 解释会获得什么；随机数量只展示范围，不抽奖、不检查背包领取副作用，也不运行命令奖励。 */
final class FtbRewardContents {
    private FtbRewardContents() {}
    static void append(JsonObject out, Object reward, String type) {
        CompoundTag data = FtbQuestData.definition(reward);
        out.add("native_definition", FtbQuestData.json(data)); out.addProperty("content_status", "structured");
        switch (type) {
            case "ftbquests:item" -> {
                ItemStack item = (ItemStack) call(reward, "getItem");
                long count = ((Number) call(reward, "getCount")).longValue();
                out.addProperty("item_id", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
                out.addProperty("item_name", item.getHoverName().getString());
                out.addProperty("count_min", Long.toString(count)); out.addProperty("count_max", Long.toString(count + data.getInt("random_bonus")));
                out.addProperty("only_one", data.getBoolean("only_one"));
                out.addProperty("grant_condition", data.getBoolean("only_one") ? "matching_item_not_already_in_inventory" : "none");
                out.add("item_stack", FtbQuestData.json(data.get("item")));
            }
            case "ftbquests:xp" -> { out.addProperty("unit", "xp_points"); out.addProperty("amount", data.getInt("xp")); }
            case "ftbquests:xp_levels" -> { out.addProperty("unit", "xp_levels"); out.addProperty("amount", data.getInt("xp_levels")); }
            case "ftbquests:currency" -> { out.addProperty("unit", "ftb_money"); out.addProperty("amount", data.getInt("amount")); }
            case "ftbquests:advancement" -> {
                out.addProperty("advancement", data.getString("advancement")); out.addProperty("criterion", data.getString("criterion"));
            }
            case "ftbquests:gamestage" -> {
                out.addProperty("stage", data.getString("stage")); out.addProperty("remove", data.getBoolean("remove"));
            }
            case "ftbquests:toast" -> out.addProperty("description", data.getString("description"));
            case "ftbquests:command" -> {
                out.addProperty("command", data.getString("command"));
                out.addProperty("execution", "server_reward_only");
            }
            case "ftbquests:custom" -> out.addProperty("content_status", "server_defined");
            default -> out.addProperty("content_status", "native_definition_only");
        }
    }
}

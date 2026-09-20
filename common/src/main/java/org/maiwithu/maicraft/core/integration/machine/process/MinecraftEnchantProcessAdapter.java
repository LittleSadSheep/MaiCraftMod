// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.task.enchant.EnchantParameters;
import org.maiwithu.maicraft.core.task.enchant.EnchantTaskRecord;
import org.maiwithu.maicraft.task.TaskRecord;
import com.google.gson.Gson;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.task.enchant.EnchantmentQuote;

/** 把附魔作为已有台子上的原生加工机制；共用原来的可见GUI、报价、费用、成品与返还执行器。 */
public final class MinecraftEnchantProcessAdapter implements NativeProcessAdapter {
    public static final String ID = "minecraft:enchanting";
    @Override public String id() { return ID; }
    @Override public boolean available() { return true; }
    @Override public JsonObject contract() {
        // 完整成本契约只随匹配台子的观察或按需知识返回，不把每个机制的参数加入默认能力描述。
        return JsonParser.parseString("""
                {"process":"minecraft:enchanting","description":"在已加载原版附魔台为一件未附魔物品选择真实报价，核验费用、成品并取回。",
                 "parameters":{"item_id":{"type":"resource_id","required":true},"offer_tier":{"type":"integer","default":1,"minimum":1,"maximum":3},
                  "max_levels_spent":{"type":"integer","required":true,"minimum":0,"maximum":3},
                  "max_lapis":{"type":"integer","required":true,"minimum":0,"maximum":3}},
                 "quote_observation":"装入自有材料后，任务progress展示三档原生报价；报价改变时停止，等级门槛与实际扣级分开报告。",
                 "completion":"确认一次按钮的服务端结果、实际扣费、完整成品返还及关闭菜单。",
                 "retry":"消费预约开始后不自动重发；未知结果保留人工核验。","background_watch_supported":false}
                """).getAsJsonObject();
    }
    @Override public void validate(JsonObject parameters) {
        if (parameters == null || !Set.of("item_id", "offer_tier", "max_levels_spent", "max_lapis").containsAll(parameters.keySet()))
            throw new IllegalArgumentException("enchanting process accepts item_id, offer_tier, max_levels_spent and max_lapis only");
        EnchantParameters.parse(parameters);
    }
    @Override public boolean matches(LocalPlayer player, BlockPos position) {
        return player != null && position != null && player.level().isLoaded(position)
                && player.level().getBlockState(position).is(Blocks.ENCHANTING_TABLE);
    }
    @Override public JsonObject inspect(LocalPlayer player, BlockPos position) {
        JsonObject out = new JsonObject(); out.addProperty("matched", matches(player, position));
        out.addProperty("evidence_source", "loaded_client_block"); out.addProperty("quote_available", false);
        out.addProperty("next_observation", "真实报价需在该过程装入自有物品后的可见附魔菜单中观察；此处不打开菜单或移动材料。");
        if (player.containerMenu instanceof EnchantmentMenu menu
                && MachineMenu.openedAt(player, position)) {
            // 只读已经同步到匹配菜单的可见线索；观察不锁定报价，也不能代替消费前再次校验。
            var quote = EnchantmentQuote.capture(player, menu);
            out.add("quote", new Gson().toJsonTree(quote.describe()));
            out.addProperty("quote_evidence_source", "native_client_menu");
            out.addProperty("quote_available", quote.offers().stream().anyMatch(offer -> offer.requiredLevel() > 0 && !offer.clue().isEmpty() && offer.clueLevel() > 0));
        }
        return out;
    }
    @Override public TaskRecord createTask(String callId, long deadline, LocalPlayer player, BlockPos position, JsonObject parameters) {
        validate(parameters); var parsed = EnchantParameters.parse(parameters);
        return new EnchantTaskRecord(callId, deadline, parsed.itemId(), position, parsed.offerTier(), parsed.maxLevelsSpent(), parsed.maxLapis());
    }
}

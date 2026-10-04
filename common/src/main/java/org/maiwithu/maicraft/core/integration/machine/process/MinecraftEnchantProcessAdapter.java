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
                {"process":"minecraft:enchanting","description":"通过 operate_machine 的 run_production 使用 schema_version=2、process=minecraft:enchanting；下列字段放在 goal.parameters.production.parameters。只为主背包中的一件可附魔且未附魔物品工作，书会成为附魔书；不建台、不取料、不刷经验、不保证未显示的随机附魔。外层需要真实 snapshot_id、匹配目标及 allow_use=true。",
                 "parameters":{"item_id":{"type":"resource_id","required":true,"description":"完整物品注册ID；选择一件现有兼容物品并保留其余组件，不能传槽位或指定多个数量。"},"offer_tier":{"type":"integer","default":1,"minimum":1,"maximum":3,"description":"原生报价档位，省略取1；0、null、小数均拒绝，不自动换档。"},
                  "max_levels_spent":{"type":"integer","required":true,"minimum":0,"maximum":3,"description":"实际扣除的玩家等级上限，不是显示的入场等级门槛；0表示不许扣级，省略或null拒绝。创造模式仍按实际等级变化核对。"},
                  "max_lapis":{"type":"integer","required":true,"minimum":0,"maximum":3,"description":"实际消耗的青金石件数上限；0表示不许消耗，省略或null拒绝。无限材料身体可有零青金石成本，仍读取实际报价。"}},
                 "quote_observation":"装入自有物品和所需青金石后，progress 展示三档同步报价。提交前报价变化会重新读取并等待稳定，仍遵守原档位、费用上限及真实等级门槛。",
                 "completion":"先持久化消费预约，再原生提交；核对新附魔、青金石与等级变化，取回成品及自有剩余材料并确认关菜单。查看 enchantment_confirmed、actual_levels_spent、actual_lapis_spent、cleanup_status、outcome_uncertain，而非只看按钮已发出。",
                 "retry":"CONFIRMED_NOT_APPLIED 可沿同一消费身份重读报价并最多重试两次；已消费或未知结果不自动再附魔。暂停、取消、死亡或重连后先查原任务与消费预约；不要换 request_key 猜测性重做。","background_watch_supported":false}
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

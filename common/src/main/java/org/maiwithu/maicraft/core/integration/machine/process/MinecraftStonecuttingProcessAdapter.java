// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.task.stonecutter.StonecuttingParameters;
import org.maiwithu.maicraft.core.task.stonecutter.StonecuttingTaskRecord;
import org.maiwithu.maicraft.task.TaskRecord;

/** 把切石作为已有切石机上的原生加工机制；共用真实配方列表、逐件产出核验与取回关闭执行器。 */
public final class MinecraftStonecuttingProcessAdapter implements NativeProcessAdapter {
    public static final String ID = "minecraft:stonecutting";
    @Override public String id() { return ID; }
    @Override public boolean available() { return true; }
    @Override public JsonObject contract() {
        // 完整配方契约只随匹配切石机的观察或按需知识返回，不把每个机制的参数加入默认能力描述。
        return JsonParser.parseString("""
                {"process":"minecraft:stonecutting","description":"在已加载原版切石机按真实配方把输入物品切制为指定产物，逐件核验产出、归位余料并取回。",
                 "parameters":{"item_id":{"type":"resource_id","required":true},"output_item_id":{"type":"resource_id","required":true},
                  "count":{"type":"integer","default":1,"minimum":1,"maximum":64}},
                 "recipe_observation":"输入装入后菜单列出该输入的真实可用产物；按产物匹配配方，输入无配方或产物不匹配时明确失败。",
                 "completion":"确认一次配方选择、整批产物取回、输入余料归位及关闭菜单。",
                 "retry":"消费预约开始后不自动重发；未知结果保留人工核验。","background_watch_supported":false}
                """).getAsJsonObject();
    }
    @Override public void validate(JsonObject parameters) {
        if (parameters == null || !Set.of("item_id", "output_item_id", "count").containsAll(parameters.keySet()))
            throw new IllegalArgumentException("stonecutting process accepts item_id, output_item_id and count only");
        StonecuttingParameters.parse(parameters);
    }
    @Override public boolean matches(LocalPlayer player, BlockPos position) {
        return player != null && position != null && player.level().isLoaded(position)
                && player.level().getBlockState(position).is(Blocks.STONECUTTER);
    }
    @Override public JsonObject inspect(LocalPlayer player, BlockPos position) {
        JsonObject out = new JsonObject();
        out.addProperty("matched", matches(player, position));
        out.addProperty("evidence_source", "loaded_client_block");
        out.addProperty("recipes_available", false);
        out.addProperty("next_observation", "输入装入后的可见切石菜单才列出该输入的真实产物；此处不打开菜单或移动材料。");
        if (player.containerMenu instanceof StonecutterMenu menu && MachineMenu.openedAt(player, position)) {
            // 只读已经同步到匹配菜单的配方列表；观察不放置材料，也不能代替切制前的再次校验。
            var outputs = new JsonArray();
            menu.getRecipes().forEach(recipe -> outputs.add(BuiltInRegistries.ITEM.getKey(
                    recipe.value().getResultItem(player.level().registryAccess()).getItem()).toString()));
            out.add("outputs_for_current_input", outputs);
            out.addProperty("recipes_available", outputs.size() > 0);
        }
        return out;
    }
    @Override public TaskRecord createTask(String callId, long deadline, LocalPlayer player, BlockPos position, JsonObject parameters) {
        validate(parameters);
        var parsed = StonecuttingParameters.parse(parameters);
        return new StonecuttingTaskRecord(callId, deadline, parsed, position);
    }
}

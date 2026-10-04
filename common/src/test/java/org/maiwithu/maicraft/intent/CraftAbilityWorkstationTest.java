// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;
import org.maiwithu.maicraft.task.TaskState;

/** 从两个公开能力经过正式参数适配，核对同一随身工作台和木锄材料不会落入递归补台。 */
public final class CraftAbilityWorkstationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (String ability : List.of("maicraft:craft", "maicraft:acquire_items")) {
            try (var h = new InteractionWorldTestHarness()) {
                h.position(new Vec3(8.5, 1, 8.5));
                h.inventory.setItem(12, new ItemStack(Items.CRAFTING_TABLE));
                h.inventory.setItem(13, new ItemStack(Items.OAK_PLANKS, 2));
                h.inventory.setItem(14, new ItemStack(Items.STICK, 2));
                // 规划会查看当前合成网格；使用真实背包菜单，不能让未初始化夹具槽位掩盖木锄配方。
                h.player.containerMenu = new InventoryMenu(h.inventory, false, h.player);
                var id = ResourceLocation.parse("minecraft:wooden_hoe");
                var recipe = new RecipeHolder<>(id, new ShapedRecipe("", CraftingBookCategory.EQUIPMENT,
                        ShapedRecipePattern.of(Map.of('P', Ingredient.of(Items.OAK_PLANKS), 'S', Ingredient.of(Items.STICK)),
                                List.of("PP", " S", " S")), new ItemStack(Items.WOODEN_HOE)));
                RecipeManager manager = ClientRuntime.requireContext(h.player).connection().getRecipeManager();
                manager.replaceRecipes(List.of(recipe));
                var goal = new Goal(ability, "合成一把木锄", null,
                        "{\"item_id\":\"minecraft:wooden_hoe\",\"count\":1}", "{}", List.of(), List.of());
                var action = (IntentAction.Tool) AbilityAdapter.adapt(goal, h.player, null);
                var record = SemanticAcquireApi.newRecord(new ToolContext("public-workstation", 0),
                        JsonParser.parseString(action.argumentsJson()).getAsJsonObject(), h.player);
                var task = new SemanticAcquireCompanionTask(h.player, record);
                var start = SemanticAcquireCompanionTask.class.getDeclaredMethod("onStart"); start.setAccessible(true); start.invoke(task);
                var root = SemanticAcquireCompanionTask.class.getDeclaredField("rootNeed"); root.setAccessible(true);
                var attempt = SemanticAcquireCompanionTask.class.getDeclaredMethod("attemptCraft", root.getType());
                attempt.setAccessible(true); attempt.invoke(task, root.get(task));
                var active = SemanticAcquireCompanionTask.class.getDeclaredField("activeRecord"); active.setAccessible(true);
                check(active.get(task) instanceof CraftTaskRecord craft && craft.recipeId.equals(id)
                        && craft.count == 1, "两个公开入口都应直接派木锄合成，不能先制造工作台：" + ability);
                // 此处只验证调度决定；真正摆台和菜单点击另由模板副本实机覆盖，不在只读规划中伪造产物。
                var trace = SemanticAcquireCompanionTask.class.getDeclaredField("recipeTrace"); trace.setAccessible(true);
                check(((List<?>) trace.get(task)).isEmpty() && h.blockUses() == 0
                        && h.inventory.countItem(Items.CRAFTING_TABLE) == 1, "已有材料不展开备料树，也不提前扣台");
                task.result(TaskState.CANCELLED);
            }
        }
        System.out.println("CraftAbilityWorkstationTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

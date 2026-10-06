// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 知识层能读到、但本端枚举不出输入候选的熔炼配方（标签或谓词原料无成员），
 * 规划器不得把它静默丢弃后伪装成"只有最快一条路线"或"没有匹配配方"：
 * 失败回执必须点名这些配方与原因，并保留每条已评估路线的阻碍层。
 */
public final class CookingFallbackRouteDiagnosisTest {
    private static final TagKey<net.minecraft.world.item.Item> UNREADABLE_INPUT =
            TagKey.create(Registries.ITEM, ResourceLocation.withDefaultNamespace("test/unreadable_input"));

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        unreachablePlanNamesEveryRouteAndSkip();
        smeltingPreferenceNamesTheUnreadableRecipe();
        allInputsUnreadableGetsStructuredDiagnosis();
        System.out.println("CookingFallbackRouteDiagnosisTest: passed");
    }

    // 高炉路线可读但工作站不可得、熔炼路线输入不可读：回执必须同时交代两条路线的去向，
    // 不能只报 closest plan 锁死高炉，让调用方误以为熔炼配方不存在。
    private static void unreachablePlanNamesEveryRouteAndSkip() throws Exception {
        try (var world = world(true)) {
            stock(world);
            var task = new SemanticCookCompanionTask(world.game.player,
                    request(SemanticCookTaskRecord.Preference.AUTO, List.of()));
            task.start(world.game.player);
            check(task.tick(world.game.player) == TaskState.RUNNING, "立项失败也要先交付一次运行");
            world.game.nextTick();
            check(task.tick(world.game.player) == TaskState.FAILED, "两条路线都不可执行时如实失败");
            check("no_reachable_cooking_plan".equals(CookingTestWorld.read(task, "failureCode")),
                    "失败码保持可检索的立项失败");
            @SuppressWarnings("unchecked")
            Map<String, Object> diagnosis =
                    (Map<String, Object>) CookingTestWorld.read(task, "planningDiagnosis");
            check(diagnosis != null && !diagnosis.isEmpty(), "回执必须带结构化诊断表");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> routes = (List<Map<String, Object>>) diagnosis.get("planning_routes");
            check(routes != null && routes.size() == 1
                            && "minecraft:iron_ingot_from_blasting_raw_iron".equals(routes.getFirst().get("recipe_id"))
                            && List.of("fuel", "workstation").equals(routes.getFirst().get("blocked_layers")),
                    "已评估路线要带着阻碍层进回执，不再只点名 closest 一条");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skipped = (List<Map<String, Object>>) diagnosis.get("skipped_recipes");
            check(skipped != null && skipped.size() == 1
                            && "minecraft:iron_ingot_from_smelting_raw_iron".equals(skipped.getFirst().get("recipe_id"))
                            && "input_alternatives_unreadable".equals(skipped.getFirst().get("reason")),
                    "输入不可读的熔炼配方必须以跳过证据出现在回执里");
            String message = String.valueOf(CookingTestWorld.read(task, "failureMessage"));
            check(message.contains("blast_furnace"), "closest plan 仍点名高炉路线");
        }
    }

    // 实测病灶复现：recipe_preference=smelting 报"没有匹配配方"，而配方页明明有 smelting 配方。
    // 偏好过滤后无人可选时，被偏好覆盖却因输入不可读跳过的配方必须点名。
    private static void smeltingPreferenceNamesTheUnreadableRecipe() throws Exception {
        try (var world = world(true)) {
            stock(world);
            var task = new SemanticCookCompanionTask(world.game.player,
                    request(SemanticCookTaskRecord.Preference.SMELTING, List.of()));
            task.start(world.game.player);
            check(task.tick(world.game.player) == TaskState.RUNNING, "立项失败也要先交付一次运行");
            world.game.nextTick();
            check(task.tick(world.game.player) == TaskState.FAILED, "偏好无候选时如实失败");
            check("no_preferred_recipe".equals(CookingTestWorld.read(task, "failureCode")),
                    "失败码保持 no_preferred_recipe");
            String message = String.valueOf(CookingTestWorld.read(task, "failureMessage"));
            check(message.contains("iron_ingot_from_smelting_raw_iron"),
                    "回执必须点名被跳过的 smelting 配方，不能只说没有匹配配方");
            check(message.contains("input_alternatives_unreadable"),
                    "回执必须写明跳过原因是输入候选不可读");
        }
    }

    // 唯一配方输入不可读：不再是含混的"没有配方产出"，而是点名配方与补证据的方向。
    private static void allInputsUnreadableGetsStructuredDiagnosis() throws Exception {
        try (var world = world(false)) {
            stock(world);
            var task = new SemanticCookCompanionTask(world.game.player,
                    request(SemanticCookTaskRecord.Preference.AUTO, List.of()));
            task.start(world.game.player);
            check(task.tick(world.game.player) == TaskState.RUNNING, "立项失败也要先交付一次运行");
            world.game.nextTick();
            check(task.tick(world.game.player) == TaskState.FAILED, "无可执行配方时如实失败");
            check("cooking_recipe_inputs_unreadable".equals(CookingTestWorld.read(task, "failureCode")),
                    "新失败码区分'配方存在但输入不可读'与'确实没有配方'");
            String message = String.valueOf(CookingTestWorld.read(task, "failureMessage"));
            check(message.contains("iron_ingot_from_smelting_raw_iron")
                            && message.contains("input_alternatives_unreadable"),
                    "回执点名配方与原因，并指向配方知识页");
            @SuppressWarnings("unchecked")
            Map<String, Object> diagnosis =
                    (Map<String, Object>) CookingTestWorld.read(task, "planningDiagnosis");
            check(diagnosis != null && diagnosis.get("skipped_recipes") != null,
                    "结构化诊断携带跳过配方表");
        }
    }

    private record World(InteractionWorldTestHarness game) implements AutoCloseable {
        @Override public void close() throws Exception { game.close(); }
    }

    /** withBlasting=false 时只装一条输入不可读的熔炼配方；true 时再加可读的高炉路线。 */
    private static World world(boolean withBlasting) throws Exception {
        var recipes = new java.util.ArrayList<RecipeHolder<?>>();
        recipes.add(new RecipeHolder<>(ResourceLocation.withDefaultNamespace("iron_ingot_from_smelting_raw_iron"),
                new SmeltingRecipe("", CookingBookCategory.MISC,
                        Ingredient.of(UNREADABLE_INPUT), new ItemStack(Items.IRON_INGOT), 0, 200)));
        if (withBlasting) {
            recipes.add(new RecipeHolder<>(ResourceLocation.withDefaultNamespace("iron_ingot_from_blasting_raw_iron"),
                    new BlastingRecipe("", CookingBookCategory.MISC,
                            Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 100)));
        }
        var harness = new InteractionWorldTestHarness();
        var manager = new RecipeManager(RegistryAccess.EMPTY);
        manager.replaceRecipes((Iterable<RecipeHolder<?>>) (Iterable<?>) List.copyOf(recipes));
        java.lang.reflect.Field field = ClientPacketListener.class.getDeclaredField("recipeManager");
        field.setAccessible(true);
        field.set(harness.player.connection, manager);
        field = ClientLevel.class.getDeclaredField("connection");
        field.setAccessible(true);
        field.set(harness.level, harness.player.connection);
        field = Level.class.getDeclaredField("registryAccess");
        field.setAccessible(true);
        field.set(harness.level, RegistryAccess.EMPTY);
        var inventoryMenu = new InventoryMenu(harness.inventory, false, harness.player);
        field = Player.class.getDeclaredField("inventoryMenu");
        field.setAccessible(true);
        field.set(harness.player, inventoryMenu);
        harness.player.containerMenu = inventoryMenu;
        return new World(harness);
    }

    private static void stock(World world) {
        world.game.inventory.clearContent();
        world.game.inventory.setItem(0, new ItemStack(Items.RAW_IRON, 5));
        world.game.inventory.setItem(1, new ItemStack(Items.COAL, 64));
        world.game.inventory.setItem(2, new ItemStack(Items.COBBLESTONE, 78));
        world.game.inventory.setItem(3, new ItemStack(Items.STICK, 3));
    }

    private static SemanticCookTaskRecord request(
            SemanticCookTaskRecord.Preference preference, List<ResourceLocation> fuels) {
        return new SemanticCookTaskRecord("cook-fallback-diagnosis", 100000,
                ResourceLocation.withDefaultNamespace("iron_ingot"), 5, preference, fuels,
                List.of(Source.INVENTORY, Source.CRAFT, Source.COOK), false, List.of());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

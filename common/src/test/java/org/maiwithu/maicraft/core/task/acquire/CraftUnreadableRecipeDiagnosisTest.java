// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.lang.reflect.Field;
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
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.tools.CraftOps;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 产物对得上目标、但输入候选在本端枚举为空的合成配方（标签/谓词原料无成员），
 * 规划器不得静默丢弃后报"没有配方"：直接规划与语义取物的失败回执都要点名配方与原因。
 */
public final class CraftUnreadableRecipeDiagnosisTest {
    private static final TagKey<net.minecraft.world.item.Item> UNREADABLE_INPUT =
            TagKey.create(Registries.ITEM, ResourceLocation.withDefaultNamespace("test/unreadable_input"));

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        directPlanNamesUnreadableRecipe();
        acquireIssueCarriesUnreadableEvidence();
        System.out.println("CraftUnreadableRecipeDiagnosisTest: passed");
    }

    // 实测病灶复现：furnace 配方在证据里可见，craft 却报"没有配方"。现在回执点名配方与原因。
    private static void directPlanNamesUnreadableRecipe() throws Exception {
        try (var harness = new InteractionWorldTestHarness()) {
            harness.position(new net.minecraft.world.phys.Vec3(8.5, 1, 8.5));
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    harness.set(new net.minecraft.core.BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            install(harness, List.of(furnaceRecipe()));
            harness.inventory.clearContent();
            harness.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 78));
            CraftOps.Plan plan = new CraftOps().plan("minecraft:furnace", 1, harness.player,
                    new ToolContext("craft-unreadable", 1000L));
            check(!plan.executable(), "输入不可读的配方不能直接执行");
            check(plan.recoveryCandidates().isEmpty(), "没有可比较的补料候选");
            Map<String, Object> data = plan.immediate().data();
            Object unreadable = data.get("recipes_with_unreadable_inputs");
            check(unreadable instanceof List<?> rows && !rows.isEmpty(),
                    "回执必须携带不可读配方表");
            String message = String.valueOf(plan.immediate().message());
            check(message.contains("minecraft:furnace") && message.contains("input_alternatives_unreadable"),
                    "失败话术点名配方与原因，不再伪装成没有配方");
        }
    }

    // 语义取物整链：craft 家族评估里留下可检索的跳过证据，per-family 失败话术可追溯到原因。
    private static void acquireIssueCarriesUnreadableEvidence() throws Exception {
        try (var harness = new InteractionWorldTestHarness()) {
            harness.position(new net.minecraft.world.phys.Vec3(8.5, 1, 8.5));
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    harness.set(new net.minecraft.core.BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            install(harness, List.of(furnaceRecipe()));
            harness.inventory.clearContent();
            harness.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 78));
            var record = new SemanticAcquireTaskRecord("craft-unreadable", 100000,
                    List.of(ResourceLocation.withDefaultNamespace("furnace")), 1,
                    List.of(SemanticAcquireTaskRecord.Source.INVENTORY,
                            SemanticAcquireTaskRecord.Source.CRAFT),
                    false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(harness.player, record);
            task.start(harness.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 12; tick++) {
                state = task.tick(harness.player);
                if (state == TaskState.FAILED || state == TaskState.SUCCESS) break;
                harness.nextTick();
            }
            check(state == TaskState.FAILED, "来源许可内无法完成时如实失败");
            Field issuesField = SemanticAcquireCompanionTask.class.getDeclaredField("issues");
            issuesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> issues = (List<Map<String, Object>>) issuesField.get(task);
            boolean named = issues.stream().anyMatch(issue ->
                    "craft".equals(issue.get("source"))
                            && "recipes_with_unreadable_inputs".equals(issue.get("code")));
            check(named, "craft 家族评估必须留下'配方存在但输入不可读'的证据");
            String summary = String.valueOf(task.result(TaskState.FAILED).message());
            check(summary.contains("craft="), "per-family 终局话术逐族点名评估结果");
        }
    }

    private static RecipeHolder<ShapedRecipe> furnaceRecipe() {
        var key = new java.util.LinkedHashMap<Character, Ingredient>();
        key.put('C', Ingredient.of(UNREADABLE_INPUT));
        return new RecipeHolder<>(ResourceLocation.withDefaultNamespace("furnace"),
                new ShapedRecipe("", CraftingBookCategory.MISC,
                        ShapedRecipePattern.of(key, List.of("CCC", "C C", "CCC")),
                        new ItemStack(Blocks.FURNACE), true));
    }

    private static void install(InteractionWorldTestHarness harness, List<RecipeHolder<?>> recipes) throws Exception {
        var manager = new RecipeManager(RegistryAccess.EMPTY);
        manager.replaceRecipes((Iterable<RecipeHolder<?>>) (Iterable<?>) recipes);
        Field field = ClientPacketListener.class.getDeclaredField("recipeManager");
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
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
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
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 171 前提验证：知识层 fallback 配方证据在位时，合成规划的四态受控复现。
 * 全料+台面 / C 原始背包（前置齐备）/ 链式展开各自应把合成派发到正确配方；
 * 缺料态应如实失败并把缺口点名到具体材料，不得只留一句笼统 no_finite_recipe_path。
 * 断言停在规划与编排边界（派发哪条配方、链怎么展开）；原生菜单合成由 craftingRegression 覆盖。
 */
public final class CraftFallbackConsumptionTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        fullMaterialsWithTableDispatchesPickaxe();
        carriedPrerequisitesChainToPickaxe();
        chainExpandsPlankToStick();
        missingMaterialsAreNamedNotBareVerdict();
        System.out.println("CraftFallbackConsumptionTest: passed");
    }

    /** 例一：圆石+木棍齐备、工作台在位——规划器应消费证据把石镐配方直接派发执行。 */
    private static void fullMaterialsWithTableDispatchesPickaxe() throws Exception {
        try (var world = harnessWithCraftingRecipes()) {
            world.set(new BlockPos(9, 1, 8), Blocks.CRAFTING_TABLE.defaultBlockState());
            world.inventory.clearContent();
            world.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 241));
            world.inventory.setItem(1, new ItemStack(Items.STICK, 3));
            Driver driver = new Driver(world, item("stone_pickaxe"), 1);
            String recipe = driver.firstCraftDispatch(60);
            check("minecraft:stone_pickaxe".equals(recipe),
                    "全料+台面在位应直接派发石镐配方，实际派发=" + recipe + " " + driver);
            driver.cancel();
        }
    }

    /** 例三：C 原始背包（圆石 241+木棍 3）外加 4 木板补台——应经 工作台→石镐 链式派发。 */
    private static void carriedPrerequisitesChainToPickaxe() throws Exception {
        try (var world = harnessWithCraftingRecipes()) {
            world.inventory.clearContent();
            world.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 241));
            world.inventory.setItem(1, new ItemStack(Items.STICK, 3));
            world.inventory.setItem(2, new ItemStack(Items.OAK_PLANKS, 4));
            Driver driver = new Driver(world, item("stone_pickaxe"), 1);
            String first = driver.firstCraftDispatch(60);
            check("minecraft:crafting_table".equals(first),
                    "缺台但有木板应先派发工作台合成，实际派发=" + first + " " + driver);
            // 工作台到包后，上层应回到石镐配方继续派发。
            world.inventory.setItem(2, new ItemStack(Items.CRAFTING_TABLE, 1));
            String second = driver.nextCraftDispatch(60);
            check("minecraft:stone_pickaxe".equals(second),
                    "工作台到包后应派发石镐配方，实际派发=" + second + " " + driver);
            driver.cancel();
        }
    }

    /** 例四：C 木棍场景（持 3 要 5、只有 1 桦木板）外加原木——应经 原木→木板→木棍 链式派发并到账完成。 */
    private static void chainExpandsPlankToStick() throws Exception {
        try (var world = harnessWithCraftingRecipes()) {
            world.inventory.clearContent();
            world.inventory.setItem(0, new ItemStack(Items.BIRCH_PLANKS, 1));
            world.inventory.setItem(1, new ItemStack(Items.BIRCH_LOG, 2));
            Driver driver = new Driver(world, item("stick"), 5);
            String first = driver.firstCraftDispatch(60);
            check(first != null && first.endsWith("_planks"),
                    "缺木板应先派发木板合成补链，实际派发=" + first + " " + driver);
            // 木板原生合成到包后，链应回到木棍配方；木棍到账后整链成功。
            world.inventory.setItem(1, new ItemStack(Items.BIRCH_PLANKS, 16));
            String second = driver.nextCraftDispatch(60);
            check("minecraft:sticks".equals(second),
                    "木板补齐后应派发木棍配方，实际派发=" + second + " " + driver);
            world.inventory.setItem(1, new ItemStack(Items.BIRCH_PLANKS, 12));
            world.inventory.setItem(2, new ItemStack(Items.STICK, 8));
            driver.runToTerminal(120);
            check(driver.state() == TaskState.SUCCESS,
                    "链上材料到账后应整链成功，实际终态=" + driver.state() + " " + driver);
            check(world.inventory.countItem(Items.STICK) >= 5, "木棍终态数量保持 >=5");
        }
    }

    /** 例二：只有圆石缺木棍——不得只报笼统 no_finite_recipe_path，缺口要点名到材料。 */
    private static void missingMaterialsAreNamedNotBareVerdict() throws Exception {
        try (var world = harnessWithCraftingRecipes()) {
            world.inventory.clearContent();
            world.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 241));
            Driver driver = new Driver(world, item("stone_pickaxe"), 1);
            driver.runToTerminal(120);
            check(driver.state() == TaskState.FAILED, "来源许可内无木料时应如实失败，实际=" + driver.state());
            boolean bare = driver.issues().stream().anyMatch(issue ->
                    "craft".equals(issue.get("source"))
                            && "no_finite_recipe_path".equals(issue.get("code")));
            check(bare, "复现场景应走到 no_finite_recipe_path 判定，issues=" + driver.issues());
            // 点名语义落在专用事实字段上：被否决配方携带缺失原料清单，而不是笼统的失败码。
            List<Map<String, Object>> missingByRecipe = driver.issues().stream()
                    .filter(issue -> "craft".equals(issue.get("source"))
                            && "no_finite_recipe_path".equals(issue.get("code")))
                    .map(issue -> issue.get("facts"))
                    .filter(facts -> facts instanceof Map<?, ?>)
                    .map(facts -> (Map<?, ?>) facts)
                    .map(facts -> facts.get("missing_materials_by_recipe"))
                    .filter(value -> value instanceof List<?>)
                    .map(value -> (List<Map<String, Object>>) (List<?>) value)
                    .findFirst().orElse(List.of());
            check(JSON.stringify(missingByRecipe).contains("minecraft:stick"),
                    "缺料判定应点名被否决配方与缺失材料（木棍），missing_materials_by_recipe="
                            + JSON.stringify(missingByRecipe));
        }
    }

    // —— 驱动：逐刻推进，观察合成派发序列；原生菜单执行由 craftingRegression 覆盖 ——

    private static final class JSON {
        static String stringify(Object value) {
            if (value == null) return "null";
            if (value instanceof Map<?, ?> map) {
                var parts = new ArrayList<String>();
                map.forEach((k, v) -> parts.add(k + "=" + stringify(v)));
                return "{" + String.join(", ", parts) + "}";
            }
            if (value instanceof Iterable<?> rows) {
                var parts = new ArrayList<String>();
                for (Object row : rows) parts.add(stringify(row));
                return "[" + String.join(", ", parts) + "]";
            }
            return String.valueOf(value);
        }
    }

    private static ResourceLocation item(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static final class Driver {
        private final InteractionWorldTestHarness world;
        private final SemanticAcquireCompanionTask task;
        private final List<String> dispatchedRecipes = new ArrayList<>();
        private TaskState state = TaskState.RUNNING;

        Driver(InteractionWorldTestHarness world, ResourceLocation target, int count) throws Exception {
            this.world = world;
            var record = new SemanticAcquireTaskRecord("craft-fallback", 1000000,
                    List.of(target), count,
                    List.of(SemanticAcquireTaskRecord.Source.INVENTORY,
                            SemanticAcquireTaskRecord.Source.CRAFT),
                    false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            this.task = new SemanticAcquireCompanionTask(world.player, record);
            task.start(world.player);
        }

        String firstCraftDispatch(int maxTicks) throws Exception {
            return nextCraftDispatch(maxTicks);
        }

        /** 逐刻推进，直到派发一个新的合成子任务或任务终态；返回新派发的配方名。 */
        String nextCraftDispatch(int maxTicks) throws Exception {
            int seen = dispatchedRecipes.size();
            for (int tick = 0; tick < maxTicks && state == TaskState.RUNNING; tick++) {
                state = task.tick(world.player);
                if (activeCraftRecipe() instanceof String recipe
                        && (dispatchedRecipes.isEmpty()
                                || !dispatchedRecipes.getLast().equals(recipe))) {
                    dispatchedRecipes.add(recipe);
                    if (dispatchedRecipes.size() > seen) return recipe;
                }
                world.nextTick();
            }
            return null;
        }

        void runToTerminal(int maxTicks) throws Exception {
            for (int tick = 0; tick < maxTicks && state == TaskState.RUNNING; tick++) {
                state = task.tick(world.player);
                world.nextTick();
            }
        }

        void cancel() {
            task.result(TaskState.CANCELLED);
        }

        TaskState state() {
            return state;
        }

        List<Map<String, Object>> issues() throws Exception {
            Field field = SemanticAcquireCompanionTask.class.getDeclaredField("issues");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> issues = (List<Map<String, Object>>) field.get(task);
            return issues;
        }

        /** 当前活动子任务是合成任务时返回其配方名。 */
        private String activeCraftRecipe() throws Exception {
            Field field = SemanticAcquireCompanionTask.class.getDeclaredField("activeRecord");
            field.setAccessible(true);
            if (field.get(task) instanceof CraftTaskRecord craft) {
                return craft.recipeId.toString();
            }
            return null;
        }

        @Override
        public String toString() {
            return "state=" + state + " dispatched=" + dispatchedRecipes;
        }
    }

    private static InteractionWorldTestHarness harnessWithCraftingRecipes() throws Exception {
        var world = new InteractionWorldTestHarness();
        world.position(new Vec3(8.5, 1, 8.5));
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                world.set(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
        install(world, List.of(
                stonePickaxeRecipe(),
                stickRecipe(),
                planksRecipe("birch_planks", Items.BIRCH_LOG, Items.BIRCH_PLANKS),
                planksRecipe("oak_planks", Items.OAK_LOG, Items.OAK_PLANKS),
                craftingTableRecipe()));
        return world;
    }

    private static RecipeHolder<ShapedRecipe> stonePickaxeRecipe() {
        var key = new java.util.LinkedHashMap<Character, Ingredient>();
        key.put('A', Ingredient.of(Items.COBBLESTONE));
        key.put('B', Ingredient.of(Items.STICK));
        return new RecipeHolder<>(item("stone_pickaxe"),
                new ShapedRecipe("", CraftingBookCategory.EQUIPMENT,
                        ShapedRecipePattern.of(key, List.of("AAA", " B ", " B ")),
                        new ItemStack(Items.STONE_PICKAXE), true));
    }

    private static RecipeHolder<ShapedRecipe> stickRecipe() {
        var key = new java.util.LinkedHashMap<Character, Ingredient>();
        key.put('P', Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS));
        return new RecipeHolder<>(item("sticks"),
                new ShapedRecipe("", CraftingBookCategory.MISC,
                        ShapedRecipePattern.of(key, List.of("P", "P")),
                        new ItemStack(Items.STICK, 4), true));
    }

    private static RecipeHolder<ShapedRecipe> planksRecipe(String path, net.minecraft.world.item.Item log, net.minecraft.world.item.Item planks) {
        var key = new java.util.LinkedHashMap<Character, Ingredient>();
        key.put('L', Ingredient.of(log));
        return new RecipeHolder<>(item(path),
                new ShapedRecipe("", CraftingBookCategory.BUILDING,
                        ShapedRecipePattern.of(key, List.of("L")),
                        new ItemStack(planks, 4), true));
    }

    private static RecipeHolder<ShapedRecipe> craftingTableRecipe() {
        var key = new java.util.LinkedHashMap<Character, Ingredient>();
        key.put('P', Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS));
        return new RecipeHolder<>(item("crafting_table"),
                new ShapedRecipe("", CraftingBookCategory.MISC,
                        ShapedRecipePattern.of(key, List.of("PP", "PP")),
                        new ItemStack(Items.CRAFTING_TABLE), true));
    }

    private static void install(InteractionWorldTestHarness world, List<RecipeHolder<?>> recipes) throws Exception {
        var manager = new RecipeManager(RegistryAccess.EMPTY);
        manager.replaceRecipes((Iterable<RecipeHolder<?>>) (Iterable<?>) recipes);
        Field field = ClientPacketListener.class.getDeclaredField("recipeManager");
        field.setAccessible(true);
        field.set(world.player.connection, manager);
        field = ClientLevel.class.getDeclaredField("connection");
        field.setAccessible(true);
        field.set(world.level, world.player.connection);
        field = Level.class.getDeclaredField("registryAccess");
        field.setAccessible(true);
        field.set(world.level, RegistryAccess.EMPTY);
        var inventoryMenu = new InventoryMenu(world.inventory, false, world.player);
        field = Player.class.getDeclaredField("inventoryMenu");
        field.setAccessible(true);
        field.set(world.player, inventoryMenu);
        world.player.containerMenu = inventoryMenu;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

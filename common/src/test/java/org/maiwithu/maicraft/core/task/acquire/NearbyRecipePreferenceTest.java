// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;
import org.maiwithu.maicraft.core.tools.CraftOps;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;

/** 用空背包、天然白桦和不存在的橡木验证现场排序、模型偏好、库存优先及偏好失败回退。 */
public final class NearbyRecipePreferenceTest {
    private static final ResourceLocation STICK = id("stick"), OAK = id("oak_log"), BIRCH = id("birch_log");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Map<TagKey<Block>, List<Holder<Block>>> original = new HashMap<>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> original.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var tags = new HashMap<>(original);
        tags.put(BlockTags.LOGS, List.of(Blocks.OAK_LOG.builtInRegistryHolder(), Blocks.BIRCH_LOG.builtInRegistryHolder()));
        tags.put(BlockTags.DIRT, List.of(Blocks.DIRT.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);
        try (var world = new InteractionWorldTestHarness()) {
            var manager = new RecipeManager(RegistryAccess.EMPTY);
            manager.replaceRecipes(List.of(recipe("oak_planks", Items.OAK_PLANKS, 4, Ingredient.of(Items.OAK_LOG)),
                    recipe("birch_planks", Items.BIRCH_PLANKS, 4, Ingredient.of(Items.BIRCH_LOG)),
                    recipe("stick", Items.STICK, 4, Ingredient.of(Items.OAK_PLANKS), Ingredient.of(Items.OAK_PLANKS)),
                    recipe("stick_from_bamboo_item", Items.STICK, 1, Ingredient.of(Items.BAMBOO), Ingredient.of(Items.BAMBOO))));
            var field = ClientPacketListener.class.getDeclaredField("recipeManager"); field.setAccessible(true);
            field.set(world.player.connection, manager);
            world.player.containerMenu = new InventoryMenu(world.inventory, false, world.player);
            BlockPos tree = new BlockPos(8, 1, 8);
            world.set(tree.below(), Blocks.DIRT.defaultBlockState());
            for (int y = 0; y < 5; y++) world.set(tree.above(y), Blocks.BIRCH_LOG.defaultBlockState());
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                if (x != 0 || z != 0) world.set(tree.above(4).offset(x, 0, z),
                        Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
            var sources = List.of(SemanticAcquireTaskRecord.Source.INVENTORY,
                    SemanticAcquireTaskRecord.Source.CRAFT, SemanticAcquireTaskRecord.Source.MINE);
            var need = new AcquisitionNeed(List.of(STICK), 4, 0, Set.of(STICK), Set.of(), Set.of(), sources);
            var candidate = new CraftRecoveryCandidate(STICK, "test:wooden_sticks",
                    List.of(new CraftRecoveryCandidate.IngredientDemand(List.of(id("oak_planks"), id("birch_planks")), 2, 2)),
                    new CraftPlanCost(2, CraftPlanCost.Surface.READY, 0, 2, "test:wooden_sticks"), List.of());
            var planner = new AcquisitionRecipePlanner(world.player, false, 16, List.of());
            // 一轮观察完成后，默认路线应使用实际存在的白桦，而不是先查名字靠前的橡木。
            boolean observed = false;
            for (int tick = 0; tick < 200 && !(observed = planner.observeNearbySources(need, 16)); tick++) world.nextTick();
            check(observed && planner.materialPlan(candidate, need).supplies().getFirst().alternatives().equals(List.of(BIRCH)),
                    "空背包应优先选择附近已确认野生树所支持的配方");
            planner.preferMaterials(List.of(OAK));
            check(planner.materialPlan(candidate, need).supplies().getFirst().alternatives().equals(List.of(OAK)),
                    "LLM 可以用材料软偏好覆盖缺料路线的默认现场排序");
            need.rejectedRecipeInputs.put(candidate.recipeId(), Set.of(OAK));
            check(planner.materialPlan(candidate, need).supplies().getFirst().alternatives().equals(List.of(BIRCH)),
                    "偏好木种获取失败后仍应回退到附近白桦");
            need.rejectedRecipeInputs.clear();
            planner.preferMaterials(List.of(BIRCH));
            world.inventory.setItem(0, new ItemStack(Items.OAK_LOG)); world.nextTick();
            check(planner.materialPlan(candidate, need).supplies().isEmpty(), "背包原木已够时不应再为偏好采一棵树");
            var inventoryOnly = new AcquisitionNeed(List.of(STICK), 4, 0, Set.of(STICK), Set.of(), Set.of(),
                    List.of(SemanticAcquireTaskRecord.Source.INVENTORY, SemanticAcquireTaskRecord.Source.CRAFT));
            var restricted = new AcquisitionRecipePlanner(world.player, false, 16, List.of());
            check(restricted.observeNearbySources(inventoryOnly, 16)
                    && Boolean.FALSE.equals(restricted.nearbySourceEvidence().get("checked")), "只合成时不追加世界采集观察");
            validatePreferenceApiAndReadyRecipes(world);
            check(world.blockUses() == 0 && world.itemUses() == 0, "只读配方比较不得提前采集或制作");
        } finally { BuiltInRegistries.BLOCK.bindTags(original); }
        System.out.println("NearbyRecipePreferenceTest: passed");
    }

    private static void validatePreferenceApiAndReadyRecipes(InteractionWorldTestHarness world) {
        // 参数进入真实任务单，并在两条都能立即制作的配方之间发挥作用。
        var args = JsonParser.parseString("""
                {"item_id":"minecraft:stick","preferred_materials":["minecraft:oak_planks"],"allowed_sources":["craft"]}
                """).getAsJsonObject();
        var record = SemanticAcquireApi.newRecord(new ToolContext("preference-api", 0), args, world.player);
        check(record.preferredMaterials.equals(List.of(id("oak_planks")))
                && !record.allowedSources.contains(SemanticAcquireTaskRecord.Source.MINE), "材料倾向不能扩展来源权限");
        world.inventory.clearContent();
        world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        world.inventory.setItem(1, new ItemStack(Items.BAMBOO, 2));
        var plan = new CraftOps().plan(STICK.toString(), 1, world.player, new ToolContext("preferred-ready", 0),
                null, Set.of(), record.preferredMaterials);
        check(plan.executable() && plan.task().recipeId.equals(id("stick")), "同为现成可做时应采用偏好木板配方");
        args.addProperty("preferred_materials", "minecraft:oak_log");
        try { SemanticAcquireApi.validateArguments(args); throw new AssertionError("错误偏好类型未被拒绝"); }
        catch (IllegalArgumentException expected) { }
    }

    private static RecipeHolder<?> recipe(String name, Item output, int count, Ingredient... inputs) {
        return new RecipeHolder<>(id(name), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(output, count), NonNullList.of(Ingredient.EMPTY, inputs)));
    }
    private static ResourceLocation id(String value) { return ResourceLocation.withDefaultNamespace(value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.acquire.ProductionLineage;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.task.TaskState;

/** 平滑石的真实父子调度可以先烧石头；同一套来源、燃料限制和祖先在跨任务后仍然生效。 */
public final class CookingPrerequisiteChainTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        chain(); observedStock(); blocked(new ProductionLineage(Set.of(id("stone")), 1));
        blocked(new ProductionLineage(Set.of(), ProductionLineage.MAX_DEPTH));
        System.out.println("CookingPrerequisiteChainTest: passed");
    }
    private static void chain() throws Exception {
        try (var w = new CookingTestWorld()) {
            w.recipes(List.of(recipe("stone", Items.COBBLESTONE, Items.STONE), recipe("smooth_stone", Items.STONE, Items.SMOOTH_STONE)));
            w.game.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 12)); w.game.inventory.setItem(1, new ItemStack(Items.COAL, 4));
            var root = new SemanticCookCompanionTask(w.game.player, request("smooth_stone", List.of(Source.INVENTORY, Source.COOK)));
            root.start(w.game.player);
            // 烧平滑石 → 取石头 → 先烧石头；停在第二个炉子任务创建时，不用模拟产物掩盖未执行的炉次。
            SemanticCookTaskRecord inner = null;
            for (int tick = 0; tick < 10 && inner == null; tick++) {
                check(root.tick(w.game.player) == TaskState.RUNNING, "finite prerequisite preparation keeps the original goal");
                if (CookingTestWorld.read(root, "activeChild") instanceof SemanticAcquireCompanionTask acquire
                        && CookingTestWorld.read(acquire, "activeRecord") instanceof SemanticCookTaskRecord cook) inner = cook;
                w.game.nextTick();
            }
            check(inner != null && inner.itemId.equals(id("stone")) && inner.count == 12, "stone must be cooked before smooth stone");
            check(inner.allowedFuelIds.equals(List.of(id("coal"))) && inner.allowedSources.equals(List.of(Source.INVENTORY, Source.COOK)),
                    "nested cooking neither widens sources nor loses fuel restrictions");
            check(inner.productionLineage.blocks(id("smooth_stone")) && inner.productionLineage.blocks(id("stone")), "ancestors survive both dispatch boundaries");
            check(w.game.blockUses() == 0 && w.game.itemUses() == 0, "planning cannot manufacture or load a furnace batch");
        }
    }
    private static void observedStock() throws Exception {
        try (var w = new CookingTestWorld()) {
            w.recipes(List.of(recipe("stone", Items.COBBLESTONE, Items.STONE), recipe("smooth_stone", Items.STONE, Items.SMOOTH_STONE)));
            var cacheField = StockEvidence.class.getDeclaredField("NETWORK_CACHE"); cacheField.setAccessible(true); Object cache = cacheField.get(null);
            var remember = cache.getClass().getDeclaredMethod("record", Object.class, Object.class, Map.class, StockEvidence.Snapshot.class); remember.setAccessible(true);
            remember.invoke(cache, w.game.player, w.game.level, Map.of(), new StockEvidence.Snapshot(StockEvidence.Source.AE2,
                    Map.of(id("cobblestone"), (long) Integer.MAX_VALUE, id("coal"), 4L), Set.of(), w.game.level.getGameTime()));
            // 网络已有圆石时，允许的两段烧炼在估价阶段就可达；只准背包时不能借用同一份网络观察。
            for (boolean network : List.of(true, false)) {
                var planner = new CookingRecipePlanner(w.game.player, request("smooth_stone", network
                        ? List.of(Source.WIRELESS, Source.COOK) : List.of(Source.INVENTORY, Source.COOK)));
                planner.observeNearbyBlocks(); var choice = planner.fuelChoice(planner.candidates().getFirst(), Items.COAL, 12);
                check((choice.preparationCost() < CookingRecipePlanner.UNAVAILABLE_COST) == network, "stock visibility must follow declared source permissions");
            }
            check(w.game.inventory.isEmpty(), "observed network stock is not a carried-item effect");
        }
    }
    private static void blocked(ProductionLineage lineage) throws Exception {
        try (var w = new CookingTestWorld()) {
            w.recipes(List.of(recipe("stone", Items.COBBLESTONE, Items.STONE)));
            var record = new SemanticAcquireTaskRecord("cycle", 1000, List.of(id("stone")), 1, List.of(Source.COOK), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16).withCookingContext(lineage, List.of(id("coal")));
            var task = new SemanticAcquireCompanionTask(w.game.player, record); task.start(w.game.player);
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 8 && state == TaskState.RUNNING; tick++) { state = task.tick(w.game.player); w.game.nextTick(); }
            check(state == TaskState.FAILED && CookingTestWorld.read(task, "activeRecord") == null,
                    "a production cycle or exhausted depth must stop without creating another cooker");
        }
    }
    private static SemanticCookTaskRecord request(String output, List<Source> sources) {
        return new SemanticCookTaskRecord("two-stage", 10000, id(output), 12, SemanticCookTaskRecord.Preference.AUTO,
                List.of(id("coal")), sources, false, List.of());
    }
    private static RecipeHolder<SmeltingRecipe> recipe(String output, Item input, Item result) {
        return new RecipeHolder<>(id(output), new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(input), new ItemStack(result), 0, 200));
    }
    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

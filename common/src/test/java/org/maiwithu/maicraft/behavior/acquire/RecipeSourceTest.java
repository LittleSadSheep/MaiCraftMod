// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 做出来的来源：配方从读配方的接缝来（真实配方在游戏接口层读），设施从世界记忆找；
 * 缺的原料与燃料按用途标签递归回引擎；报价带着备料清单，动手时按报价认的那条配方做。
 */
class RecipeSourceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final WorldPosition TABLE_AT = new WorldPosition(8, 64, -2, null);
    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();

    /** 替身：记下递归进来的内部需求，动作当场做完——只核对备料的用途与数量。 */
    private static final class CapturingNeeds implements ItemNeeds {
        final List<ItemRequest> asked = new ArrayList<>();

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "备" + request.wanted().describe();
                }
            };
        }
    }

    /** 替身：动手做的现场动作，永远接得上，一步做完。 */
    private static final class FakeRuns implements RecipeRuns {
        static final FakeRuns READY = new FakeRuns();

        @Override public Optional<Action> run(RecipeView recipe, WorldPosition station, int times) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "做一次";
                }
            });
        }
    }

    private WorldMemory memory() {
        return new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
    }

    private RecipeView plankRecipe() {
        return new RecipeView("minecraft:oak_planks", RecipeView.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:oak_planks"), 4,
                List.of(new RecipeView.IngredientStack(WantedItem.ofItem("minecraft:oak_log"), 1)));
    }

    private RecipeSource source(WorldMemory memory, ReadsRecipes recipes, CapturingNeeds needs) {
        return new RecipeSource(recipes, memory,
                itemId -> switch (itemId) { case "minecraft:coal" -> 1600; case "minecraft:coal_block" -> 16000; default -> 0; },
                FakeRuns.READY, backpack, offhand, tags, needs);
    }

    @Test
    void 游戏里没有配方_如实回答给不了() {
        SourceQuote quote = source(memory(), wanted -> List.of(), new CapturingNeeds())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:bedrock"), 1, "施工备料"), CONTEXT);
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
    }

    @Test
    void 有配方但没记得工作台_如实说先用过一次才会记得() {
        SourceQuote quote = source(memory(), wanted -> List.of(plankRecipe()), new CapturingNeeds())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "施工备料"), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("工作台"));
    }

    @Test
    void 记得工作台_报价带备料清单() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:crafting_table", NOW);
        SourceQuote quote = source(memory, wanted -> List.of(plankRecipe()), new CapturingNeeds())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "施工备料"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertEquals("minecraft:oak_planks", offer.hint());
        assertTrue(offer.risk().contains("minecraft:oak_log"));
        assertTrue(offer.risk().contains("×1"), "做一次要一根木头");
    }

    @Test
    void 动手时缺的原料按用途标签递归回引擎() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:crafting_table", NOW);
        CapturingNeeds needs = new CapturingNeeds();
        RecipeSource recipeSource = source(memory, wanted -> List.of(plankRecipe()), needs);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                recipeSource.quote(new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 8, "施工备料"),
                        CONTEXT));
        recipeSource.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 8, "施工备料"),
                offer, CONTEXT).orElseThrow();

        // 8 块板做两次，身上一根木头都没有：缺 2 根，用途写清是为做板子备的。
        assertEquals(1, needs.asked.size());
        ItemRequest ingredientNeed = needs.asked.getFirst();
        assertEquals("minecraft:oak_log", ingredientNeed.wanted().specifier());
        assertEquals(2, ingredientNeed.count());
        assertTrue(ingredientNeed.purpose().contains("做minecraft:oak_planks"));
    }

    @Test
    void 烧炼身上没燃料_先去弄煤_用途写明是燃料() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:furnace", NOW);
        RecipeView smelting = new RecipeView("minecraft:iron_ingot", RecipeView.Kind.SMELTING,
                WantedItem.ofItem("minecraft:iron_ingot"), 1,
                List.of(new RecipeView.IngredientStack(WantedItem.ofItem("minecraft:raw_iron"), 1)));
        CapturingNeeds needs = new CapturingNeeds();
        RecipeSource recipeSource = source(memory, wanted -> List.of(smelting), needs);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                recipeSource.quote(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"),
                        CONTEXT));
        assertTrue(offer.risk().contains("燃料"));
        recipeSource.begin(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"),
                offer, CONTEXT).orElseThrow();

        // 8 次烧炼备 1 块煤的整数倍：这里 1 次，规划按一块煤 1600 刻取整。
        ItemRequest fuelNeed = needs.asked.stream()
                .filter(asked -> asked.purpose().contains("燃料"))
                .findFirst().orElseThrow();
        assertEquals("minecraft:coal", fuelNeed.wanted().specifier());
        assertEquals(1, fuelNeed.count());
    }

    @Test
    void 身上有烧得久的燃料_不再去弄() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:furnace", NOW);
        backpack.add("minecraft:coal_block", 2);
        RecipeView smelting = new RecipeView("minecraft:iron_ingot", RecipeView.Kind.SMELTING,
                WantedItem.ofItem("minecraft:iron_ingot"), 1,
                List.of(new RecipeView.IngredientStack(WantedItem.ofItem("minecraft:raw_iron"), 1)));
        CapturingNeeds needs = new CapturingNeeds();
        RecipeSource recipeSource = source(memory, wanted -> List.of(smelting), needs);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                recipeSource.quote(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"),
                        CONTEXT));
        recipeSource.begin(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"),
                offer, CONTEXT).orElseThrow();
        assertTrue(needs.asked.stream().noneMatch(asked -> asked.purpose().contains("燃料")));
    }

    @Test
    void 报价认的配方没了_交回空由引擎换路() {
        SourceQuote.Offer stale = new SourceQuote.Offer("自己做", 4,
                new AcquisitionCost(5, 6), null, "minecraft:gone_recipe");
        assertTrue(source(memory(), wanted -> List.of(), new CapturingNeeds())
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "施工备料"),
                        stale, CONTEXT).isEmpty());
    }
}

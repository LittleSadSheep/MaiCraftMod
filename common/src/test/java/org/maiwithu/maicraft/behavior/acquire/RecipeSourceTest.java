// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

        @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                Permissions permissions) {
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

    private WorkstationRecipe plankRecipe() {
        return new WorkstationRecipe("minecraft:oak_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:oak_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:oak_log"), 1)));
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

    /** 替身：会在背包合成格里做的现场动作，记下有没有走背包这条路。 */
    private static final class InventoryRuns implements RecipeRuns {
        int inInventory;
        int atStation;

        @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                Permissions permissions) {
            atStation++;
            return Optional.of(done("在工作台上做"));
        }

        @Override public boolean craftsInInventory() {
            return true;
        }

        @Override public Optional<Action> runInInventory(WorkstationRecipe recipe, int times) {
            inInventory++;
            return Optional.of(done("在背包里做"));
        }

        private static Action done(String what) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return what;
                }
            };
        }
    }

    private WorkstationRecipe smallPlankRecipe() {
        return new WorkstationRecipe("minecraft:oak_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:oak_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:oak_log"), 1)), true);
    }

    @Test
    void 摆得进两格乘两格的合成_没有工作台也能报价_在背包合成格里做() {
        // 新世界开局：附近没有工作台、身上也没有，做木板不能卡在"先要一张工作台"上。
        InventoryRuns runs = new InventoryRuns();
        CapturingNeeds needs = new CapturingNeeds();
        RecipeSource recipeSource = new RecipeSource(recipes -> List.of(smallPlankRecipe()), memory(),
                itemId -> 0, runs, backpack, offhand, tags, needs);
        ItemRequest planks = new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "做工作台");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(planks, CONTEXT));
        assertEquals(0, offer.cost().distanceBlocks(), "在背包里做，不用走去哪");
        assertTrue(offer.risk().contains("背包"), offer.risk());

        recipeSource.begin(planks, offer, CONTEXT).orElseThrow();
        assertEquals(1, runs.inInventory, "走背包合成格这条路");
        assertEquals(0, runs.atStation);
        assertEquals(1, needs.asked.size(), "原料照样回引擎去弄");
        assertTrue(needs.asked.stream().noneMatch(ask -> ask.wanted().specifier().equals("minecraft:crafting_table")),
                "不去弄工作台");
    }

    @Test
    void 按标签要东西_挑身上原料缺得最少的那条配方() {
        // 要"任意木板"、背包里有云杉原木：做云杉木板，不去找竹子做竹板。
        backpack.add("minecraft:spruce_log", 3);
        WorkstationRecipe bamboo = new WorkstationRecipe("minecraft:bamboo_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:bamboo_planks"), 2,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:bamboo_block"), 1)), true);
        WorkstationRecipe spruce = new WorkstationRecipe("minecraft:spruce_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:spruce_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:spruce_log"), 1)), true);
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(bamboo, spruce), memory(),
                itemId -> 0, new InventoryRuns(), backpack, offhand, tags, new CapturingNeeds());
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(
                new ItemRequest(WantedItem.ofTag("minecraft:planks"), 4, "做工作台"), CONTEXT));
        assertEquals("minecraft:spruce_planks", offer.hint());
    }

    @Test
    void 记得的工作台在几十格外_能就地摆就不跑过去() {
        // 做石镐要工作台：记得的那张在 40 格外的崖底，就地摆一张（四块木板）比走回去省事，也不用一路搭路。
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(new WorldPosition(40, 64, 0, null), "minecraft:crafting_table", NOW);
        WorkstationRecipe pickaxe = new WorkstationRecipe("minecraft:stone_pickaxe", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:stone_pickaxe"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:stone_tool_materials"), 3),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:stick"), 2)));
        RecipeSource recipeSource = new RecipeSource(Set.of(WorkstationRecipe.Kind.CRAFTING), wanted -> List.of(pickaxe),
                memory, itemId -> 0, FakeRuns.READY, backpack, offhand, tags, new CapturingNeeds(),
                blockType -> Optional.empty(), null);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(
                new ItemRequest(WantedItem.ofItem("minecraft:stone_pickaxe"), 1, "开局"), CONTEXT));
        assertTrue(offer.risk().contains("放一个"), offer.risk());
        assertEquals(0, offer.cost().distanceBlocks(), "就地摆，不走去 40 格外");
    }

    @Test
    void 要任意床_不挑拿床换颜色的配方() {
        // 实机：夜里备床挑了"白床加绿染料做绿床"，缺的件数看着最少，可做它先得有一张床，还去烧仙人掌弄染料。
        tags.put("minecraft:white_bed", "minecraft:beds");
        tags.put("minecraft:green_bed", "minecraft:beds");
        WorkstationRecipe dyed = new WorkstationRecipe("minecraft:dye_green_bed", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:green_bed"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:white_bed"), 1),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:green_dye"), 1)), true);
        WorkstationRecipe white = new WorkstationRecipe("minecraft:white_bed", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:white_bed"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:white_wool"), 3),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:planks"), 3)));
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(dyed, white), memory(),
                itemId -> 0, new InventoryRuns(), backpack, offhand, tags, new CapturingNeeds());
        SourceQuote quote = recipeSource.quote(new ItemRequest(WantedItem.ofTag("minecraft:beds"), 1, "睡觉"), CONTEXT);
        // 白床要工作台、附近又没记得的：报价可以是"做不了"，但绝不能是绿床那条。
        if (quote instanceof SourceQuote.Offer offer) {
            assertEquals("minecraft:white_bed", offer.hint());
        } else {
            SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
            assertTrue(unavailable.reason().contains("minecraft:white_bed"), unavailable.reason());
        }
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
        WorkstationRecipe smelting = new WorkstationRecipe("minecraft:iron_ingot", WorkstationRecipe.Kind.SMELTING,
                WantedItem.ofItem("minecraft:iron_ingot"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:raw_iron"), 1)));
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
    void 身上的木板加起来够烧_不去弄煤() {
        // 烤 4 块牛肉要 800 刻，一块木板只烧 300 刻，十块加起来够：不能因为最耐烧的一件不够就去找煤。
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:furnace", NOW);
        backpack.add("minecraft:oak_planks", 10);
        WorkstationRecipe cooking = new WorkstationRecipe("minecraft:cooked_beef", WorkstationRecipe.Kind.SMELTING,
                WantedItem.ofItem("minecraft:cooked_beef"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:beef"), 1)));
        CapturingNeeds needs = new CapturingNeeds();
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(cooking), memory,
                itemId -> itemId.equals("minecraft:oak_planks") ? 300 : 0,
                FakeRuns.READY, backpack, offhand, tags, needs);
        ItemRequest beef = new ItemRequest(WantedItem.ofItem("minecraft:cooked_beef"), 4, "吃");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(beef, CONTEXT));
        recipeSource.begin(beef, offer, CONTEXT).orElseThrow();
        assertTrue(needs.asked.stream().noneMatch(asked -> asked.purpose().contains("燃料")), needs.asked.toString());
    }

    @Test
    void 身上有烧得久的燃料_不再去弄() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:furnace", NOW);
        backpack.add("minecraft:coal_block", 2);
        WorkstationRecipe smelting = new WorkstationRecipe("minecraft:iron_ingot", WorkstationRecipe.Kind.SMELTING,
                WantedItem.ofItem("minecraft:iron_ingot"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:raw_iron"), 1)));
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

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
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CollectedRecords;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
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
                Permissions permissions, TaskRecords records) {
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

    /** 替身：会在背包合成格里做的现场动作，记下有没有走背包这条路、拿到了谁的记账口。 */
    private static final class InventoryRuns implements RecipeRuns {
        int inInventory;
        int atStation;
        TaskRecords recordsSeen;

        @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                Permissions permissions, TaskRecords records) {
            atStation++;
            recordsSeen = records;
            return Optional.of(done("在工作台上做"));
        }

        @Override public boolean craftsInInventory() {
            return true;
        }

        @Override public Optional<Action> runInInventory(WorkstationRecipe recipe, int times, TaskRecords records) {
            inInventory++;
            recordsSeen = records;
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
    void 动手时把发起任务的记账口交给现场动作() {
        // 自制了几件东西由现场动作逐笔记进发起任务的结果（要的那件引擎已替它滤掉），结果里的变化能和背包净变化对得上。
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:crafting_table", NOW);
        InventoryRuns runs = new InventoryRuns();
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(plankRecipe()), memory,
                itemId -> 0, runs, backpack, offhand, tags, new CapturingNeeds());
        CollectedRecords records = new CollectedRecords();
        SourceContext booked = new SourceContext(WorldPosition.here(0, 64, 0), Permissions.DEFAULT, null, null,
                records);
        ItemRequest planks = new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 8, "施工备料");
        backpack.add("minecraft:oak_log", 2);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(planks, CONTEXT));

        assertInstanceOf(ActionStatus.Done.class,
                runToEnd(recipeSource.begin(planks, offer, booked).orElseThrow()));
        assertEquals(records, runs.recordsSeen, "现场动作拿到的就是发起任务的那份记账口");
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
    void 原料弄不全_身上够做一份就先做_一份都不够才以备料原因失败() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:crafting_table", NOW);
        ItemNeeds failing = (request, permissions) -> new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, "附近没有橡木"));
            }
            @Override public String describe() {
                return "备" + request.wanted().describe();
            }
        };
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(plankRecipe()), memory, itemId -> 0,
                FakeRuns.READY, backpack, offhand, tags, failing);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 8, "施工备料");

        // 要 8 块板（两根木头），身上只有一根：备料弄不来，但一根够做一份，照样去做。
        backpack.add("minecraft:oak_log", 1);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));
        assertInstanceOf(ActionStatus.Done.class, runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));

        // 一根都没有：一份都做不了，失败原因写弄不来的那一样。
        FakeBackpack empty = new FakeBackpack(36);
        RecipeSource bare = new RecipeSource(wanted -> List.of(plankRecipe()), memory, itemId -> 0,
                FakeRuns.READY, empty, offhand, tags, failing);
        SourceQuote.Offer bareOffer = assertInstanceOf(SourceQuote.Offer.class, bare.quote(request, CONTEXT));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                runToEnd(bare.begin(request, bareOffer, CONTEXT).orElseThrow()));
        assertTrue(failed.problem().message().contains("附近没有橡木"), failed.problem().message());
    }

    @Test
    void 任意木板_头一种木头弄不来就换下一种() {
        WorldMemory memory = memory();
        WorkstationRecipe cherry = new WorkstationRecipe("minecraft:cherry_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:cherry_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:cherry_log"), 1)), true);
        WorkstationRecipe spruce = new WorkstationRecipe("minecraft:spruce_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:spruce_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:spruce_log"), 1)), true);
        List<String> asked = new ArrayList<>();
        // 樱桃原木弄不来；云杉原木弄得来（替身把一根云杉原木放进背包）。
        ItemNeeds needs = (request, permissions) -> new Action() {
            @Override public ActionStatus tick(TickContext context) {
                asked.add(request.wanted().specifier());
                if (request.wanted().specifier().equals("minecraft:cherry_log")) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, "附近没有樱桃原木"));
                }
                backpack.add("minecraft:spruce_log", 1);
                return ActionStatus.done();
            }
            @Override public String describe() {
                return "备" + request.wanted().describe();
            }
        };
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(cherry, spruce), memory, itemId -> 0,
                new MakesPlanks(backpack), backpack, offhand, tags, needs);
        ItemRequest request = new ItemRequest(WantedItem.ofTag("minecraft:planks"), 4, "做木斧");
        tags.put("minecraft:cherry_planks", "minecraft:planks");
        tags.put("minecraft:spruce_planks", "minecraft:planks");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        assertInstanceOf(ActionStatus.Done.class, runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));
        assertEquals(List.of("minecraft:cherry_log", "minecraft:spruce_log"), asked, "樱桃不成换云杉");
    }

    /** 替身：在背包里做木板——身上有哪种原木就扣一根、加四块那种木板，没有就说缺料。 */
    private record MakesPlanks(FakeBackpack backpack) implements RecipeRuns {
        @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                Permissions permissions, TaskRecords records) {
            return runInInventory(recipe, times, records);
        }

        @Override public boolean craftsInInventory() {
            return true;
        }

        @Override public Optional<Action> runInInventory(WorkstationRecipe recipe, int times, TaskRecords records) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    String log = recipe.ingredients().getFirst().item().itemId();
                    if (!backpack.take(log, 1)) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有" + log));
                    }
                    backpack.add(recipe.result().itemId(), 4);
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "做" + recipe.result().describe();
                }
            });
        }
    }

    private static ActionStatus runToEnd(Action action) {
        for (long t = 0; t < 100; t++) {
            ActionStatus status = action.tick(new StubTick(t));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("没收场");
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
    void 烧木炭没燃料_揣着原木就拿原木做木板烧() {
        // 实机（全量测试 10-12）：做火把缺木炭，身上 16 根原木，烧木炭时"身上没有能烧的燃料"，再去弄煤又转回木炭。
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:furnace", NOW);
        tags.put("minecraft:oak_log", "minecraft:logs", "minecraft:logs_that_burn");
        backpack.add("minecraft:oak_log", 16);
        WorkstationRecipe charcoal = new WorkstationRecipe("minecraft:charcoal", WorkstationRecipe.Kind.SMELTING,
                WantedItem.ofItem("minecraft:charcoal"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:logs_that_burn"), 1)));
        CapturingNeeds needs = new CapturingNeeds();
        RecipeSource recipeSource = source(memory, wanted -> List.of(charcoal), needs);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:charcoal"), 2, "火把");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));
        recipeSource.begin(request, offer, CONTEXT).orElseThrow();

        ItemRequest fuel = needs.asked.stream().filter(asked -> asked.purpose().contains("燃料")).findFirst().orElseThrow();
        assertTrue(fuel.wanted().isTag() && fuel.wanted().tagId().equals("minecraft:planks"), fuel.toString());
        assertEquals(2, fuel.count(), "烧两块木炭 400 刻，两块木板 600 刻够");
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

    /** 替身：摆工作台就是往世界记忆里记下它的位置（生产实现摆好并记，摆没记下等于白摆）。 */
    private static final class PlacesWorkstation implements SetsUpWorkstation {
        private final WorldMemory memory;
        private final boolean remember;
        int placed;
        /** 这一格记着的台子在世界里已经没了。 */
        WorldPosition goneAt;

        @Override public boolean gone(WorldPosition at, String blockType) {
            return at.equals(goneAt);
        }

        PlacesWorkstation(WorldMemory memory, boolean remember) {
            this.memory = memory;
            this.remember = remember;
        }

        @Override public Optional<Action> placeNearby(String blockType) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    placed++;
                    if (remember) {
                        memory.workstationSeen(new WorldPosition(3, 64, 3, null), blockType, NOW);
                    }
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "在身边放下工作台";
                }
            });
        }
    }

    /** 替身：和生产实现一个规矩，动手那一刻才从记忆里找台子；找得到就做一次，找不到接不上。 */
    private static final class MemoryBackedRuns implements RecipeRuns {
        private final WorldMemory memory;
        int crafted;

        MemoryBackedRuns(WorldMemory memory) {
            this.memory = memory;
        }

        @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                Permissions permissions, TaskRecords records) {
            return Optional.of(craftAction());
        }

        @Override public Optional<Action> runAtRememberedStation(WorkstationRecipe recipe, int times,
                Permissions permissions, TaskRecords records) {
            boolean remembered = memory.allRecords().stream()
                    .anyMatch(record -> record.kind() == MemoryKind.WORKSTATION
                            && MenuRecipeRuns.workstationBlockType(WorkstationRecipe.Kind.CRAFTING)
                                    .equals(record.blockType()));
            return remembered ? Optional.of(craftAction()) : Optional.empty();
        }

        private Action craftAction() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    crafted++;
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "在工作台上做";
                }
            };
        }
    }

    /** 木镐那条配方：三块木板两根木棍，摆不进背包合成格，得上工作台。 */
    private static WorkstationRecipe pickaxeRecipe() {
        return new WorkstationRecipe("minecraft:wooden_pickaxe", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:wooden_pickaxe"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:oak_planks"), 3),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:stick"), 2)));
    }

    @Test
    void 就地摆下的台子_摆完才认位置_照着做() {
        // 实机（208 现象一）：身上有台子、附近没有记过的台子，串动作还没跑就把"到新台上做"解析了，
        // 那时记忆里还没有这台台子，解析出来是空，于是只剩摆台、不做东西，结果还说做完了。
        WorldMemory memory = memory();
        MemoryBackedRuns runs = new MemoryBackedRuns(memory);
        PlacesWorkstation placer = new PlacesWorkstation(memory, true);
        RecipeSource recipeSource = new RecipeSource(Set.of(WorkstationRecipe.Kind.CRAFTING),
                wanted -> List.of(pickaxeRecipe()), memory, itemId -> 0, runs, backpack, offhand, tags,
                new CapturingNeeds(), placer, null);
        backpack.add("minecraft:oak_planks", 3);
        backpack.add("minecraft:stick", 2);
        backpack.add("minecraft:crafting_table", 1);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:wooden_pickaxe"), 1, "开局");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        assertInstanceOf(ActionStatus.Done.class, runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));
        assertEquals(1, placer.placed, "摆了一次台");
        assertEquals(1, runs.crafted, "摆完就在新台上做了，不能只摆台不做东西");
    }

    @Test
    void 记得的台子已经没了_划掉记忆_放下身上那张照做() {
        // 实机（全量测试 10-12）：挖楼梯把刚用过的工作台挖走了，记忆里还记着它，石镐走过去点空地整件失败，
        // 身上明明揣着那张工作台。
        WorldMemory memory = memory();
        WorldPosition old = new WorldPosition(1, 64, 1, null);
        memory.rememberWorkstationUsed(old, "minecraft:crafting_table", NOW);
        MemoryBackedRuns runs = new MemoryBackedRuns(memory);
        PlacesWorkstation placer = new PlacesWorkstation(memory, true);
        placer.goneAt = old;
        RecipeSource recipeSource = new RecipeSource(Set.of(WorkstationRecipe.Kind.CRAFTING),
                wanted -> List.of(pickaxeRecipe()), memory, itemId -> 0, runs, backpack, offhand, tags,
                new CapturingNeeds(), placer, null);
        backpack.add("minecraft:oak_planks", 3);
        backpack.add("minecraft:stick", 2);
        backpack.add("minecraft:crafting_table", 1);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:wooden_pickaxe"), 1, "开局");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        assertInstanceOf(ActionStatus.Done.class, runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));
        assertTrue(memory.recordAt(MemoryKind.WORKSTATION, old).isEmpty(), "没了的台子从记忆里划掉");
        assertEquals(1, placer.placed, "放下身上那张");
        assertEquals(1, runs.crafted, "在新台上做成");
    }

    @Test
    void 摆下的台子没记下位置_动手如实失败_不冒充做成了() {
        WorldMemory memory = memory();
        MemoryBackedRuns runs = new MemoryBackedRuns(memory);
        RecipeSource recipeSource = new RecipeSource(Set.of(WorkstationRecipe.Kind.CRAFTING),
                wanted -> List.of(pickaxeRecipe()), memory, itemId -> 0, runs, backpack, offhand, tags,
                new CapturingNeeds(), new PlacesWorkstation(memory, false), null);
        backpack.add("minecraft:oak_planks", 3);
        backpack.add("minecraft:stick", 2);
        backpack.add("minecraft:crafting_table", 1);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:wooden_pickaxe"), 1, "开局");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));
        assertTrue(failed.problem().message().contains("位置"), failed.problem().message());
        assertEquals(0, runs.crafted, "没认出位置就不动手");
    }

    /** 替身：备原料的动作照实改背包——做木板扣一根原木加四块板，做木棍扣两块板加四根棍；记下每回要了什么。 */
    private final class MakesPlanksAndSticks implements ItemNeeds {
        final List<String> asked = new ArrayList<>();

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request.wanted().specifier() + "×" + request.count());
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    String wanted = request.wanted().specifier();
                    if (wanted.equals("minecraft:oak_planks")) {
                        backpack.take("minecraft:oak_log", 1);
                        backpack.add("minecraft:oak_planks", 4);
                    } else if (wanted.equals("minecraft:stick")) {
                        backpack.take("minecraft:oak_planks", 2);
                        backpack.add("minecraft:stick", 4);
                    }
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "备" + request.wanted().describe();
                }
            };
        }
    }

    @Test
    void 先做的木棍吃掉了镐头要的木板_差一点回手再补_不说原料不够() {
        // 实机（208 现象二前半）：空手做木镐，先做 4 块木板，又被木棍用掉 2 块，剩 2 块不够 3 块，
        // 手上还有原木却报"身上的原料不够做一份"。关口现在把缺的再补一轮。
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(TABLE_AT, "minecraft:crafting_table", NOW);
        MakesPlanksAndSticks needs = new MakesPlanksAndSticks();
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(pickaxeRecipe()), memory,
                itemId -> 0, FakeRuns.READY, backpack, offhand, tags, needs);
        backpack.add("minecraft:oak_log", 3);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:wooden_pickaxe"), 1, "开局");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        assertInstanceOf(ActionStatus.Done.class, runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));
        assertEquals(List.of("minecraft:oak_planks×3", "minecraft:stick×2", "minecraft:oak_planks×1"),
                needs.asked, "木棍用掉一块板后，缺的一块板回手补上");
        assertEquals(1, countOf("minecraft:oak_log"), "补板用的还是手上的原木，不出门找替代");
    }

    @Test
    void 缺得一样多的做法_缺的原料近的先试() {
        // 实机（208 现象二后半）：补一块木板，1 格外就有丛林树、11 格外才有竹子，
        // 两种做法缺料一样多时按原料远近排先后，不去十格外挖替代品。
        tags.put("minecraft:jungle_planks", "minecraft:planks");
        tags.put("minecraft:bamboo_planks", "minecraft:planks");
        WorkstationRecipe bamboo = new WorkstationRecipe("minecraft:bamboo_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:bamboo_planks"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:bamboo"), 2)), true);
        WorkstationRecipe jungle = new WorkstationRecipe("minecraft:jungle_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:jungle_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:jungle_log"), 1)), true);
        List<String> asked = new ArrayList<>();
        // 问价：竹子 11 格，丛林原木 1 格；动手时竹子那一做法当场失败，丛林原木给一根。
        ItemNeeds needs = new ItemNeeds() {
            @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
                return Optional.of(request.wanted().specifier().equals("minecraft:bamboo") ? 11d : 1d);
            }

            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request.wanted().specifier());
                boolean bambooRun = request.wanted().specifier().equals("minecraft:bamboo");
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        if (bambooRun) {
                            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, "竹子够不着"));
                        }
                        backpack.add("minecraft:jungle_log", 1);
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "备" + request.wanted().describe();
                    }
                };
            }
        };
        // 竹板排在前面（模拟登记顺序），近的丛林树要排到它前面去。
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(bamboo, jungle), memory(),
                itemId -> 0, new InventoryRuns(), backpack, offhand, tags, needs);
        ItemRequest request = new ItemRequest(WantedItem.ofTag("minecraft:planks"), 1, "补镐头的料");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        assertInstanceOf(ActionStatus.Done.class, runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));
        assertEquals(List.of("minecraft:jungle_log"), asked, "先试原料近的做法，没去碰十格外的竹子");
    }

    @Test
    void 做法多过十二种_先按远近排再取() {
        // 实机：装了加木头的模组，木板几十种，附近只有云杉；原先先截前十二种再排远近，云杉排不进去，
        // 木板说"哪儿都拿不到"。现在全拿来排，近的云杉先试。
        List<WorkstationRecipe> recipes = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            String planks = "modwood:wood" + i + "_planks";
            tags.put(planks, "minecraft:planks");
            recipes.add(new WorkstationRecipe(planks, WorkstationRecipe.Kind.CRAFTING, WantedItem.ofItem(planks), 4,
                    List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("modwood:wood" + i + "_log"), 1)), true));
        }
        tags.put("minecraft:spruce_planks", "minecraft:planks");
        recipes.add(new WorkstationRecipe("minecraft:spruce_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:spruce_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:spruce_log"), 1)), true));
        List<String> asked = new ArrayList<>();
        ItemNeeds needs = new ItemNeeds() {
            @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
                return request.wanted().specifier().equals("minecraft:spruce_log") ? Optional.of(3d) : Optional.empty();
            }

            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request.wanted().specifier());
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        backpack.add(request.wanted().specifier(), 1);
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "备" + request.wanted().describe();
                    }
                };
            }
        };
        RecipeSource recipeSource = new RecipeSource(wanted -> recipes, memory(),
                itemId -> 0, new InventoryRuns(), backpack, offhand, tags, needs);
        ItemRequest request = new ItemRequest(WantedItem.ofTag("minecraft:planks"), 1, "做箱子");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        assertInstanceOf(ActionStatus.Done.class, runToEnd(recipeSource.begin(request, offer, CONTEXT).orElseThrow()));
        assertEquals("minecraft:spruce_log", asked.getFirst(), "先试附近有的云杉：" + asked);
    }

    private int countOf(String itemId) {
        return backpack.stacks().stream()
                .filter(stack -> stack.itemId().equals(itemId))
                .mapToInt(BackpackStack::count)
                .sum();
    }
}

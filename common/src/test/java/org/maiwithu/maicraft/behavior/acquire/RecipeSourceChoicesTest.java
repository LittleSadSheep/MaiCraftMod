// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 一格收好几样的原料（给床换颜色要"别的颜色的任一张床"）：身上有哪样用哪样；都没有就挑最省的——
 * 手头的料够现做的连现做一起比，别的只比现成的来路；都问不出代价挑现做缺料最少的。不再只认排第一的黑床。
 */
class RecipeSourceChoicesTest {

    private static final SourceContext CONTEXT = new SourceContext(WorldPosition.here(20, 63, 26), Permissions.DEFAULT);
    private static final List<String> OTHER_BEDS = List.of("minecraft:black_bed", "minecraft:white_bed", "minecraft:red_bed");

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();
    /** 引擎替身：记下回来要了什么、问最省代价时问了什么；要到的东西当场放进背包。 */
    private final List<ItemRequest> asked = new ArrayList<>();
    private final Map<String, Double> readyMadeCost = new HashMap<>();
    private final Map<String, Double> makingCost = new HashMap<>();
    private final List<String> costAsked = new ArrayList<>();
    private final List<String> made = new ArrayList<>();

    /** 给床换成品红：一份品红染料加别的颜色的任一张床，摆得进背包合成格。 */
    private static final WorkstationRecipe DYE_MAGENTA_BED = new WorkstationRecipe("minecraft:dye_magenta_bed",
            WorkstationRecipe.Kind.CRAFTING, WantedItem.ofItem("minecraft:magenta_bed"), 1,
            List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:magenta_dye"), 1),
                    new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:black_bed"), 1, OTHER_BEDS)),
            true);

    /** 三块同色羊毛加三块木板做一张床，要工作台。 */
    private static WorkstationRecipe bed(String color) {
        return new WorkstationRecipe("minecraft:" + color + "_bed", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:" + color + "_bed"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:" + color + "_wool"), 3),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:planks"), 3)));
    }

    private RecipeSource source() {
        tags.put("minecraft:spruce_planks", "minecraft:planks");
        List<WorkstationRecipe> recipes = List.of(DYE_MAGENTA_BED, bed("black"), bed("white"), bed("red"));
        ItemNeeds needs = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request);
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        backpack.add(request.wanted().itemId(), request.count());
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "弄" + request.wanted().describe();
                    }
                };
            }

            @Override public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions,
                    boolean includeMaking) {
                String item = request.wanted().itemId();
                costAsked.add(item + (includeMaking ? "（含现做）" : ""));
                Double ready = readyMadeCost.get(item);
                Double making = includeMaking ? makingCost.get(item) : null;
                if (ready == null) return Optional.ofNullable(making);
                return Optional.of(making == null ? ready : Math.min(ready, making));
            }
        };
        RecipeRuns runs = new RecipeRuns() {
            @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                    Permissions permissions, TaskRecords records) {
                return Optional.empty();
            }

            @Override public boolean craftsInInventory() {
                return true;
            }

            @Override public Optional<Action> runInInventory(WorkstationRecipe recipe, int times, TaskRecords records) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        made.add(recipe.id());
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "做" + recipe.id();
                    }
                });
            }
        };
        return new RecipeSource(wanted -> recipes.stream()
                .filter(recipe -> wanted.matches(recipe.result().itemId(), tags.tagsOf(recipe.result().itemId())))
                .toList(),
                new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1"),
                itemId -> 0, runs, backpack, offhand, tags, needs);
    }

    /** 要一张品红床：问价、动手、推到收场。 */
    private void makeMagentaBed() {
        RecipeSource source = source();
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:magenta_bed"), 1, "做一张床放进屋里");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));
        assertEquals("minecraft:dye_magenta_bed", offer.hint());
        Action action = source.begin(request, offer, CONTEXT).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int tick = 0; tick < 50 && status instanceof ActionStatus.Running; tick++) status = action.tick(null);
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(List.of("minecraft:dye_magenta_bed"), made);
    }

    @Test
    void 身上有白床_换颜色直接用它_不去弄排第一的黑床() {
        backpack.add("minecraft:magenta_dye", 4);
        backpack.add("minecraft:white_bed", 1);
        makeMagentaBed();
        assertTrue(asked.isEmpty(), "身上那张白床就够了：" + asked);
    }

    @Test
    void 身上没有床_挑现成来路最省的那样() {
        // 现场：自己野外那张白床能收回来（118），黑床、红床哪儿都没有现成的，也都缺羊毛做不了。
        backpack.add("minecraft:magenta_dye", 4);
        readyMadeCost.put("minecraft:white_bed", 118.0);
        makeMagentaBed();
        assertEquals("minecraft:white_bed", asked.getFirst().wanted().itemId());
        assertTrue(costAsked.stream().noneMatch(item -> item.endsWith("（含现做）")), "都缺羊毛，只比现成的：" + costAsked);
    }

    @Test
    void 手头的料够现做_现做和远处现成的一起比() {
        // 身上有三块红羊毛和三块木板，红床当场就能做（100）；野外那张白床在一百多格外（118）。
        backpack.add("minecraft:magenta_dye", 4);
        backpack.add("minecraft:red_wool", 3);
        backpack.add("minecraft:spruce_planks", 3);
        readyMadeCost.put("minecraft:white_bed", 118.0);
        makingCost.put("minecraft:red_bed", 100.0);
        makeMagentaBed();
        assertEquals("minecraft:red_bed", asked.getFirst().wanted().itemId());
        assertTrue(costAsked.contains("minecraft:red_bed（含现做）"), costAsked.toString());
    }

    @Test
    void 都问不出代价_挑现做缺料最少的() {
        // 身上有两块白羊毛和木板：白床只差一块羊毛，黑床、红床各差三块。
        backpack.add("minecraft:magenta_dye", 4);
        backpack.add("minecraft:white_wool", 2);
        backpack.add("minecraft:spruce_planks", 3);
        makeMagentaBed();
        assertEquals("minecraft:white_bed", asked.getFirst().wanted().itemId());
    }
}

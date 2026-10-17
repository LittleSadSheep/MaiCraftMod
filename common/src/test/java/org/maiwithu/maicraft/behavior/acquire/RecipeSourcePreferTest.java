// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.util.List;
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

/** LLM 说倾向用哪种材料：几种做法都行时用到它的排前面，备料时沿配方链往下传。 */
class RecipeSourcePreferTest {

    private static final SourceContext CONTEXT = new SourceContext(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();

    private static WorkstationRecipe planks(String wood) {
        return new WorkstationRecipe("minecraft:" + wood + "_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:" + wood + "_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:" + wood + "_log"), 1)), true);
    }

    private RecipeSource source() {
        ItemNeeds needs = (request, permissions) -> new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "替身";
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
        };
        tags.put("minecraft:oak_planks", "minecraft:planks");
        tags.put("minecraft:spruce_planks", "minecraft:planks");
        return new RecipeSource(wanted -> List.of(planks("oak"), planks("spruce")),
                new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1"),
                itemId -> 0, runs, backpack, offhand, tags, needs);
    }

    @Test
    void 点名倾向的材料排前面_哪怕身上有别的() {
        // 身上有橡木原木，本来先做橡木板；LLM 说倾向云杉，就做云杉木板。
        backpack.add("minecraft:oak_log", 2);
        ItemRequest request = new ItemRequest(WantedItem.ofTag("minecraft:planks"), 4, "做箱子", List.of("minecraft:spruce_log"));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source().quote(request, CONTEXT));
        assertEquals("minecraft:spruce_planks", offer.hint());
    }

    @Test
    void 外层点的倾向沿配方链传到备料() {
        backpack.add("minecraft:oak_log", 2);
        RecipeSource recipeSource = source();
        recipeSource.watchChain(() -> List.of(new ItemRequest(WantedItem.ofItem("minecraft:chest"), 1, "存东西",
                List.of("minecraft:spruce_log"))));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(
                new ItemRequest(WantedItem.ofTag("minecraft:planks"), 8, "做箱子的木板"), CONTEXT));
        assertEquals("minecraft:spruce_planks", offer.hint());
    }

    @Test
    void 没点倾向照旧先用身上有的() {
        backpack.add("minecraft:oak_log", 2);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source().quote(
                new ItemRequest(WantedItem.ofTag("minecraft:planks"), 4, "做箱子"), CONTEXT));
        assertEquals("minecraft:oak_planks", offer.hint());
    }
}

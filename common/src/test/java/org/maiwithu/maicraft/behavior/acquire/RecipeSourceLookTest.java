// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

/** 几种做法分不出先后（AE 这五分钟没开过，每种木头都报"看一眼"）：先打开看一眼，再按看到的挑。 */
class RecipeSourceLookTest {

    private static final SourceContext CONTEXT = new SourceContext(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();

    /** 替身：背包合成格里做，做一次就把成品放进背包。 */
    private final class MakesInInventory implements RecipeRuns {
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
                    backpack.add(recipe.result().itemId(), recipe.resultCount() * times);
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "做" + recipe.id();
                }
            });
        }
    }

    private static WorkstationRecipe planks(String wood) {
        return new WorkstationRecipe("minecraft:" + wood + "_planks", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:" + wood + "_planks"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:" + wood + "_log"), 1)), true);
    }

    @Test
    void 原料一样近时先看一眼AE_再拿AE里有的那种木头() {
        // 实机：AE 这五分钟没开过，橡木、云杉都报"看一眼"距离 0；按配方表顺序先试橡木，附近有橡树就去砍，
        // AE 里堆着的云杉原木不用。现在先打开看一眼：看完橡木在 AE 里没有（只剩 30 格外的橡树），云杉就在 AE 里。
        tags.put("minecraft:oak_planks", "minecraft:planks");
        tags.put("minecraft:spruce_planks", "minecraft:planks");
        Map<String, Double> distances = new HashMap<>(Map.of("minecraft:oak_log", 0d, "minecraft:spruce_log", 0d));
        List<String> asked = new ArrayList<>();
        List<String> looked = new ArrayList<>();
        ItemNeeds needs = new ItemNeeds() {
            @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
                return Optional.ofNullable(distances.get(request.wanted().specifier()));
            }

            @Override public Optional<Action> lookAround(ItemRequest request, Permissions permissions) {
                if (!looked.isEmpty()) return Optional.empty();
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        // 看完：AE 里只有云杉，橡木要去 30 格外砍。
                        looked.add(request.wanted().specifier());
                        distances.put("minecraft:oak_log", 30d);
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "看一眼 AE";
                    }
                });
            }

            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request.wanted().specifier());
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        backpack.add(request.wanted().specifier(), request.count());
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "备" + request.wanted().describe();
                    }
                };
            }
        };
        RecipeSource recipeSource = new RecipeSource(wanted -> List.of(planks("oak"), planks("spruce")),
                new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1"),
                itemId -> 0, new MakesInInventory(), backpack, offhand, tags, needs);
        ItemRequest request = new ItemRequest(WantedItem.ofTag("minecraft:planks"), 4, "做箱子");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, recipeSource.quote(request, CONTEXT));

        Action action = recipeSource.begin(request, offer, CONTEXT).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (long t = 0; t < 50 && !(status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed); t++) {
            status = action.tick(new StubTick(t));
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, looked.size(), "先看了一眼");
        assertEquals(List.of("minecraft:spruce_log"), asked, "看完拿 AE 里有的云杉，不去砍橡树");
    }
}

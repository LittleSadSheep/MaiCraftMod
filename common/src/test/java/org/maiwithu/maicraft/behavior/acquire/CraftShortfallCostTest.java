// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 现做的报价算上缺料：身上每缺一件原料加 32 格。做一张床缺三块羊毛时，野外一百来格外自己那张床先拆回来，
 * 不先去打羊；羊毛都在身上时现做照旧更省。
 */
class CraftShortfallCostTest {

    private static final SourceContext CONTEXT = new SourceContext(WorldPosition.here(20, 63, 26), Permissions.DEFAULT);
    private static final WantedItem WHITE_BED = WantedItem.ofItem("minecraft:white_bed");

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeTags tags = new FakeTags();
    /** 合成来源回来要了什么：选了收回就不该来要羊毛。 */
    private final List<ItemRequest> asked = new ArrayList<>();

    private RecipeSource crafting() {
        tags.put("minecraft:spruce_planks", "minecraft:planks");
        WorkstationRecipe bed = new WorkstationRecipe("minecraft:white_bed", WorkstationRecipe.Kind.CRAFTING, WHITE_BED, 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:white_wool"), 3),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:planks"), 3)));
        ItemNeeds needs = (request, permissions) -> {
            asked.add(request);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "附近没有羊"));
                }
                @Override public String describe() {
                    return "弄" + request.wanted().describe();
                }
            };
        };
        RecipeRuns runs = (recipe, station, times, permissions, records) -> Optional.empty();
        SetsUpWorkstation placer = blockType -> Optional.empty();
        return new RecipeSource(Set.of(WorkstationRecipe.Kind.CRAFTING),
                wanted -> wanted.matches(WHITE_BED.itemId(), Set.of()) ? List.of(bed) : List.of(),
                new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1"), itemId -> 0, runs,
                backpack, new FakeOffhand(), tags, needs, placer, null);
    }

    private AcquisitionCost craftCost() {
        SourceQuote quote = crafting().quote(new ItemRequest(WHITE_BED, 1, "睡觉"), CONTEXT);
        return assertInstanceOf(SourceQuote.Offer.class, quote).cost();
    }

    @Test
    void 缺三块羊毛_报价加三段路() {
        backpack.add("minecraft:spruce_planks", 3);
        AcquisitionCost cost = craftCost();
        assertEquals(3 * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM, cost.shortfallBlocks());
        assertEquals(0, cost.distanceBlocks(), "愿意走多远只比路程，缺料不算路程");
    }

    @Test
    void 料都在身上_不加价() {
        backpack.add("minecraft:white_wool", 3);
        backpack.add("minecraft:spruce_planks", 3);
        assertEquals(0, craftCost().shortfallBlocks());
    }

    @Test
    void 缺羊毛时_野外自己那张床先拆回来_不先去打羊() {
        backpack.add("minecraft:spruce_planks", 3);
        ScriptedSource reclaim = new ScriptedSource("收回自己放下的", AcquireVia.RECLAIM, backpack)
                .answer(new SourceQuote.Offer("收回自己放下的", 1, new AcquisitionCost(108, 1), null));
        ItemAcquisition engine = new ItemAcquisition(List.of(crafting(), reclaim), backpack, new FakeOffhand(), tags,
                new FixedSpot(20, 63, 26), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        Action action = engine.need(new ItemRequest(WHITE_BED, 1, "睡觉"), Permissions.DEFAULT);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 50 && status instanceof ActionStatus.Running; tick++) {
            status = action.tick(new StubTick(tick));
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, reclaim.begunTimes());
        assertTrue(asked.isEmpty(), "没先去弄羊毛：" + asked);
    }
}

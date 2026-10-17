// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 熔炉添燃料挑哪一堆、放几件，关着界面等多久回来看。 */
class FurnaceFuelPickTest {

    private static final int COAL = 1600;
    private static final int COAL_BLOCK = 16000;
    private static final int PLANKS = 300;
    private static final int STICK = 100;

    @Test
    void smallBatchDoesNotBurnACoalBlock() {
        // 烧两件（400 刻）：一块煤炭块能烧八十件，扔进去就浪费了；一块煤够烧完，挑煤。
        FurnaceRun.FuelPick pick = FurnaceRun.pickFuel(List.of(
                new FurnaceRun.FuelStack(0, 3, COAL_BLOCK),
                new FurnaceRun.FuelStack(1, 5, COAL)), 400);
        assertEquals(1, pick.index());
        assertEquals(1, pick.count());
    }

    @Test
    void enoughFuelForTheWholeBatchIsPlacedAtOnce() {
        // 烧十六件（3200 刻）：两块煤正好烧完，一次放两块。
        FurnaceRun.FuelPick pick = FurnaceRun.pickFuel(List.of(new FurnaceRun.FuelStack(4, 10, COAL)), 3200);
        assertEquals(4, pick.index());
        assertEquals(2, pick.count());
    }

    @Test
    void sticksOnlyWhenNothingBetterCoversTheBatch() {
        // 木板和木棍都够烧完时不拿零碎的木棍；只有木棍够时才用木棍。
        FurnaceRun.FuelPick withPlanks = FurnaceRun.pickFuel(List.of(
                new FurnaceRun.FuelStack(0, 20, STICK),
                new FurnaceRun.FuelStack(1, 10, PLANKS)), 600);
        assertEquals(1, withPlanks.index());
        assertEquals(2, withPlanks.count());
        FurnaceRun.FuelPick onlySticksCover = FurnaceRun.pickFuel(List.of(
                new FurnaceRun.FuelStack(0, 20, STICK),
                new FurnaceRun.FuelStack(1, 1, PLANKS)), 600);
        assertEquals(0, onlySticksCover.index());
        assertEquals(6, onlySticksCover.count());
    }

    @Test
    void fuelIsCappedAtOneStackAndMissingFuelIsNull() {
        FurnaceRun.FuelPick pick = FurnaceRun.pickFuel(List.of(new FurnaceRun.FuelStack(2, 64, PLANKS)), 200L * 300);
        assertEquals(64, pick.count());
        assertNull(FurnaceRun.pickFuel(List.of(), 200));
    }

    @Test
    void waitsUntilTheLoadedItemsShouldBeDoneWithinBounds() {
        // 投入口三件、手上这件烧了一半：两件半的时间再多等一秒回来；八件要七件半的时间，封顶一分钟回来收一次。
        assertEquals(520, FurnaceRun.waitTicks(3, 0.5f, 200));
        assertEquals(20 * 60, FurnaceRun.waitTicks(8, 0.5f, 200), "最多隔一分钟回来看一眼");
        assertEquals(220, FurnaceRun.waitTicks(1, 0f, 200));
        assertTrue(FurnaceRun.waitTicks(1, 1f, 200) >= 40, "刚烧完也至少等一会儿再回来");
    }
}

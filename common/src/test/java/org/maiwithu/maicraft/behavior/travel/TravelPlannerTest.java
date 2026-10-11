// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 行程规划的取舍：近处走、远处比报价、指定方式只用它、试过的不再试。 */
class TravelPlannerTest {

    private static final TravelVia ELEVATOR = new TravelVia("elevator", "坐电梯");
    private static final BlockPos HERE = new BlockPos(0, 64, 0);

    private static TravelOffer offer(TravelVia via, BlockPos to, long ticks) {
        return new TravelOffer(via, HERE, to, ticks, List.of(), List.of(), "");
    }

    @Test
    void 近处直接走_不问报价() {
        BlockPos to = new BlockPos(20, 66, 0);
        assertInstanceOf(TravelPlanner.Plan.Walk.class, TravelPlanner.choose(null, HERE, to, false, false,
                List.of(offer(ELEVATOR, to, 1)), List.of(), Set.of()));
    }

    @Test
    void 同一层有电梯能到目标层_坐电梯不爬楼() {
        BlockPos top = new BlockPos(5, 100, 5);
        TravelPlanner.Plan plan = TravelPlanner.choose(null, HERE, top, false, false,
                List.of(offer(ELEVATOR, top, 200)), List.of(), Set.of());
        assertInstanceOf(TravelPlanner.Plan.Ride.class, plan);
    }

    @Test
    void 远处报价比走路慢_照样走() {
        BlockPos far = new BlockPos(200, 64, 0);
        assertInstanceOf(TravelPlanner.Plan.Walk.class, TravelPlanner.choose(null, HERE, far, false, false,
                List.of(offer(ELEVATOR, far, 100_000)), List.of(), Set.of()));
    }

    @Test
    void 指定方式给不出报价_如实结束不改走路() {
        TravelPlanner.Plan plan = TravelPlanner.choose("elevator", HERE, new BlockPos(0, 100, 0), false, false,
                List.of(), List.of(new TravelPlanner.Declined(ELEVATOR, Problem.Kind.UNSUPPORTED, "附近没有通到那一层的电梯")), Set.of());
        TravelPlanner.Plan.DeadEnd dead = assertInstanceOf(TravelPlanner.Plan.DeadEnd.class, plan);
        assertEquals(Problem.Kind.UNSUPPORTED, dead.problem().kind());
    }

    @Test
    void 指定方式送过一段后剩下的走路() {
        assertInstanceOf(TravelPlanner.Plan.Walk.class, TravelPlanner.choose("elevator", HERE, new BlockPos(3, 64, 3),
                false, true, List.of(), List.of(), Set.of()));
    }

    @Test
    void 走不通又没有别的方式_写明各方式的原因() {
        TravelPlanner.Plan plan = TravelPlanner.choose(null, HERE, new BlockPos(10, 64, 0), true, false,
                List.of(), List.of(new TravelPlanner.Declined(ELEVATOR, Problem.Kind.UNSUPPORTED, "附近没有电梯")), Set.of());
        TravelPlanner.Plan.DeadEnd dead = assertInstanceOf(TravelPlanner.Plan.DeadEnd.class, plan);
        assertEquals(Problem.Kind.UNREACHABLE, dead.problem().kind());
    }

    @Test
    void 试过的报价这一趟不再试() {
        BlockPos top = new BlockPos(5, 100, 5);
        TravelOffer tried = offer(ELEVATOR, top, 10);
        TravelPlanner.Plan plan = TravelPlanner.choose(null, HERE, top, true, false, List.of(tried), List.of(),
                Set.of(TravelPlanner.key(tried)));
        assertInstanceOf(TravelPlanner.Plan.DeadEnd.class, plan);
    }

    @Test
    void 送完离目的地不近的报价不算往前送() {
        BlockPos top = new BlockPos(0, 100, 0);
        TravelOffer down = offer(ELEVATOR, new BlockPos(0, 30, 0), 10);
        assertInstanceOf(TravelPlanner.Plan.Walk.class, TravelPlanner.choose(null, HERE, top, false, false,
                List.of(down), List.of(), Set.of()));
    }

    @Test
    void 比的是坐这段再走完剩下的总耗时() {
        BlockPos far = new BlockPos(300, 64, 0);
        // 这段要 600 刻，送到的地方离目的地还有 200 格：加起来比直接走 300 格还慢。
        TravelOffer sideways = offer(ELEVATOR, new BlockPos(100, 64, 0), 600);
        assertInstanceOf(TravelPlanner.Plan.Walk.class, TravelPlanner.choose(null, HERE, far, false, false,
                List.of(sideways), List.of(), Set.of()));
    }
}

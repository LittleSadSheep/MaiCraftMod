// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 航点选择：打分偏向往外走、穿水让位、全穿水仍选出、纯水面不选、已试邻域去重。 */
class WaypointChooserTest {

    private static WaypointChooser.Candidate candidate(int x, int z, boolean waterSurface,
            boolean crossesWater, double fromOrigin, double rayDistance, double eccentricity) {
        return new WaypointChooser.Candidate(new BlockPos(x, 0, z), waterSurface, crossesWater,
                fromOrigin, rayDistance, eccentricity);
    }

    @Test
    void 打分偏向往外走与沿中线走远() {
        // 同样的射线距离，离原点更远的得分更高：覆盖范围由目标区域决定。
        double outward = WaypointChooser.score(candidate(60, 0, false, false, 60, 20, 0));
        double inward = WaypointChooser.score(candidate(20, 0, false, false, 20, 20, 0));
        assertTrue(outward > inward);
        // 同样的离原点距离，偏心越小得分越高。
        double centered = WaypointChooser.score(candidate(0, -60, false, false, 60, 60, 0));
        double offAxis = WaypointChooser.score(candidate(40, -45, false, false, 60, 60, 35));
        assertTrue(centered > offAxis);
    }

    @Test
    void 穿水的候选让位_有干路先走干路() {
        // 实机 175/073 场景：穿水是优先级不是否决。
        Optional<WaypointChooser.Candidate> chosen = WaypointChooser.choose(List.of(
                candidate(0, -40, false, true, 40, 40, 0),
                candidate(30, -30, false, false, 42, 42, 5)), Set.of());
        assertEquals(30, chosen.orElseThrow().column().getX());
    }

    @Test
    void 全部候选都穿水时仍选出得分最高的() {
        // 身处岛屿：穿水让不出干路，还是要选一条，渡不渡得过去由走到的跨水保证结算。
        Optional<WaypointChooser.Candidate> chosen = WaypointChooser.choose(List.of(
                candidate(0, -40, false, true, 40, 40, 0),
                candidate(10, -20, false, true, 22, 22, 3)), Set.of());
        assertEquals(0, chosen.orElseThrow().column().getX());
    }

    @Test
    void 纯水面的落点列不选() {
        // 实机 175 场景：落点列是纯水面会把身体领进水里，让位给干地，没有干地就选不出。
        Optional<WaypointChooser.Candidate> chosen = WaypointChooser.choose(List.of(
                candidate(0, -40, true, false, 40, 40, 0)), Set.of());
        assertTrue(chosen.isEmpty());
    }

    @Test
    void 已试过的落点十六格内的候选不再选() {
        long tried = new BlockPos(0, 0, -40).asLong();
        Optional<WaypointChooser.Candidate> chosen = WaypointChooser.choose(List.of(
                candidate(2, -42, false, false, 44, 44, 2),
                candidate(60, 0, false, false, 60, 80, 0)), Set.of(tried));
        assertEquals(60, chosen.orElseThrow().column().getX());
    }

    @Test
    void 十六格外不算同一个地方() {
        long tried = new BlockPos(0, 0, -40).asLong();
        Optional<WaypointChooser.Candidate> chosen = WaypointChooser.choose(List.of(
                candidate(0, -60, false, false, 60, 60, 0)), Set.of(tried));
        assertEquals(-60, chosen.orElseThrow().column().getZ(), "隔着 20 格，不是同一个已试的落点");
    }
}

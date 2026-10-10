// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 站位判断：按能到、够得着、看得见、站得稳筛候选并排序；被拒的位置记着败在哪一项。 */
class StandSpotsTest {

    private static final ReachRules REACH = new ReachRules(4.5, 3.0, 3, 1.62);

    private final StubWorld world = new StubWorld();
    private final StubWalkCost walking = new StubWalkCost();
    private final StubGuarded guarded = new StubGuarded();

    private StandSpots.Ranking find(ApproachTarget target) {
        return StandSpots.find(target, REACH, world, walking, guarded);
    }

    @Test
    void 平地上围着目标找出一圈能用的站位并按代价排好序() {
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertFalse(ranking.spots().isEmpty());
        // 排序：前面的代价不比后面的大，同代价的按格子位置排，保证顺序稳定。
        for (int i = 1; i < ranking.spots().size(); i++) {
            assertTrue(ranking.spots().get(i - 1).compareTo(ranking.spots().get(i)) <= 0);
        }
        // 目标紧邻的位置应该出现：贴着方块才能对它动手。
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(2, 64, 0))));
    }

    @Test
    void 脚下悬空和头顶没空间的格子被拒() {
        world.noFloor.add(new BlockPos(2, 64, 0));
        world.lowCeiling.add(new BlockPos(2, 64, 1));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("脚下悬空", failureAt(ranking, new BlockPos(2, 64, 0)));
        assertEquals("头顶没空间", failureAt(ranking, new BlockPos(2, 64, 1)));
    }

    @Test
    void 深落差液体岩浆和受保护的格子都不站() {
        world.deepDrops.add(new BlockPos(2, 64, 0));
        world.fluids.add(new BlockPos(2, 64, 1));
        world.lavas.add(new BlockPos(2, 64, -1));
        guarded.add(new BlockPos(2, 63, 0));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("落差太深", failureAt(ranking, new BlockPos(2, 64, 0)));
        assertEquals("泡在液体里", failureAt(ranking, new BlockPos(2, 64, 1)));
        assertEquals("旁边有岩浆", failureAt(ranking, new BlockPos(2, 64, -1)));
        assertEquals("受保护", failureAt(ranking, new BlockPos(2, 63, 0)));
    }

    @Test
    void 超出方块交互距离的格子记为够不着() {
        // 交互距离几乎为零，目标块又不能站进去：所有候选不是够不着就是进不去。
        world.lowCeiling.add(new BlockPos(0, 64, 5));
        world.lowCeiling.add(new BlockPos(0, 63, 5));
        ReachRules 近视 = new ReachRules(0.3, 3.0, 3, 1.62);
        StandSpots.Ranking ranking = StandSpots.find(
                ApproachTarget.ofBlock(new BlockPos(0, 64, 5)), 近视, world, walking, guarded);
        assertTrue(ranking.spots().isEmpty());
        assertFalse(ranking.rejected().isEmpty());
        assertEquals("够不着", failureAt(ranking, new BlockPos(0, 64, 4)));
    }

    @Test
    void 床按服务端距离分轴卡() {
        ApproachTarget bed = ApproachTarget.ofBed(new BlockPos(0, 60, 0));
        StandSpots.Ranking ranking = find(bed);
        // 横向差四格超出床距离档；横向差三格、高度差两格正好在服务端允许的边上。
        assertEquals("够不着", failureAt(ranking, new BlockPos(4, 60, 0)));
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(3, 62, 0))));
        // 高度差三格超出床距离档；候选只在目标上下两格内找，直接核对站在那里行不行。
        world.feet = new BlockPos(0, 63, 0);
        assertEquals(Optional.of("够不着"), StandSpots.checkArrival(bed, REACH, world, guarded));
    }

    @Test
    void 实体按实体交互距离卡() {
        ApproachTarget entity = ApproachTarget.ofEntity(new AABB(4, 64, 0, 5, 66, 1));
        StandSpots.Ranking ranking = StandSpots.find(entity, REACH, world, walking, guarded);
        // 平地上围着实体的一圈都在三格实体距离内，找得到能用的站位，先走代价最小的。
        assertFalse(ranking.spots().isEmpty());
        assertEquals(1.0, ranking.spots().getFirst().walkCost());
        // 离实体十格的角色：实体距离够不够与角色站多远无关，但代价排序让它先走身边的候选。
        world.feet = new BlockPos(14, 64, 0);
        StandSpots.Ranking far = StandSpots.find(entity, REACH, world, walking, guarded);
        assertFalse(far.spots().isEmpty());
        assertEquals(7.0, far.spots().getFirst().walkCost());
        // 实体距离收得极紧时一个候选都留不下；把身体能探进实体的三格头顶封住，免得替身里站进实体。
        world.lowCeiling.add(new BlockPos(4, 62, 0));
        world.lowCeiling.add(new BlockPos(4, 63, 0));
        world.lowCeiling.add(new BlockPos(4, 64, 0));
        ReachRules 贴脸 = new ReachRules(4.5, 0.4, 3, 1.62);
        assertTrue(StandSpots.find(entity, 贴脸, world, walking, guarded).spots().isEmpty());
    }

    @Test
    void 射线被挡住的格子记为看不见() {
        // 半边天被墙挡住：眼睛在挡板西侧的候选都看不见。
        world.hidden = (eye, target) -> eye.x() < 1.5;
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("看不见", failureAt(ranking, new BlockPos(0, 64, 0)));
        assertTrue(ranking.spots().stream().allMatch(spot -> spot.feet().getX() >= 1));
    }

    @Test
    void 寻路回答走不过去的格子记为到不了() {
        walking.unreachable.add(new BlockPos(2, 64, 0));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("到不了", failureAt(ranking, new BlockPos(2, 64, 0)));
    }

    @Test
    void 到了之后四下核对() {
        ApproachTarget target = ApproachTarget.ofBlock(new BlockPos(3, 64, 0));
        assertEquals(Optional.empty(), StandSpots.checkArrival(target, REACH, world, guarded));
        // 离目标太远：够不着。
        world.feet = new BlockPos(20, 64, 0);
        assertEquals(Optional.of("够不着"), StandSpots.checkArrival(target, REACH, world, guarded));
        // 站对了位置但被挡住：看不见。
        world.feet = new BlockPos(2, 64, 0);
        world.hidden = (eye, t) -> true;
        assertEquals(Optional.of("看不见"), StandSpots.checkArrival(target, REACH, world, guarded));
        // 还在跳跃中：没落地。
        world.hidden = (eye, t) -> false;
        world.grounded = false;
        assertEquals(Optional.of("还没落地"), StandSpots.checkArrival(target, REACH, world, guarded));
    }

    private String failureAt(StandSpots.Ranking ranking, BlockPos feet) {
        List<RejectedSpot> matches = ranking.rejected().stream()
                .filter(spot -> spot.feet().equals(feet)).toList();
        assertEquals(1, matches.size(), "位置 " + feet + " 应该恰好在被拒表里出现一次");
        return matches.getFirst().failedItem();
    }
}

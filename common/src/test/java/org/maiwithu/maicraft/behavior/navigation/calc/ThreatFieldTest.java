// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;

/** 避怪寻路：离每一只都够远才算脱身、越近越不划算、接近目标时绕开别的怪、目标比较不分威胁先后。 */
class ThreatFieldTest {

    private static final ThreatField.Threat LEFT = new ThreatField.Threat(-1.5, 64, 0.5, 2.7);
    private static final ThreatField.Threat RIGHT = new ThreatField.Threat(2.5, 64, 0.5, 2.7);

    @Test
    void clearOnlyWhenOutsideEveryThreat() {
        // 一左一右两只：只躲开最近那只不算，得离两只都出圈。
        ThreatField field = new ThreatField(ThreatField.DEFAULT_PENALTY, List.of(LEFT, RIGHT));
        assertFalse(field.clearAt(0, 64, 0), "夹在两只中间");
        assertFalse(field.clearAt(-3, 64, 0), "躲开了右边那只，左边那只还够得着");
        assertTrue(field.clearAt(0, 64, 5), "往侧面走出两只的圈");
        // 高度差不算脱身：楼上那一格水平上仍在圈里。
        assertFalse(field.clearAt(-2, 70, 0));
    }

    @Test
    void closerCellsCostMore() {
        ThreatField field = new ThreatField(ThreatField.DEFAULT_PENALTY, List.of(LEFT));
        assertTrue(field.penaltyAt(-1, 64, 0) > field.penaltyAt(0, 64, 0));
        assertTrue(field.penaltyAt(0, 64, 0) > field.penaltyAt(4, 64, 0));
        assertEquals(ThreatField.ON_TOP_OF_THREAT * ThreatField.DEFAULT_PENALTY,
                new ThreatField(ThreatField.DEFAULT_PENALTY, List.of(new ThreatField.Threat(0.5, 64, 0.5, 2)))
                        .penaltyAt(0, 64, 0), 1e-9, "正踩在威胁身上不除以零");
    }

    @Test
    void awayFromThreatsArrivesOutsideAllCirclesAndPullsOutward() {
        NavGoal away = GoalCompiler.awayFrom(List.of(LEFT, RIGHT)).goal();
        assertFalse(away.isAt(new BlockPos(0, 64, 0)));
        assertTrue(away.isAt(new BlockPos(0, 64, 6)));
        // 估价往外拉：离圈越远剩下的越少。
        assertTrue(away.heuristic(new BlockPos(0, 64, 1)) > away.heuristic(new BlockPos(0, 64, 4)));
        // 逃跑把脱身距离拉到很远时，估价仍带着"还差多远"，不会走出近处的惩罚就不想再走。
        NavGoal flee = GoalCompiler.awayFrom(List.of(LEFT.withClearance(32))).goal();
        assertTrue(flee.heuristic(new BlockPos(10, 64, 0)) > flee.heuristic(new BlockPos(20, 64, 0)));
    }

    @Test
    void approachAvoidingNeedsBothTheTargetAndClearance() {
        // 打的那只自己也在威胁场里：带子外沿是我够得着它（3.15），内沿由它的危险半径顶住（2.02）。
        Vec3 zombie = new Vec3(0.5, 64, 0.5);
        GoalCompiler.Compiled band = GoalCompiler.band(zombie, 0, 3.15);
        NavGoal stance = GoalCompiler.approachAvoiding(band,
                List.of(new ThreatField.Threat(zombie.x, zombie.y, zombie.z, 2.02))).goal();
        assertFalse(stance.isAt(new BlockPos(1, 64, 0)), "贴脸那一格它够得着我");
        assertTrue(stance.isAt(new BlockPos(3, 64, 0)), "够得着它、它够不着我的那条缝");
        assertFalse(stance.isAt(new BlockPos(5, 64, 0)), "太远砍不到");
        // 进度只看走没走近目标，不被惩罚带偏。
        assertEquals(band.goal().progressHeuristic(new BlockPos(8, 64, 0)),
                stance.progressHeuristic(new BlockPos(8, 64, 0)), 1e-9);
        // 没有威胁就是原目标。
        assertEquals(band, GoalCompiler.approachAvoiding(band, List.of()));
    }

    @Test
    void fingerprintIgnoresThreatOrderButSeesChanges() {
        NavGoal a = GoalCompiler.awayFrom(List.of(LEFT, RIGHT)).goal();
        NavGoal b = GoalCompiler.awayFrom(List.of(RIGHT, LEFT)).goal();
        NavGoal moved = GoalCompiler.awayFrom(List.of(LEFT, new ThreatField.Threat(3.5, 64, 0.5, 2.7))).goal();
        assertEquals(a.semanticFingerprint(), b.semanticFingerprint());
        assertNotEquals(a.semanticFingerprint(), moved.semanticFingerprint());
    }
}

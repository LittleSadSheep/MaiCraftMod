// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用连续角度请求检查镜头转速、同一刻重复请求和左右小幅抖动；不需要启动游戏画面。
 */
class NavigationCameraCourseTest {

    @Test
    void walkingKeepsPitchNearLevelAndDivingKeepsRequestedPitch() {
        // 普通上坡跳跃、落地和下坡请求交替时视角保持水平附近，潜泳继续采用自己的原生方向。
        for (float pitch : new float[]{80, -30, 8, 90, -90}) {
            assertEquals(8, NavigationCameraCourse.pitch(pitch, false),
                    "walking and jumping do not alternate path-point pitch");
            assertEquals(pitch, NavigationCameraCourse.pitch(pitch, true),
                    "submerged navigation keeps its requested dive direction");
        }
    }

    @Test
    void onlyFinalFirstTickRequestEstablishesInitialCourse() {
        var course = new NavigationCameraCourse();
        course.target(0, false, 0);
        assertEquals(90, course.target(90, false, 0), "only the final first-tick request establishes the initial course");
        course.reset();
        course.target(30, false, 0);
        assertEquals(-30, course.target(-30, false, 0), "reset within the same revision still replaces the initial course");
        course.reset();
        assertEquals(0, course.target(0, false, 1), "first course");
    }

    @Test
    void turnBudgetPerTickCannotBeMultipliedByMovementHandoffs() {
        var course = new NavigationCameraCourse();
        course.reset();
        course.target(0, false, 1);
        assertEquals(9, course.target(90, false, 2), "turn budget per tick");
        for (int i = 0; i < 12; i++) {
            assertEquals(9, course.target(90, false, 2), "movement handoffs cannot multiply the turn rate");
        }
        assertEquals(-9, course.target(-90, false, 2), "the final target replaces an intermediate completed movement target");
        assertEquals(-18, course.target(-90, false, 3), "a new tick advances once");
    }

    @Test
    void wrappedNearbyHeadingsDoNotTriggerFullTurn() {
        var course = new NavigationCameraCourse();
        course.reset();
        assertEquals(179, course.target(179, false, 4), "wrapped heading turns the short way");
        assertEquals(179, course.target(-179, false, 5), "wrapped nearby headings do not trigger a full turn");
    }

    @Test
    void aTurnContinuesUntilTheCameraFacesTheRoute() {
        // 原先转到差十五度就停，后面整段直线都歪着看；现在一直转到对正。
        var course = new NavigationCameraCourse();
        course.target(0, false, 1);
        float yaw = 0;
        for (int tick = 2; tick < 20; tick++) yaw = course.target(40, false, tick);
        assertEquals(40, yaw, "the walk turn does not stop fifteen degrees short");
    }

    @Test
    void aSteadyDriftToOneSideIsCorrectedAfterHalfASecond() {
        // 长直线比镜头偏了十五度：不到半秒不动，偏同一边满半秒就转过去，最后对正。
        var course = new NavigationCameraCourse();
        course.target(0, false, 100);
        for (int tick = 101; tick < 110; tick++) {
            assertEquals(0, course.target(15, false, tick), "a short drift is not chased yet");
        }
        float yaw = 0;
        for (int tick = 110; tick < 130; tick++) yaw = course.target(15, false, tick);
        assertEquals(15, yaw, "a long straight line is not walked with a skewed camera");
    }

    @Test
    void sprintingKeepsTheCameraWithinVanillaGlancingTolerance() {
        // 原版只在镜头与前进方向相差八度以内时把蹭到方块算擦边；疾跑时偏五度以上就转，转到对正。
        var course = new NavigationCameraCourse();
        course.target(0, true, 1);
        assertEquals(0, course.target(4, true, 2), "a tiny sprint error does not turn at once");
        assertEquals(7, course.target(7, true, 3), "a sprint error past five degrees is corrected");
        assertEquals(19, course.target(30, true, 4), "sprinting turns up to twelve degrees per tick");
        assertEquals(30, course.target(30, true, 5));
    }

    @Test
    void lookAheadAveragesTheGridStaircaseOfALongLine() {
        // 斜着的长直线在格子路线上是一格直走一格斜走：看往前四格以外的那个路点，而不是紧挨着的下一格。
        List<BlockPos> staircase = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 1),
                new BlockPos(3, 64, 1), new BlockPos(4, 64, 2), new BlockPos(5, 64, 2), new BlockPos(6, 64, 3));
        Vec3 body = new Vec3(0.5, 64, 0.5);
        assertEquals(Vec3.atBottomCenterOf(new BlockPos(4, 64, 2)), BaritoneWalkRun.lookAhead(staircase, 1, body));
        // 往上搭柱子：路点都在头顶，数到上限就停，取数到的最后一个。
        List<BlockPos> pillar = List.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0), new BlockPos(0, 66, 0),
                new BlockPos(0, 67, 0), new BlockPos(0, 68, 0), new BlockPos(0, 69, 0), new BlockPos(0, 70, 0),
                new BlockPos(0, 71, 0), new BlockPos(0, 72, 0));
        assertEquals(Vec3.atBottomCenterOf(new BlockPos(0, 70, 0)), BaritoneWalkRun.lookAhead(pillar, 1, body));
        assertEquals(Vec3.atBottomCenterOf(new BlockPos(6, 64, 3)), BaritoneWalkRun.lookAhead(staircase, 9, body),
                "past the end the last point is used");
    }

    @Test
    void smallBearingCorrectionsDoNotShakeCamera() {
        var course = new NavigationCameraCourse();
        course.reset();
        course.target(0, false, 6);
        for (int tick = 7; tick < 50; tick++) {
            assertEquals(0, course.target(tick % 2 == 0 ? 10 : -10, false, tick),
                    "small bearing corrections do not shake the camera");
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

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
        course.target(0, 0);
        assertEquals(90, course.target(90, 0), "only the final first-tick request establishes the initial course");
        course.reset();
        course.target(30, 0);
        assertEquals(-30, course.target(-30, 0), "reset within the same revision still replaces the initial course");
        course.reset();
        assertEquals(0, course.target(0, 1), "first course");
    }

    @Test
    void turnBudgetPerTickCannotBeMultipliedByMovementHandoffs() {
        var course = new NavigationCameraCourse();
        course.reset();
        course.target(0, 1);
        assertEquals(9, course.target(90, 2), "turn budget per tick");
        for (int i = 0; i < 12; i++) {
            assertEquals(9, course.target(90, 2), "movement handoffs cannot multiply the turn rate");
        }
        assertEquals(-9, course.target(-90, 2), "the final target replaces an intermediate completed movement target");
        assertEquals(-18, course.target(-90, 3), "a new tick advances once");
    }

    @Test
    void wrappedNearbyHeadingsDoNotTriggerFullTurn() {
        var course = new NavigationCameraCourse();
        course.reset();
        assertEquals(179, course.target(179, 4), "wrapped heading turns the short way");
        assertEquals(179, course.target(-179, 5), "wrapped nearby headings do not trigger a full turn");
    }

    @Test
    void smallBearingCorrectionsDoNotShakeCamera() {
        var course = new NavigationCameraCourse();
        course.reset();
        course.target(0, 6);
        for (int tick = 7; tick < 50; tick++) {
            assertEquals(0, course.target(tick % 2 == 0 ? 10 : -10, tick),
                    "small bearing corrections do not shake the camera");
        }
    }
}

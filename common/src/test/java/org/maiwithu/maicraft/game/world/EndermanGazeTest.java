// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 末影人对视：正对眼睛才惹毛，远了容差变小，隔墙、戴南瓜头不算；镜头往下压挑第一个不对视的俯角。 */
class EndermanGazeTest {

    private static final Vec3 EYE = new Vec3(0, 65.62, 0);

    /** 正北（-z）方向 distance 格外、眼睛比我高 rise 格的一只末影人。 */
    private static EndermanGaze.Eyes north(double distance, double rise, boolean inSight) {
        return new EndermanGaze.Eyes(0, EYE.y + rise, -distance, inSight);
    }

    @Test
    void lookingStraightAtItsEyesProvokes() {
        EndermanGaze.Eyes enderman = north(10, 0.93, true);
        Vec3 atEyes = new Vec3(0, 0.93, -10);
        assertTrue(EndermanGaze.provokes(EYE, atEyes, enderman, false));
        assertFalse(EndermanGaze.provokes(EYE, atEyes, enderman, true), "戴着雕刻南瓜不惹毛");
        assertFalse(EndermanGaze.provokes(EYE, atEyes, north(10, 0.93, false), false), "隔着墙看不见它的眼睛不算");
        assertFalse(EndermanGaze.provokes(EYE, atEyes, north(70, 0.93, true), false), "64 格外不算");
    }

    @Test
    void toleranceShrinksWithDistance() {
        // 视线偏开 3 度：近处 4 格还在容差里（约 6.4 度），30 格外就不算对视了（约 2.3 度）。
        Vec3 offByThreeDegrees = EndermanGaze.lookVector(3f, 0f).multiply(1, 1, -1);
        assertTrue(EndermanGaze.provokes(EYE, offByThreeDegrees, north(4, 0, true), false));
        assertFalse(EndermanGaze.provokes(EYE, offByThreeDegrees, north(30, 0, true), false));
    }

    @Test
    void lookVectorMatchesVanillaAxes() {
        // 原版：偏航 0 朝南（+z），偏航 90 朝西（-x），俯仰往下为正。
        Vec3 south = EndermanGaze.lookVector(0f, 0f);
        assertEquals(1.0, south.z, 1.0e-9);
        Vec3 west = EndermanGaze.lookVector(90f, 0f);
        assertEquals(-1.0, west.x, 1.0e-9);
        assertTrue(EndermanGaze.lookVector(0f, 45f).y < 0, "俯仰为正是往下看");
    }

    @Test
    void lookingDownPicksTheFirstPitchThatDoesNotMeetAnyEyes() {
        // 正北 20 格一只、眼睛比我高一格：平视对上了，往下压到 8 度就错开。
        EndermanGaze.Eyes ahead = north(20, 0.93, true);
        float northYaw = 180f;
        assertTrue(EndermanGaze.provokesAny(EYE, EndermanGaze.lookVector(northYaw, -2.66f), List.of(ahead), false));
        assertEquals(8f, EndermanGaze.avertedPitch(EYE, northYaw, -2.66f, List.of(ahead), false));
        // 正前方地面上低三格还站着一只：俯 8 度又对上它，接着压到 35 度。
        EndermanGaze.Eyes below = new EndermanGaze.Eyes(0, EYE.y - 1.41, -10, true);
        assertTrue(EndermanGaze.provokes(EYE, EndermanGaze.lookVector(northYaw, 8f), below, false));
        assertEquals(35f, EndermanGaze.avertedPitch(EYE, northYaw, -2.66f, List.of(ahead, below), false));
        // 没有对上的就照原来的看。
        assertEquals(-20f, EndermanGaze.avertedPitch(EYE, northYaw, -20f, List.of(ahead), false));
    }
}

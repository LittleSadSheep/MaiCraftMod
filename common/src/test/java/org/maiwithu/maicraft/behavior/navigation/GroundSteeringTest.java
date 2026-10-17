// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 不经寻路朝一点挪时的按键：朝着目标点按，不管镜头朝哪；离目标半格以内按得轻。
 * 实机 10-15 镜头朝西、门在北边时一直按前进往西走，走进岩浆湖。
 */
class GroundSteeringTest {

    @Test
    void facingWestWithDoorToTheNorthSidestepsRightInsteadOfWalkingWest() {
        // 偏航 90 度是朝西；门在正北一格。
        PlayerInput.Movement keys = GroundSteering.keys(0.0, -1.0, 90.0f, false);
        assertEquals(0.0f, keys.forward(), 1.0e-4f);
        assertEquals(-1.0f, keys.strafe(), 1.0e-4f);
    }

    @Test
    void facingTheTargetWalksStraightAhead() {
        // 偏航 180 度是朝北。
        PlayerInput.Movement keys = GroundSteering.keys(0.0, -2.0, 180.0f, false);
        assertEquals(1.0f, keys.forward(), 1.0e-4f);
        assertEquals(0.0f, keys.strafe(), 1.0e-4f);
    }

    @Test
    void closeToTheTargetPressesLightly() {
        PlayerInput.Movement keys = GroundSteering.keys(0.0, -0.25, 180.0f, false);
        assertEquals(0.5f, keys.forward(), 1.0e-4f);
    }
}

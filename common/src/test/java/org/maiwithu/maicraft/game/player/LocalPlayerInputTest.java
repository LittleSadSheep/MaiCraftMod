// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色输入的离线部分：移动量的取值校验、哪些界面允许继续走路、
 * 转头时的世界方向合成，以及镜头平滑的几何性质。
 * 写进真实玩家输入的部分需要活着的客户端对象，只在实机验收里核对。
 */
class LocalPlayerInputTest {

    @Test
    void movementRejectsOutOfRangeImpulses() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerInput.Movement(1.5f, 0f, false, false, false), "前后量必须在 -1～1");
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerInput.Movement(0f, Float.NaN, false, false, false), "左右量必须是有限数值");
        assertEquals(0.5f, new PlayerInput.Movement(0.5f, -1f, true, false, true).forward());
    }

    @Test
    void chatDoesNotStopWalkingButBedChatAndMenusDo() {
        // 聊天框提供移动信号而非键盘事件，沿指定路线行走时可以保留；床上的聊天界面必须静止等自然醒。
        assertTrue(LocalPlayerInput.permitsWorldMovement(new ChatScreen("")),
                "普通聊天框不打断走路");
        assertTrue(LocalPlayerInput.permitsWorldMovement(null), "没有界面时可以走路");
        assertFalse(LocalPlayerInput.permitsWorldMovement(new InBedChatScreen()),
                "床上界面必须保持静止");
        assertFalse(LocalPlayerInput.permitsWorldMovement(new PauseScreen(true)), "暂停菜单不允许继续走");
    }

    @Test
    void steeringKeepsWorldDirectionWhenCameraTurns() {
        // 朝向变了以后，移动算法想要的那个世界方向不能跟着镜头转：按键按新朝向重新合成。
        // 世界方向 = -forward*sin(yaw) + strafe*cos(yaw)（东西），forward*cos(yaw) + strafe*sin(yaw)（南北）。
        float oldYaw = 0f;
        float nextYaw = 90f;
        PlayerInput.Movement original = new PlayerInput.Movement(1f, 0f, false, false, false);
        double[] before = worldDirection(original, oldYaw);
        PlayerInput.Movement rotated = LocalPlayerInput.preserveHeading(original, oldYaw, nextYaw);
        double[] after = worldDirection(rotated, nextYaw);
        assertEquals(before[0], after[0], 1e-6, "转头不得旋转原本想要的世界方向（东西分量）");
        assertEquals(before[1], after[1], 1e-6, "转头不得旋转原本想要的世界方向（南北分量）");
    }

    private static double[] worldDirection(PlayerInput.Movement movement, float yaw) {
        double rad = Math.toRadians(yaw);
        return new double[]{
                -movement.forward() * Math.sin(rad) + movement.strafe() * Math.cos(rad),
                movement.forward() * Math.cos(rad) + movement.strafe() * Math.sin(rad)};
    }

    @Test
    void smoothDampAngleTakesTheShorterWayAround() {
        // 从 179° 到 -179° 只差 2°：第一步必须经 180° 折过去（179.46…），而不是反向退回 178.5…。
        float value = 179f;
        float velocity = 0f;
        boolean crossedShortSide = false;
        for (int i = 0; i < 60 && Math.abs(value - (-179f)) > 0.5f; i++) {
            LocalPlayerInput.AxisStep step = LocalPlayerInput.smoothDampAngle(value, -179f, velocity, 0.11f, 240f, 0.05f);
            value = step.value();
            velocity = step.velocity();
            if (value > 180f || value < -179f) crossedShortSide = true;
        }
        assertTrue(crossedShortSide, "左右转头走较短的一边，先越过 180°");
        // 平滑器在连续角度上工作；181° 与 -179° 是同一个朝向。
        assertEquals(181f, value, 0.5f, "沿短边收敛到与目标相同的朝向");
    }

    @Test
    void smoothDampSettlesExactlyOnTarget() {
        // 阻尼每帧只收敛一部分；余差小于容差时直接吸附，调用方的“已对准”判定才能成立。
        LocalPlayerInput.AxisStep far = LocalPlayerInput.smoothDamp(0f, 90f, 0f, 0.11f, 240f, 0.05f);
        assertTrue(far.value() > 0f && far.value() < 90f, "远距离先朝目标推进一段");
        assertTrue(far.velocity() > 0f, "推进时保留角速度供下一帧继续");
        LocalPlayerInput.AxisStep settled = LocalPlayerInput.smoothDamp(89.9f, 90f, 0f, 0.11f, 240f, 0.05f);
        assertEquals(90f, settled.value(), 1e-3, "余差小到不可分辨时吸附到目标");
        assertEquals(0f, settled.velocity(), 1e-3, "吸附后角速度归零");
    }
}

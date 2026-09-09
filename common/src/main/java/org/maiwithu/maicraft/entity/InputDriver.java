// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.entity;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/**
 * 供移动算法使用的简短按键接口，例如朝前走、跳一下、看向一个方块。
 * 它把同一游戏刻的几个要求合成一组输入；每次都重新获取当前玩家，避免换维度后还操作旧对象。
 * 真正的控制权检查和输入写入在 BodyControlPort，挖掘与右键动作不由此类管理。
 */
public final class InputDriver {
    private static long commandTick = Long.MIN_VALUE;
    private static float forward;
    private static float strafe;
    private static boolean jumping;
    private static boolean sneaking;
    private static boolean sprinting;

    private InputDriver() {
    }

    // 朝目标的水平方向转头并按前进；这里不寻路，也不检查前面能否通过。
    public static void stepToward(LocalPlayer player, Vec3 target, boolean sprint) {
        LocalPlayerContext context = context(player);
        if (context == null) return;
        Vec3 delta = target.subtract(player.getEyePosition());
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        look(player, yaw, 12.0f);
        applyMovement(player, 1.0f, 0.0f, false, false, sprint);
    }

    // 从眼睛到目标点算出左右、上下两个角度，再交给身体控制器转头。
    public static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 delta = point.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        float pitch = (float) -(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG);
        look(player, yaw, pitch);
    }

    public static void look(LocalPlayer player, float yaw, float pitch) {
        LocalPlayerContext context = context(player);
        if (context == null) return;
        context.body().requestLook(yaw, pitch, context.tickRevision());
    }

    // 给本刻已有的移动指令加上跳跃，不清掉同刻的前进或潜行。
    public static void jump(LocalPlayer player) {
        LocalPlayerContext context = context(player);
        if (context == null) return;
        resetFor(context);
        jumping = true;
        flush(context);
    }

    // 改变本刻潜行状态，同时重新判断这种状态下能否疾跑。
    public static void sneak(LocalPlayer player, boolean on) {
        LocalPlayerContext context = context(player);
        if (context == null) return;
        resetFor(context);
        sneaking = on;
        sprinting = permitsSprint(sprinting, on, player.isInWater());
        flush(context);
    }

    public static void steerVehicle(LocalPlayer player, Vec3 target) {
        stepToward(player, target, false);
    }

    public static void haltVehicle(LocalPlayer player) {
        halt(player);
    }

    public static void halt(LocalPlayer player) {
        // MCP cancellation runs on the client thread but may arrive between actor ticks. Stopping
        // an existing input lease needs no native-action slot; requiring one made ordinary
        // cancellation throw and falsely report that its movement effects were uncertain.
        var boundary = ClientRuntime.actor();
        if (boundary.activeContext().isEmpty()) {
            if (net.minecraft.client.Minecraft.getInstance().player == player
                    && boundary.body().automationOwnsControls()) {
                boundary.body().releaseAll();
            }
            return;
        }
        LocalPlayerContext context = context(player);
        if (context == null) return;
        resetFor(context);
        forward = 0.0f;
        strafe = 0.0f;
        jumping = false;
        sneaking = false;
        sprinting = false;
        flush(context);
    }

    // 用完整的一组要求覆盖本刻按键；越界的前后、左右数值压到 -1～1。
    public static void applyMovement(
            LocalPlayer player,
            float requestedForward,
            float requestedStrafe,
            boolean requestedJump,
            boolean requestedSneak,
            boolean requestedSprint) {
        LocalPlayerContext context = context(player);
        if (context == null) return;
        resetFor(context);
        forward = Mth.clamp(requestedForward, -1.0f, 1.0f);
        strafe = Mth.clamp(requestedStrafe, -1.0f, 1.0f);
        jumping = requestedJump;
        sneaking = requestedSneak;
        sprinting = permitsSprint(requestedSprint, requestedSneak, player.isInWater());
        flush(context);
    }

    /** In water, Shift descends; it must not cancel the sprinting swimming pose. */
    static boolean permitsSprint(boolean requested, boolean sneak, boolean inWater) {
        return requested && (!sneak || inWater);
    }

    /**
     * Submit a bounded native hotbar selection. The caller retains and polls the returned receipt
     * on following ticks before performing an action with the selected item.
     */
    public static NativeActionReceipt selectHotbar(LocalPlayer player, int slot) {
        LocalPlayerContext context = context(player);
        if (context == null) {
            throw new IllegalStateException("automation does not own the local-player controls");
        }
        return context.actions().selectHotbar(context, slot, 10);
    }

    public static NativeActionReceipt poll(LocalPlayer player, NativeActionReceipt receipt) {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        return context.actions().poll(context, receipt);
    }

    private static LocalPlayerContext context(LocalPlayer player) {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        return context.body().automationOwnsControls() ? context : null;
    }

    // 首次进入新的一刻时从全松键开始；同一刻的多次 jump、sneak 调用可以叠加。
    private static void resetFor(LocalPlayerContext context) {
        if (commandTick == context.tickRevision()) return;
        commandTick = context.tickRevision();
        forward = 0.0f;
        strafe = 0.0f;
        jumping = false;
        sneaking = false;
        sprinting = false;
    }

    // 把合并后的本刻按键一次交给身体控制器，由它在合适的更新时机写入玩家。
    private static void flush(LocalPlayerContext context) {
        context.body().applyMovement(
                new BodyControlPort.Movement(forward, strafe, jumping, sneaking, sprinting),
                context.tickRevision());
    }
}

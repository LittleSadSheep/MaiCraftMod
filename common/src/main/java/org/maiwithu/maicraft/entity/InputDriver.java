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
 * Compatibility surface from the imported movement algorithms to the single first-person body.
 *
 * <p>The adapter stores only primitive commands for the active tick. It asks
 * {@link ClientRuntime} for a fresh context on every call and never retains a player, world or
 * context across ticks.</p>
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

    public static void stepToward(LocalPlayer player, Vec3 target, boolean sprint) {
        LocalPlayerContext context = context(player);
        if (context == null) return;
        Vec3 delta = target.subtract(player.getEyePosition());
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        look(player, yaw, 12.0f);
        applyMovement(player, 1.0f, 0.0f, false, false, sprint);
    }

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

    public static void jump(LocalPlayer player) {
        LocalPlayerContext context = context(player);
        if (context == null) return;
        resetFor(context);
        jumping = true;
        flush(context);
    }

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

    private static void resetFor(LocalPlayerContext context) {
        if (commandTick == context.tickRevision()) return;
        commandTick = context.tickRevision();
        forward = 0.0f;
        strafe = 0.0f;
        jumping = false;
        sneaking = false;
        sprinting = false;
    }

    private static void flush(LocalPlayerContext context) {
        context.body().applyMovement(
                new BodyControlPort.Movement(forward, strafe, jumping, sneaking, sprinting),
                context.tickRevision());
    }
}

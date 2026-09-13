// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.entity.InputDriver;

public final class CreateMechanicalPlacementAttemptTest {
    public static void main(String[] args) throws Exception {
        BlockPos stand = new BlockPos(-4, -60, -5);
        Vec3 edge = new Vec3(-3.0457348874, -60, -4.2888724116);
        check(BlockPos.containing(edge).equals(stand), "recorded failed jump already belonged to the planned grid cell");
        check(!CreateMechanicalPlacementAttempt.centered(edge, Vec3.ZERO, stand), "grid membership must not stand in for centered jump geometry");
        for (float yaw : new float[]{0, 29.9351f, 90, -120}) {
            var movement = CreateMechanicalPlacementAttempt.centerMovement(edge, stand, yaw);
            double angle = Math.toRadians(yaw);
            Vec3 world = new Vec3(-movement.forward() * Math.sin(angle) + movement.strafe() * Math.cos(angle), 0,
                    movement.forward() * Math.cos(angle) + movement.strafe() * Math.sin(angle));
            check(world.dot(Vec3.atBottomCenterOf(stand).subtract(edge)) > 0, "centering must move toward the stance for any actual camera yaw");
            check(world.length() <= .301 && !movement.jumping(), "centering remains a slow grounded input");
        }
        var attempt = new CreateMechanicalPlacementAttempt();
        observe(attempt, true, 1.62, false); attempt.requestJump();
        for (int tick = 0; tick < 4; tick++) {
            observe(attempt, true, 1.62, false);
            check(attempt.holdJump(), "a key request without observed takeoff must remain leased through a short native cooldown");
            attempt.jumpCommand();
        }
        observe(attempt, false, 2.37, true);
        check(attempt.airborne() && !attempt.holdJump(), "observed takeoff releases the key before a second jump can occur");
        observe(attempt, false, 2.87, true); observe(attempt, true, 1.62, false);
        check(!attempt.holdJump(), "landing must not re-arm a second jump");
        check(((Number) attempt.report().get("maximum_eye_y")).doubleValue() == 2.87
                && ((Number) attempt.report().get("exact_support_hit_ticks")).intValue() == 2,
                "failure diagnostics retain actual peak height and valid ray windows");
        for (int tick = 0; tick < 31; tick++) observe(attempt, true, 1.62, false);
        check(attempt.expired(), "one unsuccessful jump stays finite");
        actorInputLease();
        System.out.println("CreateMechanicalPlacementAttemptTest: exact stance, observed takeoff, bounded diagnostics and actor jump lease passed");
    }
    private static void actorInputLease() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            InputDriver.halt(h.player); InputDriver.sneak(h.player, false); InputDriver.jump(h.player);
            ClientRuntime.actor().endTick(ClientRuntime.requireContext(h.player));
            check(h.player.input.jumping && !h.player.input.shiftKeyDown, "jump survives halt/sneak composition when applied at actor endTick");
            h.nextTick(); InputDriver.halt(h.player); InputDriver.sneak(h.player, false); InputDriver.jump(h.player);
            ClientRuntime.actor().endTick(ClientRuntime.requireContext(h.player));
            check(h.player.input.jumping, "renewing a pending takeoff preserves the native input across actor ticks");
            h.nextTick(); InputDriver.halt(h.player);
            ClientRuntime.actor().endTick(ClientRuntime.requireContext(h.player));
            check(!h.player.input.jumping, "release after observed takeoff removes the jump input");
        }
    }
    private static void observe(CreateMechanicalPlacementAttempt attempt, boolean grounded, double eyeY, boolean hit) {
        attempt.observe(Vec3.ZERO, new Vec3(.5, eyeY, .5), Vec3.ZERO, grounded, !grounded, false, "STANDING", false,
                BlockPos.ZERO, BlockPos.ZERO.above(), "up", hit ? "0, 1, 0" : "miss", hit ? "up" : "none", hit, hit ? 3 : 15);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

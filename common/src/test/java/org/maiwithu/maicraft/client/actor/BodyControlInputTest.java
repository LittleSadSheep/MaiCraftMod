// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;

/** Directional keys and analog impulses must agree for controllers that read native Input flags. */
public final class BodyControlInputTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var h = new ActorControlTestHarness();
        for (float forward : new float[]{-1, 0, 0.5f}) {
            for (float strafe : new float[]{-0.5f, 0, 1}) {
                h.body.applyMovement(new BodyControlPort.Movement(forward, strafe, true, true, true), h.tick);
                h.body.endTick(h.context);
                expect(h, forward, strafe, true, true, true);
                h.body.releaseAll();
                expect(h, 0, 0, false, false, false);
            }
        }
        h.minecraft.screen = h.allocate(ChatScreen.class);
        move(h);
        expect(h, 1, -1, true, false, true);
        h.nextTick(true);
        h.body.endTick(h.context);
        expect(h, 0, 0, false, false, false);
        h.minecraft.screen = null;
        h.nextTick(true);
        h.player.setYRot(0);
        float[] evaluatedYaw = {Float.NaN};
        h.body.applySteering(yaw -> {
            evaluatedYaw[0] = yaw;
            double angle = Math.toRadians(yaw);
            return new BodyControlPort.Movement((float) -Math.sin(angle), (float) Math.cos(angle), false, false, false);
        }, 0, h.tick);
        h.body.requestLook(90, 0, h.tick);
        h.body.endTick(h.context);
        check(evaluatedYaw[0] == h.player.getYRot() && evaluatedYaw[0] > 0,
                "steering must use the physical yaw after camera smoothing, not the previous heading");
        double angle = Math.toRadians(h.player.getYRot());
        double worldX = -h.player.input.forwardImpulse * Math.sin(angle) + h.player.input.leftImpulse * Math.cos(angle);
        double worldZ = h.player.input.forwardImpulse * Math.cos(angle) + h.player.input.leftImpulse * Math.sin(angle);
        check(Math.abs(worldX - 1) < 1e-6 && Math.abs(worldZ) < 1e-6, "turning the camera must not rotate the intended world motion");
        h.body.requestImmediateLook(73,90,h.tick);
        check(h.player.getYRot()==73 && h.player.getXRot()==90 && h.player.yRotO==73 && h.player.xRotO==90,
                "urgent aim updates the actual native ray and visible camera before endTick or another render frame");
        h.body.endTick(h.context);
        check(h.player.getYRot()==73 && h.player.getXRot()==90,"old camera angular velocity cannot undo an urgent aim");
        try { h.body.requestImmediateLook(0,0,h.tick-1); throw new AssertionError("stale urgent lease accepted"); }
        catch (IllegalArgumentException expected) { }
        h.nextTick(true);
        h.body.endTick(h.context);
        expect(h, 0, 0, false, false, false);
        move(h);
        h.minecraft.screen = h.allocate(PauseScreen.class);
        h.body.endTick(h.context);
        expect(h, 0, 0, false, false, false);
        System.out.println("BodyControlInputTest: passed");
    }

    private static void move(ActorControlTestHarness h) {
        h.body.applyMovement(new BodyControlPort.Movement(1, -1, true, false, true), h.tick);
        h.body.endTick(h.context);
    }

    private static void expect(ActorControlTestHarness h, float forward, float strafe,
                               boolean jump, boolean sneak, boolean sprint) {
        var input = h.player.input;
        check(input.forwardImpulse == forward && input.leftImpulse == strafe, "movement impulses match the command");
        check(input.up == (forward > 0) && input.down == (forward < 0)
                        && input.left == (strafe > 0) && input.right == (strafe < 0),
                "directional input flags match the impulse sign, including zero and release");
        check(input.jumping == jump && input.shiftKeyDown == sneak && h.player.isSprinting() == sprint,
                "jump, sneak and sprint match the same leased command");
    }
}

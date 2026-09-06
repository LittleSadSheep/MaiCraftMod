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

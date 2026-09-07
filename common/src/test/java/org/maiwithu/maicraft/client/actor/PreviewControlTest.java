// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.client.player.Input;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.field;

/** A review is an input suspension, while F8 remains a revocation of the original authority. */
public final class PreviewControlTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var h = new ActorControlTestHarness();
        Input original = (Input) field(DefaultBodyControlPort.class, "humanInput").get(h.body);
        long request = field(DefaultBodyControlPort.class, "requestRevision").getLong(h.body);
        h.body.applyMovement(new BodyControlPort.Movement(1, 0, true, false, true), h.tick);
        h.body.requestLook(90, 30, h.tick);
        h.body.endTick(h.context);
        h.body.suspendForReview(true);
        check(h.player.input == original && !h.body.automationOwnsControls()
                && h.body.automationControlRequested() && !h.body.effectiveAutomationRequested(),
                "preview restores native input and retains only the logical grant");
        check(!h.actor.preventsMouseGrab(), "review must allow the player to capture the mouse and inspect");
        check(!h.body.fulfillAutomationRequest(h.player), "ordinary ticks cannot retake input during review");
        original.forwardImpulse = .7f;
        h.body.beginTick(h.tick + 1);
        check(original.forwardImpulse == .7f, "review ticks must not erase human movement");
        h.body.suspendForReview(false);
        check(h.body.fulfillAutomationRequest(h.player) && h.body.automationOwnsControls(),
                "confirmation can restore the original still-valid grant");
        check(field(DefaultBodyControlPort.class, "requestRevision").getLong(h.body) == request,
                "review does not manufacture a new authority revision");
        check(h.player.input.forwardImpulse == 0 && !h.player.input.jumping,
                "pre-review movement and camera leases never resume");
        h.body.suspendForReview(true);
        check(!h.body.toggleHumanRequest(h.player), "F8 revokes the retained request while reviewing");
        h.body.suspendForReview(false);
        check(!h.body.fulfillAutomationRequest(h.player) && h.player.input == original,
                "confirmation after F8 must not seize control again");
        h.body.suspendForReview(true);
        check(h.body.toggleHumanRequest(h.player) && !h.body.fulfillAutomationRequest(h.player),
                "even a new F8 grant must wait for review to finish");
        h.body.shutdown();
        h.body.suspendForReview(false);
        check(!h.body.fulfillAutomationRequest(h.player), "shutdown cannot leave an approval that restores control");
        System.out.println("PreviewControlTest: passed");
    }
}

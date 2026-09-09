// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;

/** Native actions observe the posture already applied across a physical player tick. */
public final class BodyPostureObservationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var h = new ActorControlTestHarness();
        request(h, true);
        check(!h.player.isShiftKeyDown(), "a new crouch request must not claim physical convergence immediately");
        h.body.endTick(h.context);
        h.nextTick(true);
        check(h.player.isShiftKeyDown(), "beginTick must preserve the preceding physical input observation");
        request(h, true);
        nativeUse(h, true);
        h.body.endTick(h.context);

        h.nextTick(true);
        request(h, false);
        check(h.player.isShiftKeyDown(), "a release request must wait for the next physical input boundary");
        h.body.endTick(h.context);
        h.nextTick(true);
        check(!h.player.isShiftKeyDown(), "the released posture becomes visible on the following task tick");
        nativeUse(h, false);

        request(h, true);
        h.body.endTick(h.context);
        h.nextTick(true);
        check(h.player.isShiftKeyDown(), "lease expiry must not erase the last observed posture early");
        h.body.endTick(h.context);
        check(!h.player.isShiftKeyDown(), "an unrenewed movement lease must release crouch at endTick");

        h.nextTick(true);
        request(h, true); h.body.endTick(h.context);
        h.body.releaseAll();
        check(!h.player.isShiftKeyDown(), "explicit release clears physical input immediately");
        h.nextTick(true);
        request(h, true); h.body.endTick(h.context);
        var botInput = h.player.input;
        h.body.toggleHumanRequest(h.player);
        check(!botInput.shiftKeyDown && !h.body.automationOwnsControls(),
                "F8 revocation clears the abandoned bot input immediately");
        check(!h.player.isShiftKeyDown(), "human input is restored without stale bot crouch");
        missingWorldStopsInputs();
        System.out.println("BodyPostureObservationTest: physical posture and native secondary use passed");
    }

    private static void missingWorldStopsInputs() throws Exception {
        var h = new ActorControlTestHarness();
        h.body.applyMovement(new BodyControlPort.Movement(1, 0, true, true, false), h.tick);
        h.body.endTick(h.context);
        ActorControlTestHarness.field(ClientActorBoundary.class, "windowControlActive").setBoolean(h.actor, true);
        check(h.actor.beginTick().isEmpty(), "a missing world cannot create a usable actor context");
        check(h.player.input.forwardImpulse == 0 && !h.player.input.jumping && !h.player.isShiftKeyDown(),
                "a boundary without an endTick must immediately clear expired physical input");
        check(h.body.automationControlRequested(), "clearing unavailable-world inputs must preserve the control request");
    }

    private static void request(ActorControlTestHarness h, boolean crouch) {
        h.body.applyMovement(new BodyControlPort.Movement(0, 0, false, crouch, false), h.tick);
    }

    private static void nativeUse(ActorControlTestHarness h, boolean expected) throws Exception {
        var mode = h.allocate(ObservedUseMode.class);
        var previousContext = h.context;
        h.minecraft.gameMode = mode;
        h.context = new DefaultLocalPlayerContext(h.actor, h.minecraft, h.player, null, mode, null,
                0, 0, h.tick, true);
        ActorControlTestHarness.field(ClientActorBoundary.class, "activeContext").set(h.actor, h.context);
        h.actions.advance(h.context);
        h.actions.useBlock(h.context, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false),
                ignored -> NativeConfirmation.Verdict.APPLIED, 5);
        check(mode.calls == 1 && mode.secondary == expected,
                "the native use path must observe the same converged secondary-use posture as the task");
        h.minecraft.gameMode = previousContext.gameMode();
        h.context = previousContext;
        ActorControlTestHarness.field(ClientActorBoundary.class, "activeContext").set(h.actor, h.context);
    }

    private static final class ObservedUseMode extends MultiPlayerGameMode {
        int calls;
        boolean secondary;
        private ObservedUseMode() { super(null, null); }
        @Override public InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
            calls++; secondary = player.isSecondaryUseActive();
            return InteractionResult.PASS;
        }
    }
}

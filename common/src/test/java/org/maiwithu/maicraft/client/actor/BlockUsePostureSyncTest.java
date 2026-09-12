// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;

/** The real action port must put current body facts before a block-use path with no look fields. */
public final class BlockUsePostureSyncTest {
    private static final BlockHitResult HIT = new BlockHitResult(
            new Vec3(1.5, 1, 1.5), Direction.UP, new BlockPos(1, 0, 1), false);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean sneak : new boolean[]{true, false}) synchronizesBeforeUse(sneak);
        failedSyncDoesNotClick();
        System.out.println("BlockUsePostureSyncTest: native block use follows current rotation and posture packets");
    }

    private static void synchronizesBeforeUse(boolean sneak) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var h = world.h;
            var ctx = ClientRuntime.requireContext(world.player);
            // The previous physical tick could have sent SOUTH; camera control now faces EAST.
            world.player.setYRot(0); world.player.setXRot(0);
            ctx.body().requestImmediateLook(-90, 12.5F, ctx.tickRevision());
            world.player.input.shiftKeyDown = sneak;
            Vec3 before = world.player.position();
            world.mode.beforeBlockUse = () -> {
                check(h.connection.packets.size() == 2, "both body packets must precede the native click");
                check(h.connection.packets.get(0) instanceof ServerboundMovePlayerPacket.Rot,
                        "rotation synchronization must not fabricate a position update");
                var rotation = (ServerboundMovePlayerPacket.Rot) h.connection.packets.get(0);
                check(rotation.getYRot(0) == -90 && rotation.getXRot(0) == 12.5F && rotation.isOnGround(),
                        "the server must receive the current physical camera, not the prior tick or a target projection");
                var posture = (ServerboundPlayerCommandPacket) h.connection.packets.get(1);
                check(posture.getAction() == (sneak ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                                : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY),
                        "the click must see the actual held or released crouch state");
                check(posture.getId() == world.player.getId(), "the posture belongs to the controlled player");
            };
            NativeConfirmation evidence = new NativeConfirmation() {
                @Override public Verdict observe(LocalPlayerContext current) { return Verdict.APPLIED; }
                @Override public boolean requiresBlockAcknowledgement() { return true; }
            };
            var receipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, HIT, evidence, 20);
            check(world.blockUses() == 1 && !receipt.terminal(), "sync cannot replace native use or its server acknowledgement");
            check(world.player.position().equals(before) && world.player.isShiftKeyDown() == sneak,
                    "synchronization must preserve the observed physical body");
            world.nextTick(); ctx = ClientRuntime.requireContext(world.player);
            ctx.actions().poll(ctx, receipt);
            check(!receipt.terminal() && h.connection.packets.size() == 2 && world.blockUses() == 1,
                    "waiting for acknowledgement must never resend body packets or click");
            world.level.acknowledgedSequence = world.level.blockSequence;
            ctx.actions().poll(ctx, receipt);
            check(!receipt.terminal(), "a tick already observed as pending cannot also count as a stable matched tick");
            world.nextTick(); ctx = ClientRuntime.requireContext(world.player);
            ctx.actions().poll(ctx, receipt);
            check(receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED,
                    "only the ordinary reconciled placement sequence completes the action");
        }
    }

    private static void failedSyncDoesNotClick() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.h.connection.failSend = true;
            var ctx = ClientRuntime.requireContext(world.player);
            var receipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, HIT,
                    ignored -> NativeConfirmation.Verdict.APPLIED, 20);
            check(world.blockUses() == 0 && receipt.status() == NativeActionReceipt.Status.UNCERTAIN,
                    "a failed connection send must prevent the click and cannot report successful placement");
            world.nextTick(); ctx = ClientRuntime.requireContext(world.player);
            ctx.actions().poll(ctx, receipt);
            check(world.blockUses() == 0, "uncertain synchronization must not trigger a retry");
        }
    }
}

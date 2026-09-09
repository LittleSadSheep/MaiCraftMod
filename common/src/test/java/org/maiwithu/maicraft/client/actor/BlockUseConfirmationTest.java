package org.maiwithu.maicraft.client.actor;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** Real action port: prediction, delayed acknowledgement, rollback, refusal, timeout and cancellation. */
public final class BlockUseConfirmationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var observed = new AtomicReference<>(NativeConfirmation.Verdict.APPLIED);
            NativeConfirmation evidence = new NativeConfirmation() {
                @Override public Verdict observe(LocalPlayerContext ctx) { return observed.get(); }
                @Override public boolean requiresBlockAcknowledgement() { return true; }
            };
            var hit = new BlockHitResult(new Vec3(1.5, 1, 1.5), Direction.UP, new BlockPos(1, 0, 1), false);
            var ctx = ClientRuntime.requireContext(h.player);
            var receipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit, evidence, 20);
            check(!receipt.terminal(), "immediate local prediction cannot finish a placement");
            for (int i = 0; i < 4; i++) {
                h.nextTick(); ctx = ClientRuntime.requireContext(h.player);
                ctx.actions().poll(ctx, receipt);
                check(!receipt.terminal(), "multiple matching client ticks cannot replace this click's acknowledgement");
            }
            observed.set(NativeConfirmation.Verdict.NOT_APPLIED);
            h.level.acknowledgedSequence = h.level.blockSequence;
            ctx.actions().poll(ctx, receipt);
            check(receipt.status() == NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED,
                    "server rollback and unchanged effects must permit another stance");

            h.nextTick(); ctx = ClientRuntime.requireContext(h.player);
            observed.set(NativeConfirmation.Verdict.APPLIED);
            receipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit, evidence, 20);
            check(!receipt.terminal(), "an old click's acknowledgement cannot confirm the next click");
            h.nextTick(); ctx = ClientRuntime.requireContext(h.player);
            h.level.acknowledgedSequence = h.level.blockSequence;
            ctx.actions().poll(ctx, receipt);
            check(receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED, "acknowledged matching effects succeed");

            h.nextTick(); ctx = ClientRuntime.requireContext(h.player);
            observed.set(NativeConfirmation.Verdict.DIVERGED);
            receipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit, evidence, 20);
            check(!receipt.terminal(), "even divergence may be corrected when the prediction is reconciled");
            h.level.acknowledgedSequence = h.level.blockSequence;
            ctx.actions().poll(ctx, receipt);
            check(receipt.status() == NativeActionReceipt.Status.DIVERGED, "wrong server state must never become success");

            h.nextTick(); ctx = ClientRuntime.requireContext(h.player);
            observed.set(NativeConfirmation.Verdict.APPLIED);
            receipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit, evidence, 2);
            for (int i = 0; i < 2; i++) { h.nextTick(); ctx = ClientRuntime.requireContext(h.player); ctx.actions().poll(ctx, receipt); }
            check(receipt.status() == NativeActionReceipt.Status.UNCERTAIN, "no acknowledgement must time out without another click");
            h.level.acknowledgedSequence = h.level.blockSequence;
            ctx.actions().poll(ctx, receipt);
            check(receipt.status() == NativeActionReceipt.Status.UNCERTAIN, "late packets cannot revive expired effects");

            h.nextTick(); ctx = ClientRuntime.requireContext(h.player);
            receipt = ctx.actions().useBlock(ctx, InteractionHand.MAIN_HAND, hit, evidence, 20);
            ctx.actions().retireOneShotForTaskBoundary(ctx, receipt, "test owner stopped");
            h.level.acknowledgedSequence = h.level.blockSequence;
            check(receipt.terminal() && receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED,
                    "cancellation does not leave an active acknowledgement listener");
            check(h.blockUses() == 5, "confirmation and expiry must never issue a retry themselves");
        }
        BlockUseAcknowledgement noPacket = new BlockUseAcknowledgement() {
            public int maicraft$currentBlockSequence() { return 12; }
            public int maicraft$acknowledgedBlockSequence() { return 8; }
        };
        var refused = new BlockUseConfirmation(ctx -> NativeConfirmation.Verdict.NOT_APPLIED, noPacket);
        refused.submitted();
        check(refused.observe(null) == NativeConfirmation.Verdict.NOT_APPLIED,
                "a locally refused use with unchanged effects need not wait for a nonexistent packet");
        var unexplained = new BlockUseConfirmation(ctx -> NativeConfirmation.Verdict.APPLIED, noPacket);
        unexplained.submitted();
        check(unexplained.observe(null) == NativeConfirmation.Verdict.DIVERGED,
                "an unsequenced local change cannot masquerade as a server-confirmed placement");
        System.out.println("BlockUseConfirmationTest: native placement waits for its reconciled server sequence");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}

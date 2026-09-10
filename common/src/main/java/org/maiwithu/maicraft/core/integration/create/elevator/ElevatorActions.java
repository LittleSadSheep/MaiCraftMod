package org.maiwithu.maicraft.core.integration.create.elevator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorBridge.Cabin;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorSurvey.CallInput;
import org.maiwithu.maicraft.entity.InputDriver;

/**
 * 串行提交电梯操作并等确认；呼梯前复核按钮或遥控器确实关联这层，遥控按下后还负责松开和恢复临时手持。
 */
final class ElevatorActions {
    private NativeActionReceipt receipt;
    private final ElevatorRemoteStaging preparation = new ElevatorRemoteStaging();
    boolean staging;
    boolean remoteHeld;
    boolean uncertain;
    String failure;

    boolean settle(LocalPlayerContext ctx) {
        if (receipt == null) return true;
        receipt = ctx.actions().poll(ctx, receipt);
        if (!receipt.terminal()) return false;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            failure = receipt.detail(); uncertain = true;
        }
        receipt = null;
        return true;
    }

    boolean submit(LocalPlayerContext ctx, String operation, Runnable action, NativeConfirmation confirmation) {
        if (receipt != null || !ctx.mutationAvailable()) return false;
        receipt = ctx.actions().submitControlProtocol(ctx, operation, action, confirmation, 40);
        return true;
    }

    boolean call(LocalPlayerContext ctx, CreateElevatorBridge bridge, Cabin cabin, CallInput input, int floor) {
        var identity = cabin.entity().getUUID();
        if (!ElevatorSurvey.callStillAssociated(ctx, bridge, cabin, floor, input)) {
            failure = "the call input is no longer connected to the selected floor contact"; return false;
        }
        NativeConfirmation confirmation = c -> {
            Cabin live = bridge.find(c, identity);
            return live != null && live.targetY() == floor ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
        };
        if (input.remote()) {
            staging = true;
            if (!preparation.ready(ctx, input.inventorySlot(), this)) { failure = preparation.failure; return false; }
            staging = false;
            if (bridge.remoteChannel(ctx, input.position(), ctx.player().getMainHandItem()) != input.channel()) {
                failure = "the staged remote no longer matches the verified floor receiver"; return false;
            }
            if (!submit(ctx, "create:elevator_call_remote", () -> bridge.remoteInput(input.channel(), true), confirmation)) return false;
            remoteHeld = true; return true;
        }
        BlockHitResult hit = buttonHit(ctx, input.position());
        if (hit == null) return false;
        return submit(ctx, "create:elevator_call_button", () -> {
            if (!(ctx.level().getBlockState(input.position()).getBlock() instanceof ButtonBlock)) throw new IllegalStateException("call button changed");
            var result = ctx.gameMode().useItemOn(ctx.player(), InteractionHand.MAIN_HAND, hit);
            if (result.shouldSwing()) ctx.player().swing(InteractionHand.MAIN_HAND);
        }, confirmation);
    }

    boolean releaseRemote(LocalPlayerContext ctx, CreateElevatorBridge bridge, CallInput input) {
        if (!remoteHeld) return true;
        if (bridge.remoteChannel(ctx, input.position(), ctx.player().getMainHandItem()) != input.channel()) {
            uncertain = true; failure = "remote was moved before its release; native wireless input expires automatically";
            remoteHeld = false; return true;
        }
        if (!submit(ctx, "create:elevator_release_remote", () -> bridge.remoteInput(input.channel(), false),
                c -> NativeConfirmation.Verdict.APPLIED)) return false;
        remoteHeld = false; return false;
    }

    static BlockHitResult buttonHit(LocalPlayerContext ctx, BlockPos button) {
        var state = ctx.level().getBlockState(button);
        if (!(state.getBlock() instanceof ButtonBlock) || state.getValue(BlockStateProperties.POWERED)) return null;
        var shape = state.getShape(ctx.level(), button);
        Vec3 target = shape.isEmpty() ? Vec3.atCenterOf(button) : shape.bounds().getCenter().add(Vec3.atLowerCornerOf(button));
        InputDriver.lookAt(ctx.player(), target);
        Vec3 eye = ctx.player().getEyePosition();
        BlockHitResult hit = ctx.level().clip(new ClipContext(eye,
                eye.add(ctx.player().getViewVector(1F).scale(ctx.player().blockInteractionRange())),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(button) ? hit : null;
    }

    boolean pending() { return receipt != null; }
    boolean pendingInventory() { return preparation.pending(); }
    boolean ownsPreparation() { return preparation.active(); }
    boolean selectHotbar(LocalPlayerContext ctx, int slot) {
        if (receipt != null || !ctx.mutationAvailable()) return false;
        receipt = ctx.actions().selectHotbar(ctx, slot, 30); return true;
    }
    boolean ownsInventory(LocalPlayerContext ctx) { return preparation.ownsScreen(ctx); }
    boolean inventoryChanged() { return preparation.changed; }
    boolean cleanup(LocalPlayerContext ctx) {
        staging = true;
        boolean done = preparation.cleanup(ctx, this);
        uncertain |= preparation.uncertain;
        if (done) { staging = false; ctx.body().releaseAll(); }
        return done;
    }
    void abandon() { receipt = null; }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.act.ToolSelect;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 侧袋挖掘、打火石点火与左键扑火共用原生操作回执，始终瞄准实际可见的那一格。 */
final class DiscardBlockAction {
    enum Kind { EXCAVATE, IGNITE, EXTINGUISH }
    final BlockPos target;
    private final Kind kind;
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate view = new ActualViewConvergenceGate();
    private NativeActionReceipt receipt;
    private int slot = -1;
    private long started = -1;
    private String failure;
    private Vec3 gaze;

    DiscardBlockAction(BlockPos target, Kind kind) { this.target = target.immutable(); this.kind = kind; }
    TaskState tick(LocalPlayerContext context) {
        var player = context.player(); long now = context.level().getGameTime();
        context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        if (receipt != null) {
            if (gaze != null) InputDriver.lookAt(player, gaze);
            receipt = receipt.kind() == NativeActionReceipt.Kind.BREAK_BLOCK
                    ? context.actions().continueBreaking(context, receipt) : context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            return receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED ? TaskState.SUCCESS : failed(receipt.detail());
        }
        if (started < 0) started = now;
        if (now - started > 200) return failed("discard block interaction could not reach its visible target");
        if (!context.level().isLoaded(target)) return failed("discard target unloaded");
        var state = context.level().getBlockState(target);
        if (kind == Kind.EXTINGUISH && !(state.getBlock() instanceof BaseFireBlock)
                || kind == Kind.EXCAVATE && state.isAir()) return TaskState.SUCCESS;
        if (NavigationSafetyContext.protectsMutation(target) || NavigationSafetyContext.protectsUse(target)) return failed("discard target is explicitly protected");
        if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
        if (slot < 0) {
            slot = kind == Kind.IGNITE ? PlayerInv.findSlot(player.getInventory(), Items.FLINT_AND_STEEL)
                    : kind == Kind.EXCAVATE ? ToolSelect.bestSlot(player, state) : emptySlot(player.getInventory());
            // 左键扑火不需要再次使用道具；有空槽先空手，满包时也可原生挥手扑灭，不另丢一件工具腾位置。
            if (slot < 0 && kind != Kind.IGNITE) slot = player.getInventory().selected;
            if (slot < 0 || slot >= 36 && !(kind == Kind.IGNITE && slot == 40)) return failed("discard action tool unavailable in carried inventory");
        }
        var ready = slot == 40 ? FirstPersonActionGate.Status.READY : selection.select(player, slot);
        if (ready == FirstPersonActionGate.Status.FAILED) return failed(selection.failure());
        if (ready != FirstPersonActionGate.Status.READY) return TaskState.RUNNING;
        BlockPos clicked = kind == Kind.IGNITE ? target.below() : target;
        Vec3 aim = kind == Kind.IGNITE ? Vec3.atLowerCornerOf(clicked).add(.5, 1, .5)
                : kind == Kind.EXTINGUISH ? Vec3.atLowerCornerOf(target).add(.5, .03, .5) : visibleFace(context, target);
        if (aim == null) return failed("discard target has no reachable face");
        gaze = aim;
        InputDriver.lookAt(player, aim);
        if (!view.ready(player, aim.subtract(player.getEyePosition())) || !context.mutationAvailable()) return TaskState.RUNNING;
        var ray = Interaction.nativeRaytrace(player, player.blockInteractionRange());
        if (!(ray instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(clicked)
                || kind == Kind.IGNITE && hit.getDirection() != Direction.UP) return TaskState.RUNNING;
        if (kind == Kind.IGNITE) {
            // 打火石也可能握在副手，原生使用对应手别，不为点火擅自丢掉副手物品。
            InteractionHand hand = slot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            if (!player.getItemInHand(hand).is(Items.FLINT_AND_STEEL)) return failed("flint and steel changed before ignition");
            receipt = context.actions().useBlock(context, hand, hit, new NativeConfirmation() {
                public boolean requiresBlockAcknowledgement() { return true; }
                public Verdict observe(LocalPlayerContext current) {
                    return current.level().getBlockState(target).getBlock() instanceof BaseFireBlock ? Verdict.APPLIED : Verdict.PENDING;
                }
            }, 80);
        } else receipt = context.actions().startBreaking(context, hit, kind == Kind.EXTINGUISH ? 80 : 600);
        return TaskState.RUNNING;
    }
    private static int emptySlot(Inventory inventory) {
        for (int slot = 0; slot < 36; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }
    private static Vec3 visibleFace(LocalPlayerContext context, BlockPos target) {
        var player = context.player();
        for (Direction face : Direction.values()) {
            Vec3 aim = Vec3.atCenterOf(target).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499));
            if (aim.distanceToSqr(player.getEyePosition()) > player.blockInteractionRange() * player.blockInteractionRange()) continue;
            var hit = context.level().clip(new ClipContext(player.getEyePosition(), aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return aim;
        }
        return null;
    }
    private TaskState failed(String reason) { failure = reason; return TaskState.FAILED; }
    String failure() { return failure; }
    boolean submitted() { return receipt != null; }
    void close(LocalPlayerContext context) {
        if (receipt != null && !receipt.terminal()) {
            if (receipt.kind() == NativeActionReceipt.Kind.BREAK_BLOCK) context.actions().cancelBreakingForTaskBoundary(context, receipt, "discard block work ended");
            else context.actions().retireOneShotForTaskBoundary(context, receipt, "discard ignition ended");
        }
        selection.reset();
    }
}

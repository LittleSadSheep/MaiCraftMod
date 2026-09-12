// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.function.BooleanSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** One aimed block use. Never falls through to throwing an eye, and never retries an uncertain click. */
final class PortalActivation {
    private final Item item;
    private final BlockPos target;
    private final Vec3 aim;
    private final Direction requiredFace;
    private final BooleanSupplier permitted;
    private final BooleanSupplier applied;
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private NativeActionReceipt receipt;
    private long firstTick = -1;
    private String failure;

    PortalActivation(Item item, BlockPos target, Vec3 aim, Direction requiredFace,
                     BooleanSupplier permitted, BooleanSupplier applied) {
        this.item = item; this.target = target.immutable(); this.aim = aim;
        this.requiredFace = requiredFace; this.permitted = permitted; this.applied = applied;
    }

    TaskState tick(LocalPlayer player) {
        var context = ClientRuntime.requireContext(player);
        InputDriver.halt(player);
        if (failure != null) return TaskState.FAILED;
        if (receipt != null) {
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            return receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                    ? TaskState.SUCCESS : reject("portal activation was not confirmed: " + receipt.detail());
        }
        if (!permitted.getAsBoolean() || NavigationSafetyContext.protectsUse(target))
            return reject("the portal changed, unloaded or became protected before activation");
        long now = player.level().getGameTime();
        if (firstTick < 0) firstTick = now;
        if (now - firstTick > 200) return reject("could not aim at the permitted portal face");
        var selected = selection.select(player, PlayerInv.findSlot(player.getInventory(), item));
        if (selected == FirstPersonActionGate.Status.FAILED) return reject(selection.failure());
        if (selected != FirstPersonActionGate.Status.READY) return TaskState.RUNNING;
        if (!player.getMainHandItem().is(item)) return reject("the activation item changed before use");
        InputDriver.lookAt(player, aim);
        if (now == firstTick || player.getViewVector(1).dot(aim.subtract(player.getEyePosition()).normalize()) < .9995)
            return TaskState.RUNNING;
        var ray = Interaction.nativeRaytrace(player, player.blockInteractionRange());
        if (!(ray instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)
                || requiredFace != null && hit.getDirection() != requiredFace)
            return reject("the native crosshair does not hit the permitted portal face");
        if (!context.permitsNativeActions() || !context.mutationAvailable()) return TaskState.RUNNING;
        receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, new NativeConfirmation() {
            @Override public boolean requiresBlockAcknowledgement() { return true; }
            @Override public Verdict observe(org.maiwithu.maicraft.client.actor.LocalPlayerContext current) {
                return applied.getAsBoolean() ? Verdict.APPLIED : Verdict.PENDING;
            }
        }, 100);
        return TaskState.RUNNING;
    }

    private TaskState reject(String message) { failure = message; return TaskState.FAILED; }
    String failure() { return failure; }

    void close(LocalPlayer player) {
        if (receipt != null && !receipt.terminal()) {
            ClientRuntime.actor().activeContext().filter(c -> c.player() == player).ifPresent(c ->
                    c.actions().retireOneShotForTaskBoundary(c, receipt, "portal preparation stopped"));
        }
        selection.reset();
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 站在选定格里低头，用随身打火石或火焰弹对脚下支撑面点一次原生火；只等这次回执和真实火格，不重复同一次点击。 */
final class SuicideIgnition {
    // 打火石有耐久可反复点火，排在前面；只有没有打火石时才消耗火焰弹。
    static final List<Item> IGNITERS = List.of(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
    private final BlockPos cell;
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate view = new ActualViewConvergenceGate();
    private NativeActionReceipt receipt;
    private int slot = -1;
    private long started = -1;
    private String failure;

    SuicideIgnition(BlockPos cell) { this.cell = cell.immutable(); }

    static int igniterSlot(Inventory inventory) {
        // 只找背包、快捷栏和副手里的点火物；副手已握着就直接用副手，不为点火挪动主手物品。
        for (Item item : IGNITERS) {
            if (inventory.getItem(Inventory.SLOT_OFFHAND).is(item)) return Inventory.SLOT_OFFHAND;
            for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) if (inventory.getItem(index).is(item)) return index;
        }
        return -1;
    }

    TaskState tick(LocalPlayerContext context) {
        var player = context.player(); long now = context.level().getGameTime();
        // 点火期间站定不动：先选物品 -> 低头对准脚下支撑面顶部 -> 准星确认后右键一次 -> 等服务端确认真实火格。
        context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        if (receipt != null) {
            InputDriver.lookAt(player, aim());
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            return receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                    ? TaskState.SUCCESS : failed("native ignition was not confirmed: " + receipt.detail());
        }
        if (started < 0) started = now;
        if (now - started > 200) return failed("could not aim the igniter at the floor below the chosen cell");
        if (NavigationSafetyContext.protectsMutation(cell) || NavigationSafetyContext.protectsUse(cell.below()))
            return failed("the ignition cell or its floor is explicitly protected");
        if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
        if (slot < 0 && (slot = igniterSlot(player.getInventory())) < 0)
            return failed("no flint and steel or fire charge remains in the carried inventory");
        var ready = slot == Inventory.SLOT_OFFHAND ? FirstPersonActionGate.Status.READY : selection.select(context, player, slot);
        if (ready == FirstPersonActionGate.Status.FAILED) return failed(selection.failure());
        if (ready != FirstPersonActionGate.Status.READY) return TaskState.RUNNING;
        InteractionHand hand = slot == Inventory.SLOT_OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (!igniter(player.getItemInHand(hand))) return failed("the igniter changed before use");
        Vec3 aim = aim();
        InputDriver.lookAt(player, aim);
        if (!view.ready(player, aim.subtract(player.getEyePosition())) || !context.permitsNativeActions() || !context.mutationAvailable())
            return TaskState.RUNNING;
        // 原生准星必须正好落在脚下支撑方块的顶面，火才会生在角色所站的格子里；没对上就继续转头等待，不盲点。
        var ray = Interaction.nativeRaytrace(player, player.blockInteractionRange());
        if (!(ray instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(cell.below()) || hit.getDirection() != Direction.UP) return TaskState.RUNNING;
        receipt = context.actions().useBlock(context, hand, hit, new NativeConfirmation() {
            @Override public boolean requiresBlockAcknowledgement() { return true; }
            @Override public Verdict observe(LocalPlayerContext current) {
                return SuicideHazards.burning(current.level(), cell) ? Verdict.APPLIED : Verdict.PENDING;
            }
        }, 80);
        return TaskState.RUNNING;
    }

    static boolean igniter(ItemStack stack) { return IGNITERS.stream().anyMatch(stack::is); }
    // 视线落点是支撑面顶部中心，也就是选定格的底面中心；角色站在格内时射线全程留在这一列。
    private Vec3 aim() { return Vec3.atBottomCenterOf(cell); }
    private TaskState failed(String reason) { failure = reason; return TaskState.FAILED; }
    String failure() { return failure; }
    // 已按下右键却没拿到确认，说明原生交互拒绝或结果未知；调用方据此不再换格反复点火。
    boolean submitted() { return receipt != null; }

    void close(LocalPlayer player) {
        // 收场只终止等待并保留未知结果，不能把已经发出的点火描述成已撤销。
        if (receipt != null && !receipt.terminal()) {
            ClientRuntime.actor().activeContext().filter(context -> context.player() == player).ifPresent(context ->
                    context.actions().retireOneShotForTaskBoundary(context, receipt, "suicide ignition ended"));
        }
        selection.reset();
    }
}

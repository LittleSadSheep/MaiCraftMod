// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 回放边走边插灯的资源冲突：旧副手回执不能占住主任务，换身体后也不能继续提交。 */
public final class AuxiliaryActionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var context = ClientRuntime.requireContext(h.player);
            var hit = new BlockHitResult(new Vec3(2.5, 1, 2.5), Direction.UP, new BlockPos(2, 0, 2), false);
            var waiting = context.actions().tryAuxiliaryBlockUse(context, hit, c -> NativeConfirmation.Verdict.PENDING, 40);
            check(waiting != null && !waiting.terminal() && h.blockUses() == 1, "副手只提交一次");
            check(context.actions().tryAuxiliaryBlockUse(context, hit, c -> NativeConfirmation.Verdict.PENDING, 40) == null,
                    "同刻没有第二次操作机会");
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            var main = context.actions().selectHotbar(context, 1, 10);
            check(main != null && !context.actions().poll(context, waiting).terminal(), "主手选工具时保留独立副手回执");
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            check(context.actions().tryAuxiliaryBlockUse(context, hit, c -> NativeConfirmation.Verdict.PENDING, 40) == null,
                    "旧灯未确认不能再次消耗");
            ((DefaultNativeActionPort) context.actions()).revokeForBoundary("test handoff");
            check(waiting.status() == NativeActionReceipt.Status.UNCERTAIN, "交还身体保留未知效果");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.enableInventoryTransactions(true);
            h.h.minecraft.screen = null;
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            h.inventory.setItem(12, new ItemStack(Items.TORCH, 64));
            h.inventory.setItem(40, new ItemStack(Items.SHIELD));
            h.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            var context = ClientRuntime.requireContext(h.player);
            var swap = context.menus().swapInventoryToOffhand(context, 12, 20);
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            check(context.menus().poll(context, swap).status() == MenuReceipt.Status.CONFIRMED_APPLIED,
                    "服务器同步确认整叠火把进入副手");
            check(h.inventory.getItem(12).is(Items.SHIELD) && h.player.getOffhandItem().getCount() == 64
                    && h.player.getMainHandItem().is(Items.IRON_PICKAXE) && context.minecraft().screen == null,
                    "副手旧物保留，主手镐与世界画面不变");
        }
        // 镜头右转九十度时原前进改成横移，世界行进方向保持一致；跳跃与潜行意图不能丢。
        var movement = new BodyControlPort.Movement(1, 0, false, false, true);
        var compensated = DefaultBodyControlPort.preserveHeading(movement, 0, 90);
        check(Math.abs(compensated.forward()) < .0001 && Math.abs(compensated.strafe() - 1) < .0001,
                "插灯转头不改变世界行进方向");
        try (var h = new InteractionWorldTestHarness()) {
            var context = ClientRuntime.requireContext(h.player);
            context.body().requestLook(0, 0, context.tickRevision());
            check(!context.body().tryAuxiliaryLook(90, 80, context.tickRevision()), "精确主动作瞄准优先");
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            context.body().applyMovement(movement, context.tickRevision());
            context.body().requestNavigationLook(0, 0, context.tickRevision());
            check(context.body().tryAuxiliaryLook(90, 80, context.tickRevision()), "普通导航允许随行插灯");
        }
        System.out.println("AuxiliaryActionTest: passed");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

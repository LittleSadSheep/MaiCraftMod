// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.lighting.OffhandTorchPlacer;
import org.maiwithu.maicraft.core.task.lighting.RoutineTorchPlacement;

/** 原生动作槽未让出时补光必须归还镜头；已经出手也不能继续占着主任务的准星目标。 */
public final class AuxiliaryLookReturnTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            h.inventory.setItem(40, new ItemStack(Items.TORCH, 8));
            var context = ClientRuntime.requireContext(h.player);
            var hit = new BlockHitResult(new Vec3(2.5, 1, 2.5), Direction.UP, new BlockPos(2, 0, 2), false);
            context.actions().tryAuxiliaryBlockUse(context, hit, c -> NativeConfirmation.Verdict.PENDING, 40);
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            context.body().applyNavigationMovement(new BodyControlPort.Movement(1, 0, false, false, true), context.tickRevision());
            context.body().requestNavigationLook(0, 8, context.tickRevision());
            float yaw = h.player.getYRot(), pitch = h.player.getXRot();
            var placer = new OffhandTorchPlacer();
            check(!placer.place(context, RoutineTorchPlacement.find(h.player, Set.of()), Set.of()), "旧副手回执尚未结清，不能发第二次点击");
            check(h.player.getYRot() == yaw && h.player.getXRot() == pitch, "没有放置却留下补光改过的镜头");
            check(h.blockUses() == 1 && h.player.getOffhandItem().getCount() == 8, "未提交的尝试不消耗火把");
            context.body().requestNavigationLook(25, 8, context.tickRevision());
            check(targetYaw(context) == 25, "失败借用之后主任务仍能续订路线视角");
        }
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            h.inventory.setItem(40, new ItemStack(Items.TORCH, 8));
            var context = ClientRuntime.requireContext(h.player);
            context.body().requestNavigationLook(0, 8, context.tickRevision());
            var placer = new OffhandTorchPlacer();
            check(placer.place(context, RoutineTorchPlacement.find(h.player, Set.of()), Set.of()), "借用真实准星提交一支火把");
            check(targetYaw(context) == 0, "放置提交后立即恢复原路线的镜头目标");
            context.body().requestNavigationLook(35, 8, context.tickRevision());
            check(targetYaw(context) == 35 && h.blockUses() == 1, "等待放置回执时不占用后继转向");
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            check(context.body().tryAuxiliaryLook(90, 60, context.tickRevision()), "新刻允许一次短暂借用");
            context.body().requestLook(120, 10, context.tickRevision());
            context.body().finishAuxiliaryLook(false, context.tickRevision());
            check(targetYaw(context) == 120, "主动作接手后的迟到辅助收尾不能覆盖新的瞄准");
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            context.body().applyNavigationMovement(new BodyControlPort.Movement(1, 0, true, false, true), context.tickRevision());
            check(!context.body().auxiliaryLookAvailable(context.tickRevision()), "尚未离地的起跳准备也拥有身体");
            context.body().applyNavigationMovement(new BodyControlPort.Movement(1, 0, false, true, false), context.tickRevision());
            check(!context.body().auxiliaryLookAvailable(context.tickRevision()), "沿边缘潜行时不借准星插灯");
        }
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            for (float yaw : new float[]{0, 90, -135}) {
                h.player.setYRot(yaw); h.player.setXRot(8);
                var target = RoutineTorchPlacement.find(h.player, Set.of());
                var direction = Vec3.atCenterOf(target.pos()).subtract(h.player.position()).multiply(1, 0, 1).normalize();
                var forward = h.player.getViewVector(1).multiply(1, 0, 1).normalize();
                check(direction.dot(forward) > .95, "平地直走和斜走都优先选前方灯位，不能按固定顺序回头找地面");
            }
        }
        System.out.println("AuxiliaryLookReturnTest: passed");
    }

    private static float targetYaw(LocalPlayerContext context) throws Exception {
        return (Float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetYaw").get(context.body());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

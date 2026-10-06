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
            // 撤回未提交的补光转向后，下一刻主任务仍能按原路线续订视角，不会被补光的试探占住准星。
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            h.renderFrames(5);
            context.body().requestNavigationLook(25, 8, context.tickRevision());
            check(targetYaw(context) == 25, "失败借用之后主任务仍能续订路线视角");
        }
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            h.inventory.setItem(40, new ItemStack(Items.TORCH, 8));
            var context = ClientRuntime.requireContext(h.player);
            context.body().requestNavigationLook(0, 8, context.tickRevision());
            var placer = new OffhandTorchPlacer();
            var candidate = RoutineTorchPlacement.find(h.player, Set.of());
            float before = h.player.getXRot();
            check(!placer.place(context, candidate, Set.of()), "第一刻只登记补光转向，还不能提交点击");
            h.renderFrames(1);
            check(Math.abs(h.player.getXRot() - before) < 12, "补光转向每帧只走正常转头的一小步");
            // 补光的借还流程要等镜头真的转到灯位；测试按真实渲染帧推进，不用瞬转迁就。
            boolean submitted = placeWhileTurning(h, placer, 0, 8);
            context = ClientRuntime.requireContext(h.player);
            check(submitted && h.blockUses() == 1, "镜头平滑转到灯位后才提交一支火把");
            check(targetYaw(context) == 0, "放置提交后立即恢复原路线的镜头目标");
            // 出手这一帧仍按原有优先级算作精确瞄准，下一帧导航照常拿回视角。
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            context.body().requestNavigationLook(35, 8, context.tickRevision());
            check(targetYaw(context) == 35, "等待放置回执时不占用后继转向");
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

    /**
     * 按真实渲染帧推进补光转向，直到那一支火把真的提交，每刻都像真实帧一样先续订路线视角。
     * 灯位每刻重新挑选：补光转头后视线对齐会变化，测试不能拿一个过期候选去要求它对准。
     * 帧数上限同时防止对准逻辑卡死时把测试无限挂住。
     */
    static boolean placeWhileTurning(InteractionWorldTestHarness h, OffhandTorchPlacer placer,
                                     float routeYaw, float routePitch) throws Exception {
        int guard = 0;
        boolean placed;
        do {
            h.nextTick();
            var context = ClientRuntime.requireContext(h.player);
            context.body().requestNavigationLook(routeYaw, routePitch, context.tickRevision());
            // 真实帧序：任务先借准星登记转向，渲染帧才推进镜头；反过来会让镜头在这一帧转回路线。
            placed = placer.place(context, RoutineTorchPlacement.find(h.player, Set.of()), Set.of());
            h.renderFrames(1);
        } while (!placed && guard++ < 200);
        return placer.pending();
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

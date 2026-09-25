// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 邻近现有或待建的收料口时，角色仍应进入临时支撑施工，不能因预计清理掉落而停工。 */
public final class BuildScaffoldNearMachineTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        preparesSupportBesideIntake(false);
        preparesSupportBesideIntake(true);
        System.out.println("BuildScaffoldNearMachineTest: existing and planned intakes allow support construction");
    }

    private static void preparesSupportBesideIntake(boolean existing) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 角色站在悬空目标旁，背包带足垫块；侧面的漏斗分别模拟已建机器和尚未施工的蓝图目标。
            Field dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
            dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F));
            h.position(new Vec3(3.5, 1, 6.5)); h.player.setDeltaMovement(Vec3.ZERO);
            h.inventory.setItem(0, new ItemStack(Items.DIRT, 64));
            var target = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                    new BlockPos(6, 2, 6), "raised machine", null, null, null);
            var intake = new BuildTaskRecord.Target(Blocks.HOPPER, Items.HOPPER,
                    new BlockPos(7, 1, 6), "machine intake", null, null, null);
            if (existing) h.set(intake.pos(), intake.desiredState());
            var record = new BuildTaskRecord("support-near-machine", 1000,
                    existing ? List.of(target) : List.of(target, intake), false);
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            BlockPos support = target.pos().below();
            check(task.permitsTemporaryScaffold(support), "navigation may scaffold beside the intake");

            // 从真实施工器的支撑准备入口推进到可执行队列，确认原来的掉落风险拒绝不再截断这次施工。
            var type = Class.forName(task.getClass().getName() + "$CellPlan");
            var constructor = type.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
            Object cell = constructor.newInstance(target, List.of());
            field("cell").set(task, cell); field("queue").set(task, new ArrayList<>(List.of(cell)));
            check(invoke(task, "prepareTemporarySupports") == TaskState.RUNNING, "the builder prepares a support chain");
            check(field("supportChain").get(task).equals(List.of(support)), "the nearest support remains usable beside the intake");
            for (int i = 0; i < 4096 && field("phase").get(task).toString().equals("SUPPORT_VERIFY"); i++) {
                h.nextTick();
                check(invoke(task, "supportVerifyTick") == TaskState.RUNNING, "support preparation reaches placement without a debris veto");
            }
            check(field("phase").get(task).toString().equals("SELECT")
                            && ((List<?>) field("queue").get(task)).size() == 2,
                    "the builder queues the support before the original target");
            check(record.placed() == 0 && record.scaffoldLedger().isEmpty() && h.blockUses() == 0,
                    "preparing the support still requires real placement before claiming progress");
        }
    }

    // 测试只驱动支撑准备阶段，施工是否完成仍由原生放置回执决定。
    private static Object invoke(Object task, String name) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static Field field(String name) throws Exception {
        Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

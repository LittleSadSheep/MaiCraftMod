// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** 支撑按接地顺序进入普通施工；排队不修改身体、方块或库存，也不等待额外的路径证明。 */
public final class BuildSupportSchedulingTest {
    private static final BlockPos TARGET = new BlockPos(8, 1, 6), SUPPORT = TARGET.west();
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 刚走下台阶也先安排真实的下一块支撑；身体移动与放置由普通施工动作处理，不额外等整链证明。
        try (var h = world()) {
            Vec3 before = h.player.position(), velocity = h.player.getDeltaMovement();
            var task = prepared(h);
            check(field("phase").get(task).toString().equals("SELECT")
                            && ((Map<?, ?>) field("temporaryTargets").get(task)).size() == 1,
                    "support preparation immediately queues ordinary placement");
            check(h.player.position().equals(before) && h.player.getDeltaMovement().equals(velocity)
                            && h.blockUses() == 0 && h.itemUses() == 0,
                    "queueing neither teleports the body nor claims an unperformed placement");
        }
        System.out.println("BuildSupportSchedulingTest: passed");
    }
    private static FirstPersonBuildCompanionTask prepared(InteractionWorldTestHarness h) throws Exception {
        var target = target(); var record = new BuildTaskRecord("settle-support", 1000, List.of(target), false, true);
        record.executionGuards(List.of(), p -> true, (p, at) -> at.equals(SUPPORT), (p, at) -> {});
        var task = new FirstPersonBuildCompanionTask(h.player, record);
        var type = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
        var constructor = type.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
        Object cell = constructor.newInstance(target, List.of()); field("cell").set(task, cell); field("queue").set(task, new ArrayList<>(List.of(cell)));
        check(invoke(task, "prepareTemporarySupports") == TaskState.RUNNING
                && ((List<?>) field("supportChain").get(task)).equals(List.of(SUPPORT)), "the ordinary bounded support planner selects only the original allowed west cell");
        return task;
    }
    private static BuildTaskRecord.Target target() { return new BuildTaskRecord.Target(Blocks.SPRUCE_FENCE.defaultBlockState(), Items.SPRUCE_FENCE,
            TARGET, "fence", null, null, null, false, Set.of(), true, Set.of()); }
    private static InteractionWorldTestHarness world() throws Exception {
        var h = new InteractionWorldTestHarness(); Field dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F)); h.position(new Vec3(4.2, 1, 6.44736837948905));
        h.player.setDeltaMovement(new Vec3(0, -.0784, 0)); h.inventory.setItem(0, new ItemStack(Items.DIRT, 16)); return h;
    }
    private static Object invoke(Object task, String name) throws Exception { Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task); }
    private static Field field(String name) throws Exception { Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

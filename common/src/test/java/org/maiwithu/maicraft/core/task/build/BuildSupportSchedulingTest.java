// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
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
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.task.TaskState;

/** 回放下台阶后的真实身体样本，通过完整施工阶段等待再验证；位置只由夹具注入，生产调度不得修改身体或偷放支撑。 */
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
    private static void landedBodyProducesOneFreshProof() throws Exception {
        try (var h = world()) {
            var task = prepared(h); var samples = BuildSupportSettlingTest.samples();
            for (int i = 0; i < samples.length; i++) {
                double[] value = samples[i];
                observeBody(h, new Vec3(value[0] + 12, value[1] - 71, 6.44736837948905),
                        new Vec3(value[2], -.0784000015258789, 0), value[3] == 1);
                Vec3 before = h.player.position(), speed = h.player.getDeltaMovement();
                check(invoke(task, "supportVerifyTick") == TaskState.RUNNING, "inertia cannot turn into a no-path failure");
                check(h.player.position().equals(before) && h.player.getDeltaMovement().equals(speed), "settling cannot teleport or zero native velocity");
                if (i < samples.length - 1) check(field("supportAccess").get(task) == null && field("phase").get(task).toString().equals("SUPPORT_VERIFY"),
                        "do not freeze a support origin while the body is still falling or sliding");
            }
            finish(h, task);
            check(field("phase").get(task).toString().equals("SELECT") && field("supportBodyReproofs").getInt(task) == 0
                    && ((Map<?, ?>) field("temporaryTargets").get(task)).size() == 1,
                    "once naturally stable, one new proof admits the original one-support proposal");
            check(h.level.getBlockState(SUPPORT).isAir() && h.level.getBlockState(TARGET).isAir()
                    && h.inventory.getItem(0).getCount() == 16 && h.blockUses() == 0 && h.itemUses() == 0,
                    "proof scheduling neither submits a support nor consumes its item");
        }
    }
    private static void onlyBodyInvalidationMayReprove(boolean changeWorld) throws Exception {
        try (var h = world()) {
            var task = prepared(h);
            var target = target();
            var stale = new BuildSupportAccess(h.player, target, List.of(SUPPORT), Blocks.DIRT.defaultBlockState(),
                    SUPPORT::equals, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY);
            for (int i = 0; i < 4096 && !stale.advance(16); i++) { }
            check(stale.accepted(), "the initial frozen origin has a genuinely reachable placement witness");
            field("supportAccess").set(task, stale);
            observeBody(h, h.player.position().add(.2, 0, 0), new Vec3(0, -.0784, 0), true);
            if (changeWorld) h.set(SUPPORT, Blocks.STONE.defaultBlockState());
            TaskState result = (TaskState) invoke(task, "supportVerifyTick");
            if (changeWorld) {
                check(result == TaskState.FAILED && field("supportBodyReproofs").getInt(task) == 0
                        && stale.invalidationReason().equals("support_projection_site_changed"),
                        "world/support change takes priority over a simultaneous body shift and cannot reuse the old witness");
            } else {
                check(result == TaskState.RUNNING && field("supportAccess").get(task) == null && field("supportBodyReproofs").getInt(task) == 1,
                        "body-only invalidation discards the old proof and waits again without submitting anything");
                finish(h, task);
                check(field("phase").get(task).toString().equals("SELECT") && field("supportAccess").get(task) != stale,
                        "resumption requires a complete new proof from the newly stable position");
            }
            check(h.blockUses() == 0 && h.itemUses() == 0, "no rejection or reproof may create a native click");
        }
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
    private static void selectingASupportRetainsItsPrerequisite() throws Exception {
        try (var h = world()) {
            var task = prepared(h); finish(h, task);
            var proven = (BuildSupportAccess) field("supportAccess").get(task);
            check(proven.accepted() && proven.placementFor(SUPPORT) != null, "proposal first proves the support's actual reachable placement");
            // 重放 SUPPORT_VERIFY -> SELECT -> 支撑格的真实交接；不能在重置普通格状态时丢掉已经验证的路线。
            check(invoke(task, "selectTick") == TaskState.RUNNING && field("supportAccess").get(task) == proven
                            && !field("supportStepApproved").getBoolean(task),
                    "selecting a temporary support preserves its movement witness but does not preapprove its click");
            h.set(SUPPORT, Blocks.STONE.defaultBlockState());
            check(invoke(task, "aimTick") == TaskState.RUNNING && field("supportAccess").get(task) == null,
                    "the click boundary discards the old observation and requests a new proof");
            TaskState state=TaskState.RUNNING;
            for(int i=0;i<64 && state==TaskState.RUNNING;i++) { h.nextTick();state=(TaskState)invoke(task,"supportVerifyTick"); }
            check(state==TaskState.FAILED && h.level.getBlockState(SUPPORT).is(Blocks.STONE)
                            && h.inventory.getItem(0).getCount()==16 && h.blockUses()==0 && h.itemUses()==0,
                    "a changed support site is rejected without replacing it or consuming the retained material");
        }
    }
    private static BuildTaskRecord.Target target() { return new BuildTaskRecord.Target(Blocks.SPRUCE_FENCE.defaultBlockState(), Items.SPRUCE_FENCE,
            TARGET, "fence", null, null, null, false, Set.of(), true, Set.of()); }
    private static InteractionWorldTestHarness world() throws Exception {
        var h = new InteractionWorldTestHarness(); Field dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F)); h.position(new Vec3(4.2, 1, 6.44736837948905));
        h.player.setDeltaMovement(new Vec3(0, -.0784, 0)); h.inventory.setItem(0, new ItemStack(Items.DIRT, 16)); return h;
    }
    private static void observeBody(InteractionWorldTestHarness h, Vec3 position, Vec3 velocity, boolean grounded) throws Exception {
        h.position(position); h.player.setDeltaMovement(velocity); Field field = Entity.class.getDeclaredField("onGround"); field.setAccessible(true); field.setBoolean(h.player, grounded); h.nextTick();
    }
    private static void finish(InteractionWorldTestHarness h, FirstPersonBuildCompanionTask task) throws Exception {
        for (int i = 0; i < 4096 && field("phase").get(task).toString().equals("SUPPORT_VERIFY"); i++) {
            h.nextTick(); check(invoke(task, "supportVerifyTick") == TaskState.RUNNING, "stable support validation must complete without a blind retry");
        }
        check(!field("phase").get(task).toString().equals("SUPPORT_VERIFY"), "support validation must finish its fixed work budget");
    }
    private static Object invoke(Object task, String name) throws Exception { Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task); }
    private static Field field(String name) throws Exception { Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

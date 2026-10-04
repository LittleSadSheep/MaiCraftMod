// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 瞄准遮挡时的自动换站位重试：接近型交互在准星被挡后先自动换一个站位重试一次，
 * 不再把"换到开阔侧"留给拿不到内部坐标的调用方；非接近目标与第二次遮挡不触发。
 */
public final class OccludedStanceRetryTest {
    /** 整格碰撞的目标：射线判定按真实几何走，薄片按钮会从贴面旁穿过去。 */
    private static final BlockPos TARGET = new BlockPos(3, 2, 3);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        blockedAimRetriesAutomatically();
        plainTargetNeverRetries();
        System.out.println("OccludedStanceRetryTest: passed");
    }

    /** 目标可交互距离内但准星被中间方块挡住：重试闸门启动，回执带换位计数而不是立刻失败。 */
    private static void blockedAimRetriesAutomatically() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.set(TARGET, Blocks.SPRUCE_PLANKS.defaultBlockState());
            f.set(new BlockPos(2, 2, 3), Blocks.STONE.defaultBlockState());
            // 假身体补上真实体格：眼高派生自 dimensions，接近型 reached 的可见性判定依赖它。
            classField(LocalPlayer.class, "dimensions").set(f.player, EntityDimensions.scalable(.6F, 1.8F));
            classField(LocalPlayer.class, "eyeHeight").setFloat(f.player, 1.62F);
            f.player.setDeltaMovement(Vec3.ZERO);
            var task = new InteractAtCompanionTask(f.player, record(f, "occluded-retry"));
            // 只驱动 act 的瞄准阶段（先行测试的同一做法），不进任务级 tick——
            // 导航执行需要完整运行时，这里验证的是遮挡后的重试决策与回执。
            check(act(task) == TaskState.RUNNING, "the first aim waits for the camera before judging the crosshair");
            aimThroughField(task, f);
            f.nextTick();
            check(act(task) == TaskState.RUNNING, "an occluded aim starts a stance retry instead of failing");
            check(boolField(task, "stanceRetryUsed"), "the retry is bounded to one attempt");
            check(boolField(task, "forcingNewStance"), "the retry drives its own approach navigation");
            check(intField(task, "stanceRetries") == 1, "exactly one retry is booked");

            // 同一遮挡不得触发第二次重试；直接再问一次重试入口验证闸门。
            var retry = InteractAtCompanionTask.class.getDeclaredMethod("retryFromDifferentStance", String.class);
            retry.setAccessible(true);
            check(Boolean.FALSE.equals(retry.invoke(task, "second occlusion")), "a second occlusion cannot retry again");

            Map<?, ?> approach = approach(task.result(TaskState.FAILED).data());
            check(((Number) approach.get("stance_retries")).intValue() == 1, "the receipt reports the stance retry count");
            check(((Number) approach.get("rejected_stances")).intValue() >= 1,
                    "the blocked stance is rejected for later stance selection");
            check(approach.containsKey("rejected_descents"), "the receipt carries the descent admission section");
        }
    }

    /** 没有接近目标的普通交互不换位：原地遮挡如实失败，由调用方决定重试。 */
    private static void plainTargetNeverRetries() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.set(TARGET, Blocks.SPRUCE_PLANKS.defaultBlockState());
            f.set(new BlockPos(2, 2, 3), Blocks.STONE.defaultBlockState());
            var task = new InteractAtCompanionTask(f.player, new InteractAtTaskRecord("plain-occluded",
                    f.player.level().getGameTime() + 400, MouseButton.RIGHT, TARGET, 0, null));
            var retry = InteractAtCompanionTask.class.getDeclaredMethod("retryFromDifferentStance", String.class);
            retry.setAccessible(true);
            check(Boolean.FALSE.equals(retry.invoke(task, "no approach target")),
                    "a without-approach interaction never repositions by itself");
        }
    }

    private static InteractAtTaskRecord record(InteractionWorldTestHarness f, String callId) {
        return new InteractAtTaskRecord(callId, f.player.level().getGameTime() + 400,
                MouseButton.RIGHT, TARGET, 0, null).withApproach(false);
    }

    /** 收敛门要求镜头真实对准目标：按任务挑好的瞄准点直接摆好视线，跨过等待镜头的刻。 */
    private static void aimThroughField(InteractAtCompanionTask task, InteractionWorldTestHarness f) throws Exception {
        Vec3 point = (Vec3) objectField(task, "aimPoint");
        if (point == null) point = Vec3.atCenterOf(TARGET);
        Vec3 delta = point.subtract(f.player.getEyePosition());
        float yaw = (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0f;
        float horizontal = (float) Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float pitch = (float) -(Math.atan2(delta.y, horizontal) * (180.0 / Math.PI));
        f.player.setYRot(yaw); f.player.setXRot(pitch);
        f.player.yRotO = yaw; f.player.xRotO = pitch;
    }

    private static Object act(InteractAtCompanionTask task) throws Exception {
        var act = InteractAtCompanionTask.class.getDeclaredMethod("act");
        act.setAccessible(true);
        return act.invoke(task);
    }

    private static Map<?, ?> approach(Map<String, Object> data) {
        var section = (Map<?, ?>) data.get("interaction_approach");
        if (section == null) throw new AssertionError("the receipt must describe the approach: " + data);
        return section;
    }

    private static Object objectField(Object owner, String name) throws Exception {
        return declaredField(owner, name).get(owner);
    }

    private static boolean boolField(Object owner, String name) throws Exception {
        return declaredField(owner, name).getBoolean(owner);
    }

    private static int intField(Object owner, String name) throws Exception {
        return declaredField(owner, name).getInt(owner);
    }

    private static Field declaredField(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** 身体字段分布在实体继承链上：沿父类查找，找不到再报缺字段。 */
    private static Field classField(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
            try {
                var field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}

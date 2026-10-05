// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchCompanionTask;
import org.maiwithu.maicraft.core.task.structure.PhysicalStructureSearchTaskRecord;

/**
 * 投眼引导的四个实机卡点：站姿不合格先有界换位再失败；眼实体已确认而消耗不可证
 * （创造模式或自返眼被拾回）时声明后继续跟踪而不中止；死亡或任务重启后沿上次
 * 掷出的方向免掷续走一段且续走必须先建立移动腿；主动路线腿丢失时先有界重建一次，
 * 再丢才终态失败并携带复位证据。
 */
public final class EnderEyeGuidanceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var w = new InteractionWorldTestHarness()) {
            // 平台 y=4，玩家脚位 y=5；眼位由具体场景布置。
            for (int x = 1; x <= 10; x++) for (int z = 1; z <= 10; z++)
                w.set(new BlockPos(x, 4, z), Blocks.STONE.defaultBlockState());
            var player = w.player;

            unsafeStanceRelocatesBeforeFailing(w);
            unconfirmedConsumptionContinuesTracking(w);
            rememberedDirectionResumesWithoutThrow(w);
            lostRouteRecoversOnceThenFailsHonest(w);
        }
        System.out.println("EnderEyeGuidanceTest: passed");
    }

    /** 点 1：站姿不合格（脚/头在水里）先发起有界换位；换位移动超时则按原站姿话术失败。 */
    private static void unsafeStanceRelocatesBeforeFailing(InteractionWorldTestHarness w) throws Exception {
        var task = newTask(w);
        BlockPos feet = new BlockPos(5, 5, 5);
        w.set(feet, Blocks.WATER.defaultBlockState());
        w.set(feet.above(), Blocks.WATER.defaultBlockState());
        w.position(new Vec3(5.5, 5, 5.5));
        w.nextTick();
        Method relocate = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("relocateToSafeThrowStance");
        relocate.setAccessible(true);
        check((Boolean) relocate.invoke(task), "近旁存在干燥站位时应发起换位而不是直接失败");
        check(String.valueOf(stage(task)).equals("RELOCATE_STANCE"),
                "换位应进入专属阶段");
        // 换位移动超过期限：按原 unsafe_ender_eye_throw_stance 话术失败，不无限换位。
        forceMoveDeadlinePast(task);
        Method tickRelocate = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("tickRelocateStance");
        tickRelocate.setAccessible(true);
        check(tickRelocate.invoke(task) == org.maiwithu.maicraft.task.TaskState.FAILED,
                "换位移动超时应失败");
        check(issueCode(task).equals("unsafe_ender_eye_throw_stance"),
                "超时失败沿用原站姿问题码");
    }

    /** 点 2：眼实体与原生回执已确认而背包数量未减——声明不可证后继续跟踪，不中止也不计数。 */
    private static void unconfirmedConsumptionContinuesTracking(InteractionWorldTestHarness w) throws Exception {
        var task = newTask(w);
        set(task, "eyeCountBeforeThrow", 1);
        set(task, "throwOrigin", w.player.position());
        set(task, "eyesBefore", Set.of());
        w.player.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 1));
        var eye = newEye(w, new Vec3(6.5, 6, 5.5));
        set(task, "eyeReceipt", confirmedReceipt(w));
        Method tick = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("tickEyeReceipt");
        tick.setAccessible(true);
        var state = (org.maiwithu.maicraft.task.TaskState) tick.invoke(task);
        check(state == org.maiwithu.maicraft.task.TaskState.RUNNING, "不可证消耗应声明后继续");
        check((Boolean) get(task, "eyeConsumptionUnverified"), "回执应标记消耗不可证");
        check((int) get(task, "eyesConsumed") == 0, "不可证消耗不得计数");
        check(String.valueOf(stage(task)).equals("TRACK_EYE"),
                "声明后应进入本眼方向跟踪");
    }

    /** 点 3：保鲜期内的方向记忆让新任务免掷续走——先经路线截取建立移动腿或进入有界等待，
     * 不再空腿直接进入行进阶段；方向截不出已加载路段时有界等待后如实失败。 */
    private static void rememberedDirectionResumesWithoutThrow(InteractionWorldTestHarness w) throws Exception {
        var task = newTask(w);
        set(task, "stronghold", true);
        set(task, "origin", w.player.blockPosition().immutable());
        attachSector(task, w);
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) getStatic(
                PhysicalStructureSearchCompanionTask.class, "EYE_DIRECTION_MEMORY");
        Object fresh = storedDirection(w, new Vec3(0, 0, 1), w.level.getGameTime());
        memory.put("minecraft:overworld|minecraft:stronghold", fresh);
        resume(task);
        check(String.valueOf(get(task, "pendingDirection")).contains("0.0, 0.0, 1.0"),
                "续走方向应来自记忆");
        check((int) get(task, "directionSegments") == 1, "续走段计入方向段计数");
        check(get(task, "moveChild") != null || String.valueOf(stage(task)).equals("OBSERVE"),
                "续走必须建立移动腿或进入有界等待，不得空腿进入行进阶段"
                        + "（空腿会在首刻路线丢失且跨任务复现）");

        // 夹具世界只有原点区块已加载、截不出十二格以上的路段：有界等待耗尽后按方向话术如实失败，
        // 而不是 internal_route_lost。
        set(task, "directionLoadWaitTicks", 41);
        Method continueTravel = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("continueDirectionTravel");
        continueTravel.setAccessible(true);
        check(continueTravel.invoke(task) == org.maiwithu.maicraft.task.TaskState.FAILED,
                "截不出已加载路段应有界失败");
        check(issueCode(task).equals("eye_direction_not_traversable"),
                "有界等待耗尽沿用方向不可行问题码");

        // 超龄记忆：不续走并淘汰，任务回到观察/投眼循环。
        var stale = storedDirection(w, new Vec3(1, 0, 0), w.level.getGameTime() - 100_000);
        memory.put("minecraft:overworld|minecraft:stronghold", stale);
        var second = newTask(w);
        set(second, "stronghold", true);
        set(second, "origin", w.player.blockPosition().immutable());
        resume(second);
        check(String.valueOf(stage(second)).equals("OBSERVE"),
                "超龄方向记忆不得续走");
        check(!memory.containsKey("minecraft:overworld|minecraft:stronghold"),
                "超龄记忆应被淘汰");
    }

    /**
     * 点 4：主动路线腿丢失先有界重建一次（116：原实现立即 internal_route_lost 且每次
     * 重提复现）；重建不出路线腿时有界降级，第二次丢失才终态失败且回执携带复位证据。
     */
    private static void lostRouteRecoversOnceThenFailsHonest(InteractionWorldTestHarness w) throws Exception {
        Method tickMove = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("tickMove", boolean.class, boolean.class);
        tickMove.setAccessible(true);

        // 证据接近腿丢失：按已确认线索位置直接重建移动腿。
        var task = newTask(w);
        set(task, "stronghold", true);
        set(task, "origin", w.player.blockPosition().immutable());
        set(task, "activeEvidence", evidenceMatch(w));
        set(task, "stage", stageEnum("MOVE_EVIDENCE"));
        var state = (org.maiwithu.maicraft.task.TaskState) tickMove.invoke(task, true, false);
        check(state == org.maiwithu.maicraft.task.TaskState.RUNNING,
                "路线腿丢失应先重建一次而不是立即失败");
        check(get(task, "moveChild") != null, "复位应重建出移动腿");
        check((int) get(task, "internalRouteRecoveries") == 1, "复位应计入回执证据");

        // 方向腿丢失而截不出已加载路段：有界降级进入观察等待，不按内部错误终止。
        var bounded = newTask(w);
        set(bounded, "stronghold", true);
        set(bounded, "origin", w.player.blockPosition().immutable());
        attachSector(bounded, w);
        set(bounded, "pendingDirection", new Vec3(0, 0, 1));
        set(bounded, "pendingDirectionDistance", 64.0);
        set(bounded, "stage", stageEnum("MOVE_DIRECTION"));
        state = (org.maiwithu.maicraft.task.TaskState) tickMove.invoke(bounded, false, true);
        check(state == org.maiwithu.maicraft.task.TaskState.RUNNING,
                "重建不出路段时应有界降级而不是失败");
        check(String.valueOf(stage(bounded)).equals("OBSERVE"),
                "有界降级回到观察等待已加载地形");

        // 第二次丢失：终态失败，问题码保持 internal_route_lost且回执带复位计数。
        set(bounded, "moveChild", null);
        set(bounded, "moveRecord", null);
        state = (org.maiwithu.maicraft.task.TaskState) tickMove.invoke(bounded, false, true);
        check(state == org.maiwithu.maicraft.task.TaskState.FAILED,
                "复位后再次丢腿应终态失败");
        check(issueCode(bounded).equals("internal_route_lost"), "终态失败沿用原问题码");
        Method resultData = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("resultData");
        resultData.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resultData.invoke(bounded);
        check(Integer.valueOf(1).equals(((Number) data.get("internal_route_recoveries")).intValue()),
                "失败回执应携带复位计数");

        // 换位腿丢失：回到投眼选择重新做有界换位，不按内部错误终止。
        var stance = newTask(w);
        set(stance, "stronghold", true);
        set(stance, "origin", w.player.blockPosition().immutable());
        set(stance, "stage", stageEnum("RELOCATE_STANCE"));
        Method tickRelocate = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("tickRelocateStance");
        tickRelocate.setAccessible(true);
        var stanceState = (org.maiwithu.maicraft.task.TaskState) tickRelocate.invoke(stance);
        check(stanceState == org.maiwithu.maicraft.task.TaskState.RUNNING,
                "换位腿丢失应回到投眼选择重试");
        check(String.valueOf(stage(stance)).equals("SELECT_EYE"),
                "换位腿丢失后回到投眼选择阶段");
    }

    private static Object evidenceMatch(InteractionWorldTestHarness w) throws Exception {
        Class<?> type = Class.forName(
                PhysicalStructureSearchCompanionTask.class.getName() + "$EvidenceMatch");
        Constructor<?> ctor = type.getDeclaredConstructor(
                BlockPos.class, Map.class, Map.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(w.player.blockPosition().immutable(), Map.of(), Map.of(), 0);
    }

    private static void attachSector(Object task, InteractionWorldTestHarness w) throws Exception {
        var record = (PhysicalStructureSearchTaskRecord) get(task, "r");
        set(task, "sector", record.sector.at(
                w.player.getX(), w.player.getZ(), w.player.getYRot()));
    }

    private static Object stageEnum(String name) throws Exception {
        Class<?> type = Class.forName(
                PhysicalStructureSearchCompanionTask.class.getName() + "$Stage");
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        throw new IllegalStateException("no stage " + name);
    }

    private static PhysicalStructureSearchCompanionTask newTask(InteractionWorldTestHarness w) {
        var record = new PhysicalStructureSearchTaskRecord(
                "eye-" + UUID.randomUUID(), w.level.getGameTime() + 20_000L,
                "minecraft:stronghold", 512, false, false);
        return new PhysicalStructureSearchCompanionTask(w.player, record);
    }

    /** 休眠实体注入夹具世界，供 findNewEye 在有界盒内找到"新出现的眼"。 */
    private static Object newEye(InteractionWorldTestHarness w, Vec3 at) throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var eye = (EyeOfEnder) unsafe.allocateInstance(EyeOfEnder.class);
        field(Entity.class, "position").set(eye, at);
        field(Entity.class, "blockPosition").set(eye, BlockPos.containing(at));
        field(Entity.class, "level").set(eye, w.level);
        field(Entity.class, "uuid").set(eye, UUID.randomUUID());
        eye.setBoundingBox(new net.minecraft.world.phys.AABB(
                at.x - .25, at.y, at.z - .25, at.x + .25, at.y + .25, at.z + .25));
        field(Entity.class, "id").setInt(eye, 77);
        w.level.entities.put(77, eye);
        return eye;
    }

    private static Object confirmedReceipt(InteractionWorldTestHarness w) throws Exception {
        LocalPlayerContext context = ClientRuntime.requireContext(w.player);
        Constructor<?> ctor = NativeActionReceipt.class.getDeclaredConstructor(
                NativeActionReceipt.Kind.class, LocalPlayerContext.class,
                int.class, int.class, NativeConfirmation.class,
                BlockPos.class, Direction.class);
        ctor.setAccessible(true);
        NativeConfirmation confirmation = c -> NativeConfirmation.Verdict.APPLIED;
        Object receipt = ctor.newInstance(NativeActionReceipt.Kind.USE_ITEM, context, 100, 1,
                confirmation, null, null);
        // poll 要求回执是端口当前活动动作；手造回执经私有 install 注册后再驱动。
        Object port = context.actions();
        Method install = port.getClass().getDeclaredMethod("install", NativeActionReceipt.class);
        install.setAccessible(true);
        install.invoke(port, receipt);
        return receipt;
    }

    private static Object storedDirection(InteractionWorldTestHarness w, Vec3 direction, long gameTime) throws Exception {
        Class<?> type = Class.forName(PhysicalStructureSearchCompanionTask.class.getName() + "$StoredEyeDirection");
        Constructor<?> ctor = type.getDeclaredConstructor(Vec3.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(direction, gameTime);
    }

    private static void resume(Object task) throws Exception {
        Method resume = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("resumeRememberedEyeDirection");
        resume.setAccessible(true);
        resume.invoke(task);
    }

    private static void forceMoveDeadlinePast(Object task) throws Exception {
        Object record = get(task, "moveRecord");
        field(org.maiwithu.maicraft.task.TaskRecord.class, "deadlineGameTime")
                .set(record, 0L);
    }

    private static String stage(Object task) throws Exception {
        return String.valueOf(get(task, "stage"));
    }

    private static String issueCode(Object task) throws Exception {
        return String.valueOf(get(task, "issueCode"));
    }

    private static Object get(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        field(owner.getClass(), name).set(owner, value);
    }

    private static Object getStatic(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result.get(null);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

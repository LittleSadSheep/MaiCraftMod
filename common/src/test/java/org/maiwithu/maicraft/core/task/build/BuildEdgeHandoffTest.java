// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.task.TaskState;

/** 将边缘动作接入真正施工入口，验证旧放法、外界完成、暂停恢复和锚点取物的顺序。 */
public final class BuildEdgeHandoffTest {
    private static final Vec3 ANCHOR = new Vec3(3.5, 1, 3.5);
    private static final BlockPos TARGET = new BlockPos(5, 1, 3);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        rejectedReadyGestureCannotReplay();
        allCompletionEntrypointsReturnFirst();
        pauseDiscardsExpiredMotionAndPreparationWaitsForAnchor();
        System.out.println("BuildEdgeHandoffTest: passed");
    }
    private static void rejectedReadyGestureCannotReplay() throws Exception {
        try (var h = fixture()) {
            AtomicBoolean allowed = new AtomicBoolean(true);
            var drive = drive(h, allowed, new AtomicInteger());
            // 独立物理测试已验证行走；这里提供已到位状态，专门检验真实放法生成与拒绝账交接。
            for (String name : List.of("approached", "prepared", "anchorAligned", "itemPrepared")) field(drive, name).setBoolean(drive, true);
            check(drive.tick() == BuildPlacementAccessDrive.Status.READY && drive.gesture() != null, "当前真实可放的手法先能准备成功");
            allowed.set(false); drive.rejectedGesture();
            check(drive.gesture() == null && drive.tick() == BuildPlacementAccessDrive.Status.FAILED,
                    "原生拒绝后的 READY 不得把同一旧放法再次交回");
            check(h.blockUses() == 0 && h.itemUses() == 0, "拒绝账检查只观察，不试点验证失败手法");
        }
    }
    private static void allCompletionEntrypointsReturnFirst() throws Exception {
        for (String entry : List.of("placeNavTick", "selectItemTick", "aimTick")) try (var h = fixture()) {
            var target = target(); h.set(TARGET, target.desiredState());
            var record = new BuildTaskRecord("edge-complete", 1000, List.of(target), false, false);
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            Class<?> cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var constructor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
            field(task, "cell").set(task, constructor.newInstance(target, List.of()));
            var access = drive(h, new AtomicBoolean(true), new AtomicInteger());
            field(access, "edge").set(access, new BuildEdgeMotion(ANCHOR, ANCHOR.add(0, 0, .65), LongSets.emptySet(), p -> true));
            field(task, "placementAccess").set(task, access);
            var method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(entry); method.setAccessible(true);
            check(method.invoke(task) == TaskState.RUNNING && field(task, "phase").get(task).toString().equals("EDGE_RETURN"),
                    "目标已被完成时，" + entry + " 也须先退回锚点");
            check(record.placed() == 0 && h.blockUses() == 0 && h.itemUses() == 0, "退回之前不提前记放置完成或重复点击");
        }
    }
    private static void pauseDiscardsExpiredMotionAndPreparationWaitsForAnchor() throws Exception {
        try (var h = fixture()) {
            AtomicInteger preparations = new AtomicInteger(); var drive = drive(h, new AtomicBoolean(true), preparations);
            var old = new BuildEdgeMotion(ANCHOR, ANCHOR.add(0, 0, .65), LongSets.emptySet(), p -> true);
            field(drive, "edge").set(drive, old); field(drive, "routes").setInt(drive, 1);
            drive.pause();
            check(drive.edgeActive() && field(drive, "edge").get(drive) == null, "暂停保留退回需求但丢弃旧控制租约");
            var clock = BuildPlacementAccessDrive.class.getDeclaredMethod("resumeClock"); clock.setAccessible(true); clock.invoke(drive);
            check(field(drive, "routes").getInt(drive) == 0 && field(drive, "deadline").getLong(drive) > h.level.getGameTime(),
                    "明确恢复使用新的有限时钟和路由额度，不复用过期运动对象");
            // 人在锚点附近仍未等于站上锚点；提供位置观察，不运行替代的位移算法。
            h.position(ANCHOR.add(.6, 0, 0));
            for (int tick = 0; tick < 6; tick++) { drive.tick(); h.nextTick(); }
            check(preparations.get() == 0 && h.blockUses() == 0, "尚未实际对齐实地锚点时，不能提前取物或点击");
        }
    }
    private static BuildPlacementAccessDrive drive(InteractionWorldTestHarness h, AtomicBoolean allowed, AtomicInteger preparations) {
        var access = new BuildPlacementAccessSearch.Access(ANCHOR, ANCHOR, List.of(ANCHOR), null, false);
        return new BuildPlacementAccessDrive(h.player, target(), PlayerNav.ContextProvider.DEFAULT, () -> true, access,
                () -> { preparations.incrementAndGet(); return BuildPlacementAccessDrive.Status.READY; }, gesture -> allowed.get());
    }
    private static BuildTaskRecord.Target target() { return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, TARGET, "eave", null, null, null); }
    private static InteractionWorldTestHarness fixture() throws Exception {
        var h = new InteractionWorldTestHarness(); field(h.player, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
        h.position(ANCHOR); h.player.setDeltaMovement(0, -.0784, 0); return h;
    }
    private static Field field(Object object, String name) throws Exception {
        for (Class<?> owner = object.getClass(); owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

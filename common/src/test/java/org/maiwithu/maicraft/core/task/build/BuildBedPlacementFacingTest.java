// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 床这类双格方块的原生朝向随点击时的视角决定：作者没有点名朝向时，原生落法与图纸默认朝向不同
 * 也必须整单成功（196 实机：床放好了任务却报 placement_diverged）；点名了朝向时仍维持原判。
 */
public final class BuildBedPlacementFacingTest {
    private static final BlockPos AT = new BlockPos(5, 1, 5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        acceptanceRules();
        nativeFacingDivergingFromBlueprintStillSucceeds();
        System.out.println("BuildBedPlacementFacingTest: native bed facing settles as success, authored facing stays enforced");
    }

    private static void acceptanceRules() {
        var east = Blocks.RED_BED.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        var plain = bedTarget(AT, false);
        check(plain.acceptsPlacedState(east) && plain.matches(east),
                "an unauthored bed cell accepts the natively chosen facing at verification too");
        var pinned = bedTarget(AT, true);
        check(!pinned.acceptsPlacedState(east) && !pinned.matches(east),
                "an explicitly authored bed facing still refuses a different native facing");
    }

    private static void nativeFacingDivergingFromBlueprintStillSucceeds() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 角色从西侧接近并朝东放置：原生朝向必然与图纸默认的 north 不同，夹具才踩中实机缺陷场景。
            h.position(new Vec3(2.5, 1, 5.5));
            h.inventory.setItem(0, new ItemStack(Items.RED_BED, 1)); h.inventory.selected = 0;
            var target = bedTarget(AT, false);
            var record = new BuildTaskRecord("bed-facing", 1000, List.of(target), false);
            record.previewManaged(true);
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            var cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var ctor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class);
            ctor.setAccessible(true);
            field(task, "cell").set(task, ctor.newInstance(target, BuildPlacementGeometry.generatedBy(target)));
            field(task, "liveGestures").set(task, BuildPlacementGeometry.plan(h.player, target, Map.of()));
            BuildPlacementFootingTest.settle(h, task);
            invoke(task, "placeNavTick");
            var gesture = (BuildPlacementGeometry.Gesture) field(task, "gesture").get(task);
            check(gesture != null, "the fixture must prove an open-ground bed placement gesture");
            invoke(task, "selectItemTick");
            // 原生结果由预测给出，不能手写：预测与图纸默认朝向必须真的不同，夹具才算踩中缺陷场景。
            var nativeState = BuildPlacementGeometry.predict(h.player, target, gesture.syntheticHit(),
                    gesture.yaw(), gesture.pitch());
            check(nativeState != null && nativeState.getBlock() == Blocks.RED_BED,
                    "the fixture must predict a native bed placement");
            check(!nativeState.equals(target.desiredState()),
                    "the fixture must exercise a native facing that differs from the blueprint default");
            BlockState head = nativeState.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
            BlockPos headPos = AT.relative(nativeState.getValue(BlockStateProperties.HORIZONTAL_FACING));
            Object mode = field(h, "mode").get(h);
            field(mode, "beforeBlockUse").set(mode, (Runnable) () -> { h.set(AT, nativeState); h.set(headPos, head); });
            TaskState state = TaskState.RUNNING;
            // 只推进到点击提交为止；提交后再调 aimTick 会绕过 WAIT_USE 阶段重新进入放置流程。
            for (int tick = 0; tick < 60 && state == TaskState.RUNNING && h.blockUses() == 0; tick++) {
                h.nextTick();
                var g = (BuildPlacementGeometry.Gesture) field(task, "gesture").get(task);
                if (g != null) { h.player.setYRot(g.yaw()); h.player.setXRot(g.pitch()); }
                state = invoke(task, "aimTick");
            }
            check(h.blockUses() == 1, "the diverging bed click must actually be submitted once");
            // 服务器随后确认这次点击并扣走那张床；任务按真实世界证据走完确认、收尾与成品复查。
            h.level.acknowledgedSequence = h.level.blockSequence;
            h.inventory.getItem(0).shrink(1);
            for (int tick = 0; tick < 60 && state == TaskState.RUNNING; tick++) {
                h.nextTick(); state = task.tick(h.player);
            }
            check(state == TaskState.SUCCESS,
                    "a bed placed with the native facing must finish successfully instead of placement_diverged");
            check(record.completed() == 1 && record.placed() == 1,
                    "the placed bed is accounted as one completed target");
            check(h.level.getBlockState(AT).equals(nativeState) && h.level.getBlockState(headPos).equals(head),
                    "both halves stay in the world with the native facing");
            task.result(state);
        }
    }

    /** 与 place_block 的单格蓝图同一份解析：真实走 BuildTool 的精确状态目标，不手搓简化目标。 */
    private static BuildTaskRecord.Target bedTarget(BlockPos at, boolean pinFacing) {
        JsonObject cell = new JsonObject();
        cell.addProperty("op", "set");
        cell.addProperty("block_id", "minecraft:red_bed");
        cell.addProperty("x", at.getX()); cell.addProperty("y", at.getY()); cell.addProperty("z", at.getZ());
        if (pinFacing) {
            JsonObject properties = new JsonObject();
            properties.addProperty("facing", "north");
            cell.add("properties", properties);
        }
        JsonArray ops = new JsonArray(); ops.add(cell);
        return BuildTool.resolvedTargets(ops, true).getFirst();
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static TaskState invoke(FirstPersonBuildCompanionTask task, String name) throws Exception {
        var method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true);
        return (TaskState) method.invoke(task);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}

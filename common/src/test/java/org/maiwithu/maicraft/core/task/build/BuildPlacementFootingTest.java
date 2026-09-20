// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import net.minecraft.world.entity.Entity;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** 用真实施工入口复现半空候选被抢用和落地后木桶朝向改变，不能只测镜头角度。 */
public final class BuildPlacementFootingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        landingNeedsDistinctTicksButFlatWalkingContinues();
        airborneShortcutCannotSelectItems();
        changedHeightReprovesWithoutRejectingTheOldGesture();
        System.out.println("BuildPlacementFootingTest: passed");
    }

    private static void landingNeedsDistinctTicksButFlatWalkingContinues() {
        var gate = new BuildPlacementFooting(); Vec3 feet = new Vec3(3.5, 1, 4.5);
        check(!gate.observe(feet, false, 1), "腾空不授予施工站位");
        check(!gate.observe(feet, true, 2) && !gate.observe(feet, true, 2), "同刻重入不能伪造落地稳定时间");
        check(!gate.observe(feet, true, 3) && gate.observe(feet, true, 4), "连续三刻实地才接管");
        check(gate.observe(feet.add(.2, 0, 0), true, 5), "平地顺路走放不因水平移动重新等三刻");
        check(!gate.observe(feet.add(.2, .5, 0), false, 6), "再起跳立即撤销旧站位");
        check(!gate.observe(feet, true, 7) && !gate.observe(feet, true, 20), "暂停间隔不能算作主动落稳");
    }

    private static void airborneShortcutCannotSelectItems() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(3.12, 1.6, 4.8)); grounded(h, false);
            h.inventory.setItem(0, new ItemStack(Items.STONE, 16));
            var target = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(4, 1, 4), "附近施工", null, null, null);
            var task = task(h, target);
            // 导航自身的到站条件也不能只认半空跨入目标格，否则会在施工接管前先把登阶输入撤掉。
            var reached = task.getClass().getDeclaredMethod("placementStanceReached", BlockPos.class); reached.setAccessible(true);
            check(!(boolean) reached.invoke(task, PlayerNav.playerFeet(h.player)),
                    "半空脚格相同仍不能向导航报告已到站");
            var candidate = BuildPlacementGeometry.currentGesture(h.player, target, Map.of());
            check(candidate != null, "纯几何仍能描述腾空时看得见的候选，不添加全局落地限制");
            var nearby = task.getClass().getDeclaredMethod("selectNearbyPlacement", Vec3.class); nearby.setAccessible(true);
            check(!(boolean) nearby.invoke(task, new Object[]{null}) && field(task, "gesture").get(task) == null,
                    "真实快捷分支不得抢用半空候选");
            field(task, "gesture").set(task, candidate);
            invoke(task, "selectItemTick");
            check(!field(task, "phase").get(task).toString().equals("AIM") && h.blockUses() == 0,
                    "意外进入选物阶段也必须先落地，不能直接转头点击");
            h.position(new Vec3(3.12, 1, 4.8)); grounded(h, true);
            check((boolean) reached.invoke(task, PlayerNav.playerFeet(h.player)),
                    "真实落地且脚格相同后允许导航结算到站");
            for (int i = 0; i < 3; i++) { h.nextTick(); invoke(task, "selectItemTick"); }
            check(field(task, "phase").get(task).toString().equals("AIM"), "落稳后从真实脚位重证并进入瞄准");
        }
    }

    private static void changedHeightReprovesWithoutRejectingTheOldGesture() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(3.5, 2, 4.5)); h.inventory.setItem(0, new ItemStack(Items.BARREL));
            h.set(new BlockPos(3, 1, 4), Blocks.STONE.defaultBlockState());
            h.set(new BlockPos(4, 1, 4), Blocks.STONE.defaultBlockState());
            var target = new BuildTaskRecord.Target(Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP),
                    Items.BARREL, new BlockPos(4, 2, 4), "朝上木桶", null, null, null);
            var task = task(h, target);
            var high = BuildPlacementGeometry.currentGesture(h.player, target, Map.of());
            check(high != null, "较高实际站位确实能通过原生朝上预测");
            field(task, "gesture").set(task, high); settle(h, task); invoke(task, "selectItemTick");
            h.position(new Vec3(3.5, 1.5, 3.5)); grounded(h, false); h.nextTick();
            invoke(task, "aimTick");
            check(field(task, "aimWaitReason").get(task).equals("waiting_for_stable_footing"), "下落时不按旧角度继续判错");
            h.position(new Vec3(3.5, 1, 3.5)); grounded(h, true);
            check(BuildPlacementGeometry.recheckCurrentGesture(h.player, target, Map.of(), high) == null,
                    "落回一级后台阶视向已不能放出朝上木桶，必须重新导航");
            for (int i = 0; i < 3; i++) { h.nextTick(); invoke(task, "aimTick"); }
            var attempts = (PlacementAttemptLedger) field(task, "placementAttempts").get(task);
            check(field(task, "phase").get(task).toString().equals("PLACE_NAV") && attempts.rejectedCount(target) == 0
                    && h.blockUses() == 0, "失去站位只重找通路，不点击、不消耗旧手法的失败预算");
        }
    }

    // 测试角色没有完整游戏物理循环，沿用夹具的实体字段观测方式提供落地样本，避免触发未初始化的支撑查询。
    static void grounded(InteractionWorldTestHarness h, boolean value) throws Exception {
        var field = Entity.class.getDeclaredField("onGround");
        field.setAccessible(true); field.setBoolean(h.player, value);
    }

    // 老回归直接进入内部阶段时，也先提供真实独立刻的落地样本；不修改生产门槛或预填成功状态。
    static void settle(InteractionWorldTestHarness h, FirstPersonBuildCompanionTask task) throws Exception {
        var gate = (BuildPlacementFooting) field(task, "placementFooting").get(task);
        for (int i = 0; i < 3; i++) { h.nextTick(); gate.ready(h.player); }
    }

    private static FirstPersonBuildCompanionTask task(InteractionWorldTestHarness h, BuildTaskRecord.Target target) throws Exception {
        var task = new FirstPersonBuildCompanionTask(h.player, new BuildTaskRecord("footing", 1000, List.of(target), false));
        var type = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
        var ctor = type.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); ctor.setAccessible(true);
        Object cell = ctor.newInstance(target, List.of());
        field(task, "cell").set(task, cell); field(task, "queue").set(task, new ArrayList<>(List.of(cell)));
        return task;
    }
    private static Object invoke(Object task, String method) throws Exception {
        var call = task.getClass().getDeclaredMethod(method); call.setAccessible(true); return call.invoke(task);
    }
    private static Field field(Object task, String name) throws Exception {
        var field = task.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}

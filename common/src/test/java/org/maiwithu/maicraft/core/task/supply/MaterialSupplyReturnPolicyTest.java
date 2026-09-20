// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.PlayerInv;

/** 通过真实获取任务的背包路径核对返回策略；测试站位变化和停止导航不冒充真实仓库/登高验收。 */
public final class MaterialSupplyReturnPolicyTest {
    private static final ResourceLocation QUARTZ = ResourceLocation.parse("minecraft:smooth_quartz");
    private static final Vec3 TOP = new Vec3(7.5, 10, 7.5), STORE = new Vec3(1.5, 1, 1.5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        callerHandoffNeverCreatesReturnNavigation();
        defaultStillReturnsAndReportsBlockedRoutes();
        unsettledAndPartialAcquisitionNeverHandOff();
        System.out.println("MaterialSupplyReturnPolicyTest: passed");
    }

    private static void callerHandoffNeverCreatesReturnNavigation() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.position(TOP);
            for (int slot = 0; slot < 4; slot++) h.inventory.setItem(slot, new ItemStack(Items.SMOOTH_QUARTZ, slot == 3 ? 26 : 64));
            var supply = begin(h, 218, true); h.position(STORE); h.nextTick();
            var result = runCarried(h, supply);
            check(result.status() == SemanticMaterialSupplyCoordinator.Status.SUPPLIED_REPLAN
                    && Boolean.TRUE.equals(result.receipt().get("materials_obtained"))
                    && Boolean.TRUE.equals(result.receipt().get("caller_handoff")), "确认二百一十八份材料后在仓库交回施工控制");
            check(Boolean.FALSE.equals(result.receipt().get("return_required"))
                    && Boolean.FALSE.equals(result.receipt().get("returned_to_investigation_site"))
                    && "caller_handoff".equals(result.receipt().get("return_policy")), "没有回墙顶就不能声称返程已完成");
            check(field(supply, "returnNavigation").get(supply) == null && h.player.blockPosition().equals(BlockPos.containing(STORE)),
                    "交接策略不创建返程导航或改变站位");
            check(count(h) == 218 && h.blockUses() == 0 && h.itemUses() == 0, "背包已足量时不补发、不重复领取或消耗已有材料");
        }
    }

    private static void defaultStillReturnsAndReportsBlockedRoutes() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.inventory.setItem(0, new ItemStack(Items.SMOOTH_QUARTZ));
            var nearby = begin(h, 1, false); var arrived = runCarried(h, nearby);
            check(arrived.status() == SemanticMaterialSupplyCoordinator.Status.SUPPLIED_REPLAN
                    && Boolean.TRUE.equals(arrived.receipt().get("return_required"))
                    && Boolean.TRUE.equals(arrived.receipt().get("returned_to_investigation_site")), "旧入口仍要求并检查原工位两格范围");
            h.position(TOP); var blocked = begin(h, 1, false); h.position(STORE); h.nextTick();
            // 已终止的导航提供确定失败，不在测试里启动实际寻路；仍走协调器原有返程失败分支。
            var nav = PlayerNav.toGoal(h.player, () -> NavGoal.near(BlockPos.containing(TOP), 2), 1, () -> false);
            Object transport = field(nav, "navigator").get(nav); field(transport, "stopped").setBoolean(transport, true);
            field(transport, "failure").set(transport, "原工位不可达的测试回执"); field(blocked, "returnNavigation").set(blocked, nav);
            check(((PlayerNav.ContextProvider) field(transport, "policy").get(transport)).permit() == TerrainPermit.PRESERVE,
                    "默认返程仍是普通导航，没有增加施工权限");
            var failure = runCarried(h, blocked);
            check(failure.status() == SemanticMaterialSupplyCoordinator.Status.FAILED
                    && "supply_return_path_blocked".equals(failure.receipt().get("failure_code"))
                    && Boolean.TRUE.equals(failure.receipt().get("materials_obtained"))
                    && Boolean.FALSE.equals(failure.receipt().get("goal_satisfied")), "真实返程失败仍报告失败，同时明确材料已经拿到");
            check(count(h) == 1, "导航失败不把材料退回或重新发送取料请求");
        }
    }

    private static void unsettledAndPartialAcquisitionNeverHandOff() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.inventory.setItem(0, new ItemStack(Items.SMOOTH_QUARTZ, 2));
            var pending = begin(h, 2, true);
            check(pending.tick(h.player, ignored -> null).status() == SemanticMaterialSupplyCoordinator.Status.RUNNING
                    && pending.active(), "数量先到也必须等获取任务自己的事务终态，不能提前交接");
            pending.cancel(h.player);
            var uncertain = begin(h, 2, true);
            var unresolved = uncertain.tick(h.player, child -> {
                child.start(h.player);
                try { field(child, "outcomeUncertain").setBoolean(child, true); } catch (Exception failure) { throw new AssertionError(failure); }
                return child.tick(h.player);
            });
            check(unresolved.status() == SemanticMaterialSupplyCoordinator.Status.FAILED
                    && Boolean.TRUE.equals(unresolved.receipt().get("outcome_uncertain")), "获取回执未确认时即使背包足量也不交接");
            var partial = begin(h, 4, true);
            var shortage = partial.tick(h.player, child -> { child.start(h.player); return TaskState.FAILED; });
            check(shortage.status() == SemanticMaterialSupplyCoordinator.Status.FAILED
                    && shortage.receipt().get("missing").equals(2) && Boolean.FALSE.equals(shortage.receipt().get("materials_obtained")),
                    "仅拿到部分材料不能冒充整批已确认");
            var cursor = begin(h, 2, true); h.player.inventoryMenu.setCarried(new ItemStack(Items.DIRT));
            var open = runCarried(h, cursor);
            check(open.status() == SemanticMaterialSupplyCoordinator.Status.FAILED
                    && "supply_handoff_unsettled".equals(open.receipt().get("failure_code"))
                    && h.player.inventoryMenu.getCarried().is(Items.DIRT), "鼠标还有物品时明确停止，保留原游标而不擅自清空");
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
        }
    }
    private static SemanticMaterialSupplyCoordinator begin(InteractionWorldTestHarness h, int count, boolean handoff) {
        var supply = new SemanticMaterialSupplyCoordinator();
        var demand = new SemanticMaterialSupplyCoordinator.Demand(List.of(QUARTZ), count, "施工补料");
        if (handoff) supply.begin(h.player, "handoff", 1000, demand, SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY,
                List.of(SemanticAcquireTaskRecord.Source.INVENTORY), false, List.of(), List.of(), SemanticMaterialSupplyCoordinator.ReturnPolicy.CALLER_HANDOFF);
        else supply.begin(h.player, "default-return", 1000, demand, SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY,
                List.of(SemanticAcquireTaskRecord.Source.INVENTORY), false, List.of());
        return supply;
    }
    private static SemanticMaterialSupplyCoordinator.Tick runCarried(InteractionWorldTestHarness h, SemanticMaterialSupplyCoordinator supply) {
        return supply.tick(h.player, child -> { child.start(h.player); return child.tick(h.player); });
    }
    private static int count(InteractionWorldTestHarness h) { return PlayerInv.buildableCount(h.inventory, Items.SMOOTH_QUARTZ); }
    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) try {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
        } catch (NoSuchFieldException inherited) { }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

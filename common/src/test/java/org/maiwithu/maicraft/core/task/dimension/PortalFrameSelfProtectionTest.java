// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.dimension.NetherPortalFrame;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationPolicy;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationSite;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationTask;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 施工单自注册的导航保护不得否决自家目标格：下界门框把整圈框注册为导航保护，
 * 预检随即用同一份名单否决全部门框格，原生荒地也报 blocked_site_cells，
 * may_alter_terrain 的动土授权在 build 站点校验中从未生效。
 */
public final class PortalFrameSelfProtectionTest {
    private static final BlockPos ORIGIN = new BlockPos(5, 2, 5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        virginSiteBuildsUnderTerrainConsent();
        inheritedExplicitProtectionStillVetoesAndNamesCells();
        unauthorizedPathStillRefusesConstruction();
        System.out.println("PortalFrameSelfProtectionTest: passed");
    }

    /** 无任何保护声明的原生荒地：may_alter_terrain=true 的建门预检必须放行门框目标格。 */
    private static void virginSiteBuildsUnderTerrainConsent() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var site = site(h);
            check(site.newSite(h.level), "原生荒地站址本身满足新建条件");
            var record = site.construction(h.level, "virgin-build", 20_000);
            h.inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 64));
            Task task = newBuildTask(h, record);
            task.start(h.player);
            TaskState state = (TaskState) invoke(task, "preflightTick");
            var data = task.result(state == TaskState.FAILED ? TaskState.FAILED : TaskState.RUNNING).data();
            check(!"blocked_site_cells".equals(data.get("failure_code")),
                    "荒地门框格不得被自家导航保护判成 protected cells");
            check(!data.containsKey("blocked_cells"), "放行的站址不产生逐格拒绝报告");
            check(state == TaskState.RUNNING, "材料齐备时荒地建门预检通过并进入施工");
        }
    }

    /** 继承的显式保护（NavigationSafetyContext）照常否决目标格，失败话术与回执点名坐标与来源。 */
    private static void inheritedExplicitProtectionStillVetoesAndNamesCells() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var site = site(h);
            var record = site.construction(h.level, "guarded-build", 20_000);
            BlockPos guarded = new NetherPortalFrame(ORIGIN, Direction.Axis.X, 2, 3).frame().getFirst();
            // 子任务在构造时快照继承保护；构造必须发生在保护作用域内，模拟外层任务传入的显式保留。
            Task task = NavigationSafetyContext.withProtectedArea(List.of(guarded), List.of(),
                    () -> newBuildTask(h, record));
            task.start(h.player);
            TaskState state = (TaskState) invoke(task, "preflightTick");
            check(state == TaskState.FAILED, "显式保护格仍拦截建门");
            var result = task.result(TaskState.FAILED);
            check("blocked_site_cells".equals(result.data().get("failure_code")), "拒绝仍归类 blocked_site_cells");
            check(result.data().get("blocked_cells") != null, "回执携带逐格 blocked_cells");
            String message = String.valueOf(result.message());
            check(message.contains("inherited_semantic_area_protection"), "话术点名拒绝来源（保护类型）");
            check(message.contains(guarded.toShortString()), "话术点名被拒格坐标");
        }
    }

    /** 未授权（may_alter_terrain=false）路径维持拒绝，不因本次放行而松动。 */
    private static void unauthorizedPathStillRefusesConstruction() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(ORIGIN.getX() + .5, 1, ORIGIN.getZ() + .5));
            var policy = new PortalPreparationPolicy(true, false, false, 128, MaterialPolicy.INVENTORY_ONLY, List.of(), List.of());
            var task = new PortalPreparationTask(h.player,
                    new PortalPreparationTaskRecord("unauthorized", 40_000, "minecraft:the_nether", 32, false, policy));
            task.start(h.player);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 400 && state == TaskState.RUNNING; i++) {
                h.nextTick(); TargetIndex.clientTick(h.level);
                state = task.tick(h.player);
            }
            check(state == TaskState.FAILED, "未授权时不得动工建门");
            check(h.blockUses() == 0, "未授权路径不产生任何方块变更");
            var data = task.result(state).data();
            check("portal_construction_permission_required".equals(data.get("issue_code")),
                    "拒绝话术指明缺少 may_alter_terrain 授权");
        }
    }

    /** 施工任务类对 dimension 包不可见；构造与预检入口用反射触达，断言仍走公开回执。 */
    private static Task newBuildTask(InteractionWorldTestHarness h, BuildTaskRecord record) {
        try {
            var ctor = Class.forName("org.maiwithu.maicraft.core.task.build.FirstPersonBuildCompanionTask")
                    .getDeclaredConstructor(net.minecraft.client.player.LocalPlayer.class, BuildTaskRecord.class);
            ctor.setAccessible(true);
            return (Task) ctor.newInstance(h.player, record);
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException("build companion constructor unavailable", broken);
        }
    }
    private static PortalPreparationSite site(InteractionWorldTestHarness h) {
        var site = new PortalPreparationSite(new NetherPortalFrame(ORIGIN, Direction.Axis.X, 2, 3), null);
        check(site.missingBlocks(h.level).size() == 10, "新建站址缺少全部十个门框格");
        return site;
    }
    private static Object invoke(Object task, String method) throws Exception {
        var call = task.getClass().getDeclaredMethod(method); call.setAccessible(true); return call.invoke(task);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

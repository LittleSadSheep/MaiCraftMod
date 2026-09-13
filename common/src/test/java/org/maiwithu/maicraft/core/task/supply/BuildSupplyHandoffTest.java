// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 推进建筑父任务连续两种材料的供料交接；库存和站位由夹具模拟，不宣称真实开箱或登高验收。 */
public final class BuildSupplyHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            // 高处离场由专门回归验证；这一段从已落到地面的施工出口开始，专查连续取料交接。
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.position(new Vec3(7.5, 1, 7.5));
            var first = new BuildTaskRecord.Target(Blocks.STONE_BRICKS, Items.STONE_BRICKS, new BlockPos(7, 2, 7), "wall", null, null, null);
            var second = new BuildTaskRecord.Target(Blocks.SMOOTH_QUARTZ, Items.SMOOTH_QUARTZ, new BlockPos(7, 3, 7), "trim", null, null, null);
            var plan = new BuildTaskRecord("upper-wall", 1000, List.of(first, second), false);
            var record = new SemanticBuildSupplyTaskRecord("upper-wall-supply", 1000, plan,
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE,
                    List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, List.of(), false);
            var task = new SemanticBuildSupplyCompanionTask(h.player, record, (owner, frozen) -> Decision.DISABLED);
            task.start(h.player); check(task.tick(h.player) == TaskState.RUNNING, "到达施工出口后开始第一种材料获取");
            var supply = (SemanticMaterialSupplyCoordinator) field(task, "supply").get(task);
            check(supply.active() && field(supply, "returnPolicy").get(supply) == SemanticMaterialSupplyCoordinator.ReturnPolicy.CALLER_HANDOFF,
                    "只有建筑父任务明确选用交接策略");
            // 模拟角色已经在仓库取得第一种材料，让真实获取任务按当前背包事实完成。
            h.position(new Vec3(1.5, 1, 1.5)); h.inventory.setItem(0, new ItemStack(Items.STONE_BRICKS)); h.nextTick();
            for (int i = 0; i < 4 && supply.active(); i++) { task.tick(h.player); h.nextTick(); }
            check(!supply.active() && field(supply, "returnNavigation").get(supply) == null, "第一种材料确认后不强行返回出发点");
            task.tick(h.player);
            check(supply.active() && h.player.blockPosition().equals(field(supply, "investigationOrigin").get(supply))
                    && field(task, "activeChild").get(task) == null, "仍在仓库就近获取第二种材料，尚未派施工返程");
            var demand = (SemanticMaterialSupplyCoordinator.Demand) field(supply, "demand").get(supply);
            check(demand.acceptableItemIds().equals(List.of(ResourceLocation.parse("minecraft:smooth_quartz")))
                    && demand.requiredFinalCount() == 1, "下一次只请求剩余缺少的石英，不补发已经拿到的石砖");
            h.inventory.setItem(1, new ItemStack(Items.SMOOTH_QUARTZ)); h.nextTick();
            for (int i = 0; i < 4 && supply.active(); i++) { task.tick(h.player); h.nextTick(); }
            var rounds = (List<?>) task.resultData().get("batches");
            check(rounds.size() == 2 && rounds.stream().allMatch(row -> {
                var receipt = (Map<?, ?>) ((Map<?, ?>) row).get("supply");
                return Boolean.TRUE.equals(receipt.get("materials_obtained")) && Boolean.TRUE.equals(receipt.get("caller_handoff"))
                        && Boolean.FALSE.equals(receipt.get("returned_to_investigation_site"));
            }), "每一种材料都明确记为在仓库交接，不伪造返回完成");
            task.tick(h.player);
            check(field(task, "activeRecord").get(task) instanceof BuildTaskRecord build && !build.supplyAccessOnly()
                    && h.inventory.getItem(0).getCount() == 1 && h.inventory.getItem(1).getCount() == 1,
                    "同批材料齐后交给原施工任务选站位，背包两种材料均未提前消耗或重复领取");
            check(h.blockUses() == 0 && h.itemUses() == 0, "供料交接自身没有隐藏方块操作或导航改地形");
            task.result(TaskState.CANCELLED);
        }
        System.out.println("BuildSupplyHandoffTest: passed");
    }
    private static Field field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

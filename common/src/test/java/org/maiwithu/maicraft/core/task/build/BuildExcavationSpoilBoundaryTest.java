// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 用明确标注的模拟回执验证仓库范围与失败收尾；这些测试不宣称已完成真实 GUI 搬运。 */
public final class BuildExcavationSpoilBoundaryTest {
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        warehouseTravelCannotExpandTheRadius();
        fullWarehousesHaveAFiniteAttemptBudget();
        inconsistentInventoryAndCustomizedCargoStop();
        System.out.println("BuildExcavationSpoilBoundaryTest: passed");
    }
    private static void warehouseTravelCannotExpandTheRadius() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); h.position(new Vec3(0.5, 1, 0.5));
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(5, 1, 0));
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(11, 1, 0));
            // 两个已知土料箱仍须受首次整理范围约束，走近第一箱不能放宽边界。
            ContainerSupplySourcesTest.rememberContents(h, new BlockPos(5, 1, 0), DIRT, 1);
            ContainerSupplySourcesTest.rememberContents(h, new BlockPos(11, 1, 0), DIRT, 1);
            var supply = begin(h, 6); supply.tick(h.player, ignored -> null);
            // 第一只箱子已满，角色走近它后不能把原本范围外的第二只箱子纳入搜索。
            h.position(new Vec3(5.5, 1, 0.5)); replace(supply, "child", new DepositReceipt(0));
            supply.tick(h.player, task -> task.tick(h.player));
            var result = supply.tick(h.player, ignored -> { throw new AssertionError("不能越界继续开箱"); });
            check(result.status() == BuildExcavationSpoilSupply.Status.FAILED && result.receipt().get("warehouse_attempts").equals(1),
                    "移动不会扩大最初六格授权范围");
            check(result.receipt().get("storage_search_origin").equals(List.of(0, 1, 0)), "回执保留最初搜索中心");
        } finally { ContainerSupplySources.reset(); }
    }
    private static void fullWarehousesHaveAFiniteAttemptBudget() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); h.position(new Vec3(0.5, 1, 0.5));
            // 满箱预算只覆盖已有土料线索的容器，不再把陌生木桶当成可探索的候选。
            for (int x = 1; x <= 9; x++) {
                ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(x, 1, 1));
                ContainerSupplySourcesTest.rememberContents(h, new BlockPos(x, 1, 1), DIRT, 1);
            }
            var supply = begin(h, 16);
            for (int i = 0; i < ContainerSupplySources.MAX_ATTEMPTS; i++) {
                supply.tick(h.player, ignored -> null); replace(supply, "child", new DepositReceipt(0));
                supply.tick(h.player, task -> task.tick(h.player));
            }
            var result = supply.tick(h.player, ignored -> { throw new AssertionError("八只满箱后必须停止"); });
            check(result.status() == BuildExcavationSpoilSupply.Status.FAILED
                    && result.receipt().get("warehouse_attempts").equals(8) && h.inventory.getItem(0).getCount() == 10,
                    "满箱只能尝试八次，不能丢物或无限换箱");
        } finally { ContainerSupplySources.reset(); }
    }
    private static void inconsistentInventoryAndCustomizedCargoStop() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(3, 1, 3));
            ContainerSupplySourcesTest.rememberContents(h, new BlockPos(3, 1, 3), DIRT, 1);
            var supply = begin(h, 16); supply.tick(h.player, ignored -> null);
            // 子回执说存了三份而背包没有变化，不能累计成已确认存入再自动重放。
            replace(supply, "child", new DepositReceipt(3));
            var mismatch = supply.tick(h.player, task -> task.tick(h.player));
            check(mismatch.status() == BuildExcavationSpoilSupply.Status.FAILED
                    && Boolean.TRUE.equals(mismatch.receipt().get("outcome_uncertain"))
                    && mismatch.receipt().get("confirmed_deposited").equals(Map.of()), "库存差量不符时保留不确定性而非虚报腾空");
            supply = begin(h, 16); supply.tick(h.player, ignored -> null); replace(supply, "child", new DepositReceipt(0));
            h.inventory.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("途中改名的土块"));
            var customized = supply.tick(h.player, ignored -> { throw new AssertionError("不能继续存入新出现的命名物品"); });
            check(customized.status() == BuildExcavationSpoilSupply.Status.FAILED && h.inventory.getItem(0).getCount() == 10,
                    "途中出现自定义组件时先停止，不让编号相同掩盖物品变化");
        } finally { ContainerSupplySources.reset(); }
    }
    private static BuildExcavationSpoilSupply begin(InteractionWorldTestHarness h, int radius) {
        h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.inventory.setItem(0, new ItemStack(Items.DIRT, 10));
        var supply = new BuildExcavationSpoilSupply(); supply.begin(h.player, "bounded-spoil", 1000, Map.of(DIRT, 10), List.of(), radius); return supply;
    }
    private static final class DepositReceipt implements Task {
        private final int moved; DepositReceipt(int moved) { this.moved = moved; }
        public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public String name() { return "模拟已收尾存入回执"; }
        public TaskResult result(TaskState state) { return TaskResult.ok("模拟存入", Map.of("operation", "deposit", "bounded_storage_deposit", true,
                "moved_count", moved, "moved_items", moved == 0 ? Map.of() : Map.of("minecraft:dirt", moved), "outcome_uncertain", false)); }
    }
    private static void replace(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

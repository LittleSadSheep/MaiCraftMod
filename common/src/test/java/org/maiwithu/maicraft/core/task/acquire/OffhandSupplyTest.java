// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.inventory.EquipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.OffhandSupplyTask;
import org.maiwithu.maicraft.core.task.inventory.OffhandSupplyTaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 离线回放副手物资的可见性与转移：副手握着点名物品时，取物调度应先安排一次原生换位，
 * 换位任务把整叠送进空主格；equip 也能从副手取用。副手对主背包口径动作不可见的误判不再发生。
 */
public final class OffhandSupplyTest {
    private static final ResourceLocation TORCH = id("minecraft:torch");

    private OffhandSupplyTest() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<AssertionError> failures = new java.util.ArrayList<>();
        run("调度只对副手现货与许可内的需求各安排一次换位", OffhandSupplyTest::acquireDispatch, failures);
        run("换位任务把副手整叠送进空主格并按净增确认", OffhandSupplyTest::pullMovesStack, failures);
        run("主背包无空格时拒绝换位且副手原物保留", OffhandSupplyTest::pullWithoutFreeSlotFails, failures);
        run("equip 从副手取用先换位再选中主手", OffhandSupplyTest::equipFromOffhand, failures);
        if (!failures.isEmpty()) {
            AssertionError failed = new AssertionError("副手供给回放发现 " + failures.size() + " 项行为不符合预期");
            failures.forEach(failed::addSuppressed);
            throw failed;
        }
        System.out.println("OffhandSupplyTest: all replay checks passed");
    }

    private static void acquireDispatch() {
        try (var world = new InteractionWorldTestHarness()) {
            var offhands = new AcquisitionOffhandInventory();
            var need = need(List.of(TORCH), List.of(SemanticAcquireTaskRecord.Source.INVENTORY), 5);

            // 副手为空或物品不符时不安排换位，不把其他来源变成副手操作。
            check(offhands.next(world.player, need, 5, () -> "offhand-1", 200) == null, "副手为空时不安排换位");
            world.player.getInventory().setItem(40, new ItemStack(Items.SHIELD));
            check(offhands.next(world.player, need, 5, () -> "offhand-1", 200) == null, "副手物品不符时不安排换位");

            // 副手握着点名物品且缺口大于零时安排一次；同一需求不重复安排。
            world.player.getInventory().setItem(40, new ItemStack(Items.TORCH, 19));
            OffhandSupplyTaskRecord record = offhands.next(world.player, need, 5, () -> "offhand-1", 200);
            check(record != null && record.items.equals(List.of(TORCH)) && record.amount == 5,
                    "副手现货应安排一次换位并携带点名物品与缺口");
            check(offhands.next(world.player, need, 5, () -> "offhand-2", 200) == null,
                    "同一需求不重复安排换位，失败也不重试");

            // 缺口为零或来源不含随身库存时不安排；换位不扩大任何采集许可。
            var satisfied = need(List.of(TORCH), List.of(SemanticAcquireTaskRecord.Source.INVENTORY), 5);
            check(offhands.next(world.player, satisfied, 0, () -> "offhand-3", 200) == null, "缺口为零时不安排换位");
            var denied = need(List.of(TORCH), List.of(SemanticAcquireTaskRecord.Source.MINE), 5);
            check(offhands.next(world.player, denied, 5, () -> "offhand-4", 200) == null, "随身库存不在许可内时不安排换位");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void pullMovesStack() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            enableTransactions(world);
            world.inventory.clearContent();
            world.player.getInventory().setItem(40, new ItemStack(Items.TORCH, 19));
            var record = new OffhandSupplyTaskRecord("offhand-1", 400, List.of(TORCH), 19);
            var task = new OffhandSupplyTask(world.player, record);
            task.start(world.player);
            TaskState state = drive(world, task);
            check(state == TaskState.SUCCESS, "换位任务应确认成功");
            check(world.player.getInventory().getItem(0).is(Items.TORCH)
                    && world.player.getInventory().getItem(0).getCount() == 19, "副手整叠应落在第一个空快捷栏格");
            check(world.player.getOffhandItem().isEmpty(), "换位后副手应清空");
            TaskResult result = task.result(TaskState.SUCCESS);
            check(result.success() && "minecraft:torch".equals(result.data().get("item_id"))
                    && Integer.valueOf(19).equals(result.data().get("moved_main_delta")),
                    "回执应点名换入物品并按主背包净增对账");
            check(Boolean.FALSE.equals(result.data().get("outcome_uncertain")), "已确认的换位不得报不确定");
        }
    }

    private static void pullWithoutFreeSlotFails() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            enableTransactions(world);
            world.inventory.clearContent();
            for (int i = 0; i < 36; i++) world.player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 1));
            world.player.getInventory().setItem(40, new ItemStack(Items.TORCH, 19));
            var record = new OffhandSupplyTaskRecord("offhand-1", 400, List.of(TORCH), 19);
            var task = new OffhandSupplyTask(world.player, record);
            task.start(world.player);
            TaskState state = drive(world, task);
            check(state == TaskState.FAILED, "无空主格时应确定失败");
            TaskResult result = task.result(TaskState.FAILED);
            check("no_space".equals(result.data().get("failure_type")), "无空主格的失败应报 no_space");
            check(world.player.getOffhandItem().getCount() == 19, "拒绝换位时副手原物必须原样保留");
        }
    }

    private static void equipFromOffhand() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            enableTransactions(world);
            world.inventory.clearContent();
            world.player.getInventory().setItem(40, new ItemStack(Items.TORCH, 19));
            var task = new EquipCompanionTask(world.player,
                    new EquipTaskRecord("equip", 400, Items.TORCH, null, "torch"));
            task.start(world.player);
            TaskState state = drive(world, task);
            check(state == TaskState.SUCCESS, "equip 应从副手换位后完成主手持物");
            check(world.player.getMainHandItem().is(Items.TORCH), "主手应观察到火把");
            check(world.player.getInventory().getItem(0).is(Items.TORCH), "换入的火把应留在主背包");
            check(Boolean.TRUE.equals(task.result(TaskState.SUCCESS).data().get("offhand_pulled")),
                    "从副手取用的回执应声明 offhand_pulled");
        }
    }

    private static void enableTransactions(InteractionWorldTestHarness world) throws Exception {
        var method = InteractionWorldTestHarness.class.getDeclaredMethod("enableInventoryTransactions", boolean.class);
        method.setAccessible(true); method.invoke(world, true);
        // 夹具只注入界面事实不模拟渲染循环；换位原语要求世界视野，这里与 InventoryHandVisibilityTest 同样显式清屏。
        var harness = fieldGet(world, "h");
        var minecraft = fieldGet(harness, "minecraft");
        var screenField = net.minecraft.client.Minecraft.class.getField("screen");
        screenField.set(minecraft, null);
    }

    private static Object fieldGet(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static TaskState drive(InteractionWorldTestHarness world, org.maiwithu.maicraft.task.Task task) throws Exception {
        TaskState state = TaskState.RUNNING;
        for (int tick = 0; tick < 20 && state == TaskState.RUNNING; tick++) {
            state = task.tick(world.player);
            if (state == TaskState.RUNNING) world.nextTick();
        }
        return state;
    }

    private static AcquisitionNeed need(List<ResourceLocation> itemIds,
            List<SemanticAcquireTaskRecord.Source> allowedSources, int count) {
        return new AcquisitionNeed(itemIds, count, 0, Set.copyOf(itemIds), Set.of(), Set.of(), allowedSources);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private static void run(String name, ThrowingCase body, List<AssertionError> failures) {
        try { body.run(); System.out.println("OffhandSupplyTest: " + name + " 通过"); }
        catch (AssertionError failure) { failures.add(failure); }
        catch (Exception failure) { failures.add(new AssertionError(failure)); }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    @FunctionalInterface private interface ThrowingCase { void run() throws Exception; }
}

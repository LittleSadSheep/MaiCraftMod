// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.client.actor.ItemEntityReceiptsTest;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.HashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.maiwithu.maicraft.task.TaskFactory;

/** 注入此前已核实的产物快照，回归通用收取子任务结束与下一帧真实Take/背包同步的边界；不运行或伪造一次配方加工。 */
public final class WorldProcessCollectionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 独立回归未启动MaiCraftCore，只登记它在实机启动时已有的通用拾取工厂，不替换任何原生动作。
        TaskFactory.register(CollectItemsTaskRecord.class, CollectItemsCompanionTask::new);
        // 独立Bootstrap不加载水的数据包标签；本夹具显式提供并恢复标签，按实机浅水规则建立场地。
        var fluids = BuiltInRegistries.FLUID;
        Map<TagKey<Fluid>,List<Holder<Fluid>>> tags = new HashMap<>();
        fluids.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var previous = new HashMap<>(tags);
        tags.put(FluidTags.WATER,List.of(fluids.wrapAsHolder(Fluids.WATER),fluids.wrapAsHolder(Fluids.FLOWING_WATER)));
        fluids.bindTags(tags);
        try { delayedReceiptAfterChildEnds(); childSuccessIsNotPickupProof(); wrongComponentsAndPureReport(); scopedCollector(); }
        finally { fluids.bindTags(previous); }
        System.out.println("WorldProcessCollectionTest: delayed parent settlement, scoped child and frozen pending-output evidence passed");
    }

    private static void delayedReceiptAfterChildEnds() throws Exception {
        try (var f = new Fixture()) {
            check(f.endChild(TaskState.FAILED) == TaskState.RUNNING, "子收取在Take前一帧结束不能让父任务提前失败");
            check(f.task.progress().get("phase").equals("verify_pickup"), "父任务进入有界同步结算，不重新择格或投料");
            Map<?, ?> pending = (Map<?, ?>) f.task.progress().get("pending_output");
            check(pending.get("entity_uuid").equals(f.entity.getUUID().toString()) && pending.get("count").equals(2)
                    && Boolean.TRUE.equals(pending.get("native_recipe_verified")) && Boolean.FALSE.equals(pending.get("collected")),
                    "收取未完成时仍报告已确认产物身份、数量和原生生成证据");
            f.world.nextTick(); f.take(f.entity);
            check(invoke(f.task, "verifyPickup") == TaskState.RUNNING, "Take已到但库存尚未同步不能报告成功");
            f.world.nextTick(); f.world.inventory.setItem(1, f.output.copy());
            check(invoke(f.task, "verifyPickup") == TaskState.SUCCESS, "下一帧完整组件库存同步后核实当前批次，不追改旧任务结果");
            check(((Map<?, ?>) f.task.progress().get("pending_output")).get("collected").equals(true), "只在真正结算后更新冻结收取状态");
        }
    }

    private static void childSuccessIsNotPickupProof() throws Exception {
        try (var f = new Fixture()) {
            check(f.endChild(TaskState.SUCCESS) == TaskState.RUNNING, "子任务成功不能替代同UUID本人Take");
            var wrong = ItemEntityReceiptsTest.item(f.world, 92, new Vec3(5.5, 1, 5.5), f.output);
            f.take(wrong); f.world.inventory.setItem(1, f.output.copy());
            check(invoke(f.task, "verifyPickup") == TaskState.RUNNING, "同组件净增与另一个UUID的拾取不能冒充本批收取");
            for (int tick = 0; tick < 21; tick++) f.world.nextTick();
            rejects(() -> invoke(f.task, "verifyPickup"), "output_pickup_not_verified");
            check(Boolean.FALSE.equals(f.task.progress().get("mechanical_retry_allowed")), "已消费的批次在失败后仍禁止盲重试");
        }
    }

    private static void wrongComponentsAndPureReport() throws Exception {
        try (var f = new Fixture()) {
            f.endChild(TaskState.SUCCESS);
            f.entity.getItem().set(DataComponents.CUSTOM_NAME, Component.literal("被替换的组件")); f.take(f.entity);
            f.world.inventory.setItem(1, f.output.copy());
            // 进度报告不能偷偷poll会抛异常的回执；已冻结的原生输出仍可展示，错误组件由执行阶段诚实拒绝。
            check(((Map<?, ?>) f.task.progress().get("pending_output")).get("item_id").equals("minecraft:brick"), "报告只读冻结证据");
            rejects(() -> invoke(f.task, "verifyPickup"), "pickup_components_changed");
            check(((Map<?, ?>) f.task.progress().get("pending_output")).get("collected").equals(false), "组件不符后不把已生成改写成已收取");
        }
    }

    private static void scopedCollector() throws Exception {
        try (var f = new Fixture()) {
            field(WorldTransformTask.class, "site").set(f.task, WorldProcessSite.inspect(f.world.player, new BlockPos(5, 1, 5), f.recipe));
            field(WorldTransformTask.class, "waitUntil").setLong(f.task, 1000);
            check(invoke(f.task, "collect") == TaskState.RUNNING, "加工收尾启动现有通用收取任务");
            Object record = field(WorldTransformTask.class, "childRecord").get(f.task);
            check(record instanceof CollectItemsTaskRecord collector && collector.targetUuids.equals(Set.of(f.entity.getUUID())),
                    "父任务只把已冻结成品UUID交给Collector，不再自行重复选择池边格");
            check(field(WorldTransformTask.class, "child").get(f.task) instanceof CollectItemsCompanionTask,
                    "受限任务单实际复用现有Collector执行器");
            f.task.stop(f.world.player, Task.StopReason.PREEMPTED);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final WorldProcessRecipe recipe = recipe();
        final ItemStack output = recipe.result();
        final ItemEntity entity;
        final WorldTransformTask task;
        Fixture() throws Exception {
            var at = new BlockPos(5, 1, 5); world.set(at, Blocks.WATER.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.REDSTONE));
            var plan = WorldProcessBatchPlan.compile(recipe, WorldProcessInventory.snapshot(world.player), 1, List.of(recipe));
            var inventory = new WorldProcessInventory(world.player, plan, output);
            world.inventory.setItem(0, ItemStack.EMPTY); inventory.dropped(new ItemStack(Items.REDSTONE), 1);
            long cursor = ItemEntityReceipts.cursor(world.player);
            entity = ItemEntityReceiptsTest.item(world, 91, new Vec3(5.5, 1.2, 5.5), output);
            var settlement = new WorldProcessSettlement(world.player, recipe, inventory, cursor, true);
            settlement.acceptNative(new WorldProcessEventEvidence.Output(entity.getUUID(), output));
            settlement.observe(ItemEntityReceipts.snapshot(world.player, new AABB(at)), true);
            var record = new WorldTransformTaskRecord("collection-boundary", 1000, recipe.id(), at, 1);
            record.submissionBarrier(() -> true); record.prepareNativeConsumptionBoundary();
            task = new WorldTransformTask(world.player, record);
            field(WorldTransformTask.class, "world").set(task, world.level);
            field(WorldTransformTask.class, "settlement").set(task, settlement);
            var events = new WorldProcessEvents(List.of(at), "minecraft:overworld"); field(WorldProcessEvents.class, "nativeEvents").setBoolean(events, true);
            field(WorldTransformTask.class, "events").set(task, events);
            Field phase = field(WorldTransformTask.class, "phase");
            @SuppressWarnings({"rawtypes", "unchecked"}) Object collect = Enum.valueOf((Class) phase.getType(), "COLLECT"); phase.set(task, collect);
        }
        TaskState endChild(TaskState state) throws Exception {
            field(WorldTransformTask.class, "child").set(task, new Task() {
                public TaskState tick(LocalPlayer player) { return state; }
                public void stop(LocalPlayer player, StopReason reason) { }
                public String name() { return "completed collection fixture"; }
                public TaskResult result(TaskState terminal) { return new TaskResult(terminal == TaskState.SUCCESS, "child ended before packet sync", false, false, Map.of()); }
            });
            field(WorldTransformTask.class, "childRecord").set(task, new TaskRecord("collect_items", "child", 1000) {
                public String describe() { return "bounded collection fixture"; }
            });
            return invoke(task, "tickChild");
        }
        void take(ItemEntity item) {
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(item.getId(), world.player.getId(), item.getItem().getCount()));
            world.level.entities.remove(item.getId());
        }
        @Override public void close() throws Exception { ItemEntityReceipts.observeWorld(null); world.close(); }
    }

    private static WorldProcessRecipe recipe() {
        return new WorldProcessRecipe() {
            public ResourceLocation id() { return ResourceLocation.parse("test:collection_boundary"); }
            public List<Ingredient> inputs() { return List.of(Ingredient.of(Items.REDSTONE)); }
            public ItemStack result() { return new ItemStack(Items.BRICK, 2); }
            public boolean supports(FluidState fluid) { return fluid.is(FluidTags.WATER); }
            public boolean isFluid() { return true; }
            public JsonObject describe() { return new JsonObject(); }
            public int triggerInputIndex() { return 0; }
        };
    }
    private static TaskState invoke(WorldTransformTask task, String name) throws Exception {
        var method = WorldTransformTask.class.getDeclaredMethod(name); method.setAccessible(true);
        try { return (TaskState) method.invoke(task); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof RuntimeException cause) throw cause; throw failure; }
    }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private interface CheckedAction { void run() throws Exception; }
    private static void rejects(CheckedAction action, String code) throws Exception {
        try { action.run(); } catch (IllegalStateException rejected) { check(rejected.getMessage().contains(code), rejected.getMessage()); return; }
        throw new AssertionError("expected " + code);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

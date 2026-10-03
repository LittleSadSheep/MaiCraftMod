package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.scan.DroppedItemObservation;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsRequest;
import org.maiwithu.maicraft.task.TaskState;

import java.util.List;
import java.util.Map;

/** 先选远处的一堆，再跟随漂移并结算本人拾取；旧引用、别人拾走和背包偶然增量都不能换成另一堆成功。 */
public final class CollectItemsSelectionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        followsSelectedStack();
        missingReferenceDoesNotCollectAnotherStack();
        inventoryGainAloneCannotConfirmSelectedStack();
        interruptionKeepsConfirmedPartialPickup();
        System.out.println("CollectItemsSelectionTest: passed");
    }

    private static void followsSelectedStack() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            ActorControlTestHarness.field(Level.class, "worldBorder").set(world.level, new WorldBorder());
            var near = item(world, 150, new Vec3(2.5, 1, 3.5));
            var selected = item(world, 151, new Vec3(7.5, 1, 3.5));
            String ref = DroppedItemObservation.describe(world.player, selected).get("drop_ref").getAsString();
            var task = task(world, ref); task.start(world.player);
            check(call(task, "nearestItem") == selected, "同类物品更近也不改变模型选中的物品堆");
            check(task.tick(world.player) == TaskState.RUNNING, "选定后直接进入靠近流程");
            clearNavigation(task);
            // 水流把物品推远后，仍用原引用解析同 UUID 的现位置，旧坐标不再成为行走终点。
            Vec3 moved = new Vec3(11.5, 1, 3.5);
            ActorControlTestHarness.field(Entity.class, "position").set(selected, moved);
            ActorControlTestHarness.field(Entity.class, "blockPosition").set(selected, BlockPos.containing(moved));
            selected.setBoundingBox(new AABB(11.375, 1, 3.375, 11.625, 1.25, 3.625));
            var destination = (GoalCompiler.Compiled) call(task, "targetGoal");
            check(destination.goal().isAt(new BlockPos(11, 1, 3)) && !destination.goal().isAt(new BlockPos(7, 1, 3)),
                    "行走目标跟随掉落移动，不用模型再次发移动或拾取");
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(selected.getId(), world.player.getId(), 2));
            world.level.entities.remove(selected.getId());
            check(task.tick(world.player) == TaskState.RUNNING, "拾取包先到时等待背包同步");
            world.inventory.setItem(0, new ItemStack(Items.BRICK, 2));
            check(task.tick(world.player) == TaskState.RUNNING && task.tick(world.player) == TaskState.SUCCESS, "本人同 UUID 回执及背包同步齐全才成功");
            var data = task.result(TaskState.SUCCESS).data();
            var collection = (Map<?, ?>) data.get("drop_collection");
            check(collection.get("collected_drop_refs").equals(List.of(ref))
                    && collection.get("unconfirmed_drop_refs").equals(List.of())
                    && data.get("collected_items").equals(Map.of("minecraft:brick", 2)), "结果明确指出选中哪堆和实际收取数量");
            check(world.level.entities.containsKey(near.getId()) && world.blockUses() == 0 && world.itemUses() == 0,
                    "精确拾取未转向另一堆，也未额外修改世界");
        }
    }

    private static void missingReferenceDoesNotCollectAnotherStack() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var selected = item(world, 160, new Vec3(7.5, 1, 3.5));
            String ref = DroppedItemObservation.reference(world.player, selected.getUUID());
            world.level.entities.remove(selected.getId());
            item(world, 161, new Vec3(2.5, 1, 3.5));
            var task = task(world, ref); task.start(world.player);
            check(task.tick(world.player) == TaskState.FAILED, "选择后已消失的物品堆不能零件成功，更不能换成附近同类物品");
            var collection = (Map<?, ?>) task.result(TaskState.FAILED).data().get("drop_collection");
            check(collection.get("unconfirmed_drop_refs").equals(List.of(ref)), "失效引用在默认结果中直接可见");
        }
    }

    private static void inventoryGainAloneCannotConfirmSelectedStack() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var selected = item(world, 170, new Vec3(7.5, 1, 3.5));
            var task = task(world, DroppedItemObservation.reference(world.player, selected.getUUID()));
            task.start(world.player); task.tick(world.player); clearNavigation(task);
            // 别人拿走目标后，同类物品从别处进包；不能把这两件无因果关系的事拼成已捡到指定目标。
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(selected.getId(), world.player.getId() + 1, 2));
            world.level.entities.remove(selected.getId()); world.inventory.setItem(0, new ItemStack(Items.BRICK, 2));
            for (int i = 0; i <= 20; i++) { check(task.tick(world.player) == TaskState.RUNNING, "给原生拾取包保留同步窗口"); world.nextTick(); }
            check(task.tick(world.player) == TaskState.FAILED && task.result(TaskState.FAILED).data().get("collected").equals(0),
                    "缺少本人的拾取证据必须如实未完成");
        }
    }

    private static CollectItemsCompanionTask task(InteractionWorldTestHarness world, String ref) {
        var args = new JsonObject(); args.addProperty("drop_ref", ref);
        return new CollectItemsCompanionTask(world.player, CollectItemsRequest.parse(args).task(world.player, new ToolContext("selection", 0)));
    }
    private static void interruptionKeepsConfirmedPartialPickup() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var selected = item(world, 180, new Vec3(7.5, 1, 3.5));
            String ref = DroppedItemObservation.reference(world.player, selected.getUUID());
            var task = task(world, ref); task.start(world.player); task.tick(world.player); clearNavigation(task);
            // 只装进一件就交回控制：已发生的本人拾取必须保留，剩余那件仍明确未完成。
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(selected.getId(), world.player.getId(), 1));
            selected.getItem().shrink(1); world.inventory.setItem(0, new ItemStack(Items.BRICK, 1));
            for (int read = 0; read < 2; read++) {
                var data = task.result(TaskState.CANCELLED).data();
                check(data.get("collected").equals(1) && data.get("collected_items").equals(Map.of("minecraft:brick", 1)),
                        "中断和重复读取不能丢掉或重复累计已确认的一件");
                check(((Map<?, ?>) data.get("drop_collection")).get("unconfirmed_drop_refs").equals(List.of(ref)), "部分入包仍不是整堆完成");
                var observations = (List<?>) ((Map<?, ?>) data.get("drop_collection")).get("observations");
                check(((JsonObject) observations.getFirst()).get("count").getAsInt() == 1, "中断回执同时保留地上尚余一件的最新事实");
            }
        }
    }
    private static ItemEntity item(InteractionWorldTestHarness world, int id, Vec3 at) throws Exception {
        var drop = ItemEntityReceiptsTest.item(world, id, at, new ItemStack(Items.BRICK, 2));
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(drop, BlockPos.containing(at));
        return drop;
    }
    private static void clearNavigation(CollectItemsCompanionTask task) throws Exception {
        // 夹具只检查选定的目标与正常收包，不启动后台路径线程；导航目标仍由生产几何代码计算。
        ActorControlTestHarness.field(AbstractCompanionTask.class, "nav").set(task, null);
    }
    private static Object call(CollectItemsCompanionTask task, String name) throws Exception {
        var method = CollectItemsCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

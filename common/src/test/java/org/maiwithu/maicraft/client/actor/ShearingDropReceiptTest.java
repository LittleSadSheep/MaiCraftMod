// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 用原生掉落观察夹具重放剪毛同步，不生成世界物品，也不把羊毛出现当成已拾取。 */
public final class ShearingDropReceiptTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var sheep = sheep(world);
            var receipt = ShearingDropReceipt.before(world.player, sheep);
            var drop = ItemEntityReceiptsTest.item(world, 401, new Vec3(5.5, 1, 5.5), new ItemStack(Items.WHITE_WOOL, 2));
            ItemEntityReceipts.entityAdded(world.player, world.level, drop.getId());
            check(!receipt.settle(world.player) && !ShearingDropReceipt.permits(world.player, drop),
                    "新物品包本身不能证明羊已被剪毛");
            sheep.shorn = true;
            settle(world, receipt);
            check(receipt.attributed() == 2 && ShearingDropReceipt.permits(world.player, drop),
                    "剪毛状态和出生回执齐全后保留精确UUID与数量");
            var other = ItemEntityReceiptsTest.item(world, 402, drop.position(), new ItemStack(Items.WHITE_WOOL, 2));
            check(!ShearingDropReceipt.permits(world.player, other), "同色同数量的另一UUID不能获得许可");
            nearbyDispatch(world, drop, other);
            drop.getItem().grow(1);
            check(!ShearingDropReceipt.permits(world.player, drop), "归属不明的增长不能沿用原数量许可");
            drop.getItem().shrink(1);
            world.level.time += 6001;
            check(!ShearingDropReceipt.permits(world.player, drop), "过期证据不会永久放行地面物品");
        }
        try (var world = new InteractionWorldTestHarness()) {
            var sheep = sheep(world);
            ItemEntityReceiptsTest.item(world, 411, new Vec3(5.5, 1, 5.5), new ItemStack(Items.WHITE_WOOL));
            var receipt = ShearingDropReceipt.before(world.player, sheep);
            var fresh = ItemEntityReceiptsTest.item(world, 412, new Vec3(5.5, 1, 5.5), new ItemStack(Items.WHITE_WOOL, 2));
            ItemEntityReceipts.entityAdded(world.player, world.level, fresh.getId());
            sheep.shorn = true; settle(world, receipt);
            check(receipt.attributed() == 0 && !ShearingDropReceipt.permits(world.player, fresh),
                    "剪毛前已有同色物品时不把可能混堆的产物全部归为本人");
        }
        try (var world = new InteractionWorldTestHarness()) {
            var sheep = sheep(world);
            var receipt = ShearingDropReceipt.before(world.player, sheep);
            var drop = ItemEntityReceiptsTest.item(world, 421, new Vec3(5.5, 1, 5.5), new ItemStack(Items.WHITE_WOOL));
            ItemEntityReceipts.entityAdded(world.player, world.level, drop.getId());
            sheep.shorn = true; settle(world, receipt);
            world.level.time = 0;
            check(!ShearingDropReceipt.permits(world.player, drop), "时间回退后旧归属证据失效");
            check(ShearingDropReceipt.before(world.player, sheep) == null, "已剪毛的羊不能创建新剪毛凭据");
        }
        System.out.println("ShearingDropReceiptTest: passed");
    }

    private static ObservedSheep sheep(InteractionWorldTestHarness world) throws Exception {
        // 不运行生物AI；只提供剪毛前后同步状态与原地包围盒，物品仍走原生观察记录。
        var sheep = world.h.allocate(ObservedSheep.class);
        sheep.setBoundingBox(new AABB(5, 1, 5, 6, 2, 6));
        world.inventory.setItem(0, new ItemStack(Items.SHEARS));
        return sheep;
    }

    @SuppressWarnings("unchecked")
    private static void nearbyDispatch(InteractionWorldTestHarness world,
                                       ItemEntity owned, ItemEntity other) throws Exception {
        // 通过真实附近来源分支交接到拾取任务，只替换执行器以免离线夹具产生导航动作。
        for (var item : List.of(owned, other)) ActorControlTestHarness.field(Entity.class, "blockPosition")
                .set(item, BlockPos.containing(item.position()));
        var registry = TaskFactory.class.getDeclaredField("RUNNERS"); registry.setAccessible(true);
        var runners = (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) registry.get(null);
        var previous = runners.get(CollectItemsTaskRecord.class);
        var captured = new CollectItemsTaskRecord[1];
        try {
            TaskFactory.register(CollectItemsTaskRecord.class, (player, request) -> {
                captured[0] = request;
                return new Task() {
                    public String name() { return "剪毛拾取调度夹具"; }
                    public TaskState tick(LocalPlayer ignored) {
                        return TaskState.RUNNING;
                    }
                    public void stop(LocalPlayer ignored, StopReason reason) {}
                };
            });
            var request = new SemanticAcquireTaskRecord("shearing-nearby", 1000,
                    List.of(ResourceLocation.withDefaultNamespace("white_wool")), 10,
                    List.of(SemanticAcquireTaskRecord.Source.NEARBY), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            var task = new SemanticAcquireCompanionTask(world.player, request);
            var start = SemanticAcquireCompanionTask.class.getDeclaredMethod("onStart"); start.setAccessible(true); start.invoke(task);
            var need = ActorControlTestHarness.field(SemanticAcquireCompanionTask.class, "rootNeed").get(task);
            var nearby = SemanticAcquireCompanionTask.class.getDeclaredMethod("attemptNearby", need.getClass());
            nearby.setAccessible(true); nearby.invoke(task, need);
            check(captured[0] != null && captured[0].targetUuids.equals(Set.of(owned.getUUID())),
                    "附近未知羊毛被跳过，确认产物按精确身份交给同一拾取子任务");
        } finally {
            if (previous == null) runners.remove(CollectItemsTaskRecord.class); else runners.put(CollectItemsTaskRecord.class, previous);
        }
    }

    private static void settle(InteractionWorldTestHarness world, ShearingDropReceipt receipt) throws Exception {
        check(!receipt.settle(world.player), "确认剪毛时仍等待掉落同步边界");
        for (int tick = 0; tick < 2; tick++) {
            world.nextTick(); check(!receipt.settle(world.player), "边界内不提前结算产物");
        }
        world.nextTick(); check(receipt.settle(world.player), "边界结束后结算本次观察");
    }

    private static final class ObservedSheep extends Sheep {
        boolean shorn;
        private ObservedSheep() { super(EntityType.SHEEP, null); }
        @Override public boolean isSheared() { return shorn; }
        @Override public boolean isBaby() { return false; }
        @Override public DyeColor getColor() { return DyeColor.WHITE; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

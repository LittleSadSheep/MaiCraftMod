// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** 重放附近取物、破坏点收取与背包同步；归属不拦动作，范围保护和实际入包证据仍有效。 */
public final class DroppedItemPickupTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        nearbyAcceptsEveryOwner();
        miningCollectsExistingAndMergedStacks(0, 1);
        miningCollectsExistingAndMergedStacks(2, 2);
        miningCollectsExistingAndMergedStacks(2, 5);
        foreignPickupWaitsForInventory();
        System.out.println("DroppedItemPickupTest: ownership-free pickup, protected scope and inventory receipts passed");
    }

    private static void nearbyAcceptsEveryOwner() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 同一范围混有来源未知、自身和其他实体的物品；只排除明确禁入格、冷却、类型和半径不符的目标。
            var unknown = item(world, 501, 2.5, 1, null);
            var self = item(world, 502, 3.5, 1, world.player);
            var foreign = item(world, 503, 4.5, 1, unknown);
            var protectedDrop = item(world, 504, 5.5, 1, unknown);
            var delayed = item(world, 505, 6.5, 1, unknown); delayed.setDefaultPickUpDelay();
            item(world, 506, 14.5, 1, unknown);
            var wrongType = item(world, 507, 7.5, 1, unknown); wrongType.stack = new ItemStack(Items.STONE);
            var record = new SemanticAcquireTaskRecord("nearby-owners", 1000,
                    List.of(ResourceLocation.withDefaultNamespace("brick")), 10,
                    List.of(SemanticAcquireTaskRecord.Source.NEARBY), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8);
            var task = new SemanticAcquireCompanionTask(world.player, record);
            Method survey = SemanticAcquireCompanionTask.class.getDeclaredMethod("surveyNearby", List.class);
            survey.setAccessible(true);
            NavigationSafetyContext.withForbiddenBodyCells(List.of(protectedDrop.blockPosition()), () -> {
                try {
                    Object result = survey.invoke(task, record.itemIds);
                    check(call(result, "safeUuids").equals(Set.of(unknown.getUUID(), self.getUUID(), foreign.getUUID())),
                            "附近拾取接纳所有归属，仅按真实范围与明确保护筛选");
                    check(call(result, "protectedCount").equals(1), "归属不再冒充保护区域问题");
                    return null;
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
        }
    }

    private static void miningCollectsExistingAndMergedStacks(int before, int after) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 挖掉同一格后，拾取新堆、未变化的旧堆或混堆；被别人投掷的身份不阻止角色靠近。
            ActorControlTestHarness.field(LivingEntity.class, "activeEffects").set(world.player, new HashMap<>());
            BlockPos source = new BlockPos(4, 1, 3);
            world.set(source, Blocks.DIRT.defaultBlockState());
            ObservedItem drop = before == 0 ? null : item(world, 511, 4.5, before, world.player);
            var record = new MineBlockTaskRecord("mine-old-stack", 1000, Set.of(Blocks.DIRT), 1,
                    "dirt", Set.of(Items.BRICK)).onlyAt(source, Blocks.DIRT.defaultBlockState());
            var task = new MineCompanionTask(world.player, record); task.start(world.player);
            world.set(source, Blocks.AIR.defaultBlockState());
            Method accepted = MineCompanionTask.class.getDeclaredMethod("acceptDigResult", BlockPos.class, BlockDigger.DigResult.class);
            accepted.setAccessible(true); accepted.invoke(task, source, BlockDigger.DigResult.BROKE_TARGET);
            if (drop == null) drop = item(world, 511, 4.5, after, world.player);
            else drop.stack.setCount(after);
            check(((List<?>) call(task, "droppedItems")).contains(drop.blockPosition()), "破坏点匹配物品成为原生拾取目标");
            check(call(task, "nearestLiveDrop") == drop, "旧堆和带归属的物品均可继续靠近");
            world.level.entities.remove(drop.getId()); world.inventory.setItem(0, new ItemStack(Items.BRICK, after));
            for (int tick = 0; tick < 13; tick++) world.nextTick();
            check(task.tick(world.player) == TaskState.SUCCESS && record.getMined() == after,
                    "混堆观察不会阻止同步材料达标后的采收成功");
            check(task.result(TaskState.SUCCESS).data().get("ambiguous_merged_drop_count").equals(after > before && before > 0 ? 1 : 0),
                    "来源不确定只保留为观察事实");
        }
    }

    private static void foreignPickupWaitsForInventory() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 角色接触其他实体所属物品后，先等实体消失与背包包到齐，不能把走近或消失直接当成收取。
            var other = item(world, 521, 7.5, 1, null);
            var drop = item(world, 522, .7, 2, other);
            var record = new CollectItemsTaskRecord("foreign-receipt", 1000, Set.of(Items.BRICK), 8,
                    "brick", Set.of(drop.getUUID()));
            var task = new CollectItemsCompanionTask(world.player, record); task.start(world.player);
            check(task.tick(world.player) == TaskState.RUNNING, "带其他实体归属的目标启动正常拾取");
            ActorControlTestHarness.field(AbstractCompanionTask.class, "nav").set(task, null);
            world.level.entities.remove(drop.getId());
            check(task.tick(world.player) == TaskState.RUNNING && record.getCollected() == 0, "实体消失仍等待实际入包");
            world.inventory.setItem(0, new ItemStack(Items.BRICK, 2));
            check(task.tick(world.player) == TaskState.RUNNING && task.tick(world.player) == TaskState.SUCCESS
                    && record.getCollected() == 2, "同步背包确认两件物品后才成功");
        }
    }

    private static ObservedItem item(InteractionWorldTestHarness world, int id, double x, int count, Entity owner) throws Exception {
        // 仅注入服务器已同步的物品状态；夹具不生成真实世界掉落，不修改生产逻辑或绕过原生拾取条件。
        var item = world.h.allocate(ObservedItem.class); item.stack = new ItemStack(Items.BRICK, count); item.owner = owner;
        Vec3 at = new Vec3(x, 1, 3.5);
        ActorControlTestHarness.field(Entity.class, "id").setInt(item, id);
        ActorControlTestHarness.field(Entity.class, "uuid").set(item, UUID.randomUUID());
        ActorControlTestHarness.field(Entity.class, "position").set(item, at);
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(item, BlockPos.containing(at));
        item.setBoundingBox(new AABB(x - .125, 1, 3.375, x + .125, 1.25, 3.625));
        world.level.entities.put(id, item); return item;
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static final class ObservedItem extends ItemEntity {
        ItemStack stack; Entity owner;
        private ObservedItem() { super(EntityType.ITEM, null); }
        @Override public ItemStack getItem() { return stack; }
        @Override public Entity getOwner() { return owner; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

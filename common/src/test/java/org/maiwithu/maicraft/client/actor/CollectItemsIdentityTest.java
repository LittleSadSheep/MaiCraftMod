// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 被动实体与背包同步夹具覆盖受限收取；不生成真实物品、不运行导航，也不将子拾取成功当原生加工证明。 */
public final class CollectItemsIdentityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 无游戏的Bootstrap不会加载原版数据包；只在本回归里补水标签，结束后恢复，避免把浅水误当未知危险流体。
        var fluids = net.minecraft.core.registries.BuiltInRegistries.FLUID;
        Map<net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>,List<net.minecraft.core.Holder<net.minecraft.world.level.material.Fluid>>> tags = new java.util.HashMap<>();
        fluids.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var previous = new java.util.HashMap<>(tags);
        tags.put(net.minecraft.tags.FluidTags.WATER,List.of(fluids.wrapAsHolder(net.minecraft.world.level.material.Fluids.WATER),fluids.wrapAsHolder(net.minecraft.world.level.material.Fluids.FLOWING_WATER)));
        fluids.bindTags(tags);
        try { scopedScanAndMerge(); continuedIdentity(); delayedContactReceipt(); shallowNudgeProtection(); }
        finally { fluids.bindTags(previous); }
        System.out.println("CollectItemsIdentityTest: UUID scan/follow/merge, pickup sync and shallow approach guards passed");
    }

    private static void scopedScanAndMerge() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var owned = item(world, 61, new Vec3(6.5, 1, 5.5), 1);
            var other = item(world, 62, new Vec3(2.5, 1, 3.5), 1);
            var ids = new HashSet<>(Set.of(owned.getUUID()));
            var record = new CollectItemsTaskRecord("scoped", 500, Set.of(Items.BRICK), 16, "brick", ids); ids.clear();
            var task = new CollectItemsCompanionTask(world.player, record); task.start(world.player);
            check(call(task, "nearestItem") == owned && record.targetUuids.contains(owned.getUUID()),
                    "内部目标集合防御冻结，另一堆更近也不能抢走已授权UUID");
            check(task.tick(world.player) == TaskState.RUNNING, "扫描只建立当前目标");
            clearUnstartedNav(task);
            observedCounts(task).put(other.getId(), 1); other.getItem().grow(1); world.level.entities.remove(owned.getId());
            check(!(boolean) call(task, "retargetProvenMerge"), "即使数量像原版合堆，也不能转向未授权幸存UUID");

            var legacy = new CollectItemsCompanionTask(world.player,
                    new CollectItemsTaskRecord("legacy", 500, Set.of(Items.BRICK), 16, "brick"));
            legacy.start(world.player);
            check(call(legacy, "nearestItem") == other, "旧公开构造继续按类型和距离扫描，不增加UUID限制");
            ActorControlTestHarness.field(CollectItemsCompanionTask.class, "pickup").set(legacy, NativePickupReceipt.begin(world.player, owned));
            observedCounts(legacy).put(other.getId(), 1);
            check((boolean) call(legacy, "retargetProvenMerge"), "未限定身份的旧合堆追踪行为保持兼容");
        }
    }

    private static void continuedIdentity() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var owned = item(world, 71, new Vec3(5.5, 1, 5.5), 2);
            var task = scoped(world, owned); task.tick(world.player); clearUnstartedNav(task);
            item(world, owned.getId(), owned.position(), 2);
            check(task.tick(world.player) == TaskState.FAILED, "数字实体ID被另一UUID复用后不能继续靠近");
        }
        try (var world = new InteractionWorldTestHarness()) {
            var owned = item(world, 72, new Vec3(5.5, 1, 5.5), 2);
            var task = scoped(world, owned); task.tick(world.player); clearUnstartedNav(task);
            owned.getItem().set(DataComponents.CUSTOM_NAME, Component.literal("不同组件"));
            check(task.tick(world.player) == TaskState.FAILED, "已授权UUID中的物品组件被替换也不能继续收取");
        }
    }

    private static void delayedContactReceipt() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            normalPlayerInfo(world);
            // 保存的浅水底脚位平移Y+60/Z+4，保持落地、在水中且未游泳的真实站姿条件。
            world.position(new Vec3(11.60711912175879, 1, 5.4699885244688387));
            world.set(new BlockPos(11, 1, 5), Blocks.WATER.defaultBlockState());
            ActorControlTestHarness.field(Entity.class, "wasTouchingWater").setBoolean(world.player, true);
            check(world.player.onGround() && world.player.isInWater() && !world.player.isSwimming(), "实机浅水站姿条件保留");
            var owned = item(world, 81, new Vec3(11.7, 1.2, 5.5), 2);
            var task = scoped(world, owned); task.tick(world.player); clearUnstartedNav(task);
            check(task.tick(world.player) == TaskState.RUNNING, "同格实际接触后的第一帧必须等待原生拾取，不能耗尽站位失败");
            world.nextTick();
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(owned.getId(), world.player.getId(), 2));
            world.level.entities.remove(owned.getId());
            for (int tick = 0; tick < 19; tick++) {
                check(task.tick(world.player) == TaskState.RUNNING, "Take后背包包稍晚仍在20刻同步窗口内"); world.nextTick();
            }
            world.inventory.setItem(0, new ItemStack(Items.BRICK, 2));
            check(task.tick(world.player) == TaskState.RUNNING && task.tick(world.player) == TaskState.SUCCESS,
                    "组件相符的背包同步到达后才结束通用收取");
            check(world.blockUses() == 0 && world.itemUses() == 0, "等待收取不能补料、修改世界或创建新物品操作");
        }
    }

    private static void shallowNudgeProtection() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            normalPlayerInfo(world);
            world.position(new Vec3(5.5, 1, 5.5)); var water = new BlockPos(5, 1, 5);
            world.set(water, Blocks.WATER.defaultBlockState());
            Method method = Class.forName("org.maiwithu.maicraft.core.task.collect.CollectItemsApproach")
                    .getDeclaredMethod("safeNudge", net.minecraft.client.player.LocalPlayer.class, Vec3.class); method.setAccessible(true);
            java.util.function.Supplier<Boolean> safe = () -> {
                try { return (boolean) method.invoke(null, world.player, new Vec3(5.8, 1.2, 5.5)); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            check(safe.get(), "有真实实底且头顶露出的浅水允许同格短靠近");
            check(!NavigationSafetyContext.withForbiddenBodyCells(List.of(water), safe), "短靠近同样继承父任务身体禁入格");
            world.set(water.below(), Blocks.WATER.defaultBlockState());
            check(!safe.get(), "看不到浅水实底时不能用直接前进绕过导航安全");
        }
    }

    private static CollectItemsCompanionTask scoped(InteractionWorldTestHarness world, ItemEntity entity) {
        var task = new CollectItemsCompanionTask(world.player,
                new CollectItemsTaskRecord("scoped", 500, Set.of(Items.BRICK), 16, "brick", Set.of(entity.getUUID())));
        task.start(world.player); return task;
    }
    private static void normalPlayerInfo(InteractionWorldTestHarness world) throws Exception {
        // Unsafe夹具未运行连接构造器；游泳判定会查旁观者资料，空HashMap模拟普通玩家尚无远端资料的正常状态。
        ActorControlTestHarness.field(net.minecraft.client.multiplayer.ClientPacketListener.class, "playerInfoMap")
                .set(world.player.connection, new java.util.HashMap<>());
        // 浅水靠近还会读取原版体型、惯性和边界；补齐正常静止站立的构造状态，不用缺失字段绕过真实碰撞验证。
        ActorControlTestHarness.field(Entity.class, "dimensions").set(world.player, net.minecraft.world.entity.EntityType.PLAYER.getDimensions());
        ActorControlTestHarness.field(Entity.class, "deltaMovement").set(world.player, Vec3.ZERO);
        ActorControlTestHarness.field(net.minecraft.world.level.Level.class, "worldBorder")
                .set(world.level, new net.minecraft.world.level.border.WorldBorder());
    }
    private static ItemEntity item(InteractionWorldTestHarness world, int id, Vec3 position, int count) throws Exception {
        var result = ItemEntityReceiptsTest.item(world, id, position, new ItemStack(Items.BRICK, count));
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(result, BlockPos.containing(position)); return result;
    }
    private static void clearUnstartedNav(CollectItemsCompanionTask task) throws Exception {
        // 本回归只验证目标与同步状态；尚未tick的导航不产生输入，因此移除夹具中的句柄即可隔离路径执行。
        ActorControlTestHarness.field(AbstractCompanionTask.class, "nav").set(task, null);
    }
    @SuppressWarnings("unchecked") private static Map<Integer, Integer> observedCounts(CollectItemsCompanionTask task) throws Exception {
        return (Map<Integer, Integer>) ActorControlTestHarness.field(CollectItemsCompanionTask.class, "firstObservedEntityCounts").get(task);
    }
    private static Object call(CollectItemsCompanionTask task, String name) throws Exception {
        Method method = CollectItemsCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

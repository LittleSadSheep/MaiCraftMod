// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import it.unimi.dsi.fastutil.longs.LongSets;

/** 主动丢弃的同一实体漂移、合堆和消失后更新寻路，普通旧掉落与机器投料不被一并封锁。 */
public final class DiscardedItemsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(8.5, 1, 8.5));
            var old = ItemEntityReceiptsTest.item(world, 80, new Vec3(5.5, 1, 5.5), new ItemStack(Items.COBBLESTONE, 4));
            var watch = DiscardedItems.watch(world.player, new ItemStack(Items.COBBLESTONE, 40), 40);
            DiscardedItems.observe(world.player);
            check(DiscardedItems.forbiddenBodyCells().isEmpty(), "existing unrelated items are not marked as discarded");
            var item = ItemEntityReceiptsTest.item(world, 81, new Vec3(10.5, 1, 10.5), new ItemStack(Items.COBBLESTONE, 40));
            ItemEntityReceipts.entityAdded(world.player, world.level, 81); DiscardedItems.observe(world.player);
            check(Boolean.TRUE.equals(watch.result().get("entity_observed")), "server spawn establishes an actual discarded entity");
            var frozen = EmbeddedBaritonePolicy.capture(LongSets.emptySet(), LongSets.emptySet(), NavigationSafetyContext.forbiddenBodyCells());
            check(frozen.forbidsBody(9, 1, 9), "diagonal pickup corner is avoided by the next search");
            check(!frozen.forbidsBody(8, 1, 8), "outside pickup reach remains navigable");
            // 水流改变实际落点，原标记移走；后台已冻结的搜索保持自己的快照，下一次策略刷新才接入新位置。
            ActorControlTestHarness.field(Entity.class, "position").set(item, new Vec3(13.5, 3, 13.5));
            item.setBoundingBox(new AABB(13.375, 3, 13.375, 13.625, 3.25, 13.625));
            DiscardedItems.observe(world.player);
            check(!NavigationSafetyContext.forbidsBody(new BlockPos(9, 1, 9)), "old pickup area is released after drift");
            check(NavigationSafetyContext.forbidsBody(new BlockPos(13, 1, 13)), "player head below a raised item still has pickup reach");
            check(frozen.forbidsBody(9, 1, 9) && !frozen.forbidsBody(13, 1, 13), "worker policy never mutates underneath a search");
            var receiver = ItemEntityReceiptsTest.item(world, 82, new Vec3(13.8, 3, 13.5), new ItemStack(Items.COBBLESTONE, 5));
            DiscardedItems.observe(world.player);
            world.level.entities.remove(81); receiver.getItem().grow(40); DiscardedItems.observe(world.player);
            world.level.time += 6; DiscardedItems.observe(world.player);
            check(NavigationSafetyContext.forbidsBody(new BlockPos(13, 1, 13)), "merged receiving stack inherits avoidance");
            // 重载同一掉落物时只保留 UUID、换掉临时编号，仍需跟踪它；随后原生消失才解除禁入。
            world.level.entities.remove(82);
            var reloaded = ItemEntityReceiptsTest.item(world, 83, receiver.position(), receiver.getItem());
            ActorControlTestHarness.field(Entity.class, "uuid").set(reloaded, receiver.getUUID());
            world.level.time += 6; DiscardedItems.observe(world.player);
            check(NavigationSafetyContext.forbidsBody(new BlockPos(13, 1, 13)), "UUID survives an entity ID change after reload");
            world.level.entities.remove(83); world.level.time += 6; DiscardedItems.observe(world.player);
            check(DiscardedItems.forbiddenBodyCells().isEmpty(), "removed items leave no permanent blocked route");
            // 丢出的物品直接并入既有堆时，精确的增长也应登记；断线会释放这一世界的所有标记。
            DiscardedItems.watch(world.player, new ItemStack(Items.COBBLESTONE, 2), 2); old.getItem().grow(2);
            DiscardedItems.observe(world.player);
            check(NavigationSafetyContext.forbidsBody(new BlockPos(5, 1, 5)), "direct merge is tracked even without a new UUID");
            DiscardedItems.observe(null);
            check(DiscardedItems.forbiddenBodyCells().isEmpty(), "world or body boundary clears discard memory");
        } finally { DiscardedItems.observe(null); }
        System.out.println("DiscardedItemsTest: pickup footprint, drift, merge, expiry and world isolation passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

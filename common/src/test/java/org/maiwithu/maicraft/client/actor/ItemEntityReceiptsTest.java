// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 用被动实体与包夹具检查身份归因；夹具不会调用真实掉落生成或拾取动作，也不把背包净增充作回执。 */
public final class ItemEntityReceiptsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var item = item(world, 71, new Vec3(5.5, 1, 5.5), new ItemStack(Items.QUARTZ, 4));
            UUID uuid = item.getUUID(); long cursor = ItemEntityReceipts.cursor(world.player);
            ItemEntityReceipts.entityAdded(world.player, world.level, item.getId());
            check(ItemEntityReceipts.spawnedAfter(world.player, uuid, cursor), "the server spawn observation preserves UUID identity");
            var snapshot = ItemEntityReceipts.snapshot(world.player, new AABB(5, 1, 5, 6, 2, 6)).getFirst();
            snapshot.stack().shrink(3);
            check(item.getItem().getCount() == 4, "readers cannot mutate the live item through an observation");
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(71, world.player.getId() + 1, 3));
            check(ItemEntityReceipts.pickedUp(world.player, uuid, cursor) == 0, "another or unresolved collector never falls back to the local player");
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(71, world.player.getId(), 2));
            world.level.entities.remove(71);
            check(ItemEntityReceipts.pickedUp(world.player, uuid, cursor) == 2, "pickup evidence survives the original entity disappearing");
            world.level.time += 1201;
            check(ItemEntityReceipts.pickedUp(world.player, uuid, cursor) == 0, "old evidence expires instead of growing without bounds");
            var sameUuid = item(world, 71, new Vec3(5.5, 1, 5.5), new ItemStack(Items.QUARTZ, 4));
            ActorControlTestHarness.field(Entity.class, "uuid").set(sameUuid, uuid);
            ItemEntityReceipts.observeWorld(null);
            long newSession = ItemEntityReceipts.cursor(world.player);
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(71, world.player.getId(), 1));
            check(ItemEntityReceipts.pickedUp(world.player, uuid, cursor) == 0, "a new body or world cannot reuse old pickup evidence");
            check(ItemEntityReceipts.pickedUp(world.player, uuid, newSession) == 1, "the current session can still attribute a newly observed pickup");
        }
        System.out.println("ItemEntityReceiptsTest: local collector UUID attribution, defensive snapshots and expiry passed");
    }

    public static ItemEntity item(InteractionWorldTestHarness world, int id, Vec3 position, ItemStack stack) throws Exception {
        // 和现有拾取几何测试一样使用不运行构造器的内存夹具；不给世界生成实体，也不设置投掷速度。
        var item = world.h.allocate(ObservedItem.class); item.stack = stack.copy();
        ActorControlTestHarness.field(Entity.class, "id").setInt(item, id);
        ActorControlTestHarness.field(Entity.class, "uuid").set(item, UUID.randomUUID());
        ActorControlTestHarness.field(Entity.class, "position").set(item, position);
        ActorControlTestHarness.field(Entity.class, "onGround").setBoolean(item, true);
        item.setBoundingBox(new AABB(position.x - .125, position.y, position.z - .125, position.x + .125, position.y + .25, position.z + .125));
        world.level.entities.put(id, item); return item;
    }

    private static final class ObservedItem extends ItemEntity {
        ItemStack stack;
        private ObservedItem() { super(EntityType.ITEM, null); }
        @Override public ItemStack getItem() { return stack; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

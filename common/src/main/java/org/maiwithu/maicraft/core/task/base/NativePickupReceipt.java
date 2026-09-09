// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.PlayerInv;

/**
 * 保存一次靠近掉落物之前的物品样式与背包数量，再判断实体消失后背包是否相应增加。
 * 这比“走到了就算捡到”更具体，但仍是客户端观察组合；同种物品的其他增减与合堆可能影响判断。
 */
public final class NativePickupReceipt {

    public enum State {
        /** The tracked entity is still loaded. */
        LIVE,
        /** It disappeared; allow the inventory packet a bounded synchronization window. */
        AWAITING_INVENTORY_SYNC,
        /** Entity disappearance and a full synchronized inventory delta agree. */
        RECEIVED,
        /** The synchronization window elapsed without a matching inventory delta. */
        DISAPPEARED_WITHOUT_RECEIPT
    }

    private final int entityId;
    private final ItemStack prototype;
    private final int inventoryBefore;
    private int largestObservedStack;
    private long missingSince = Long.MIN_VALUE;

    private NativePickupReceipt(LocalPlayer player, ItemEntity entity) {
        this.entityId = entity.getId();
        this.prototype = entity.getItem().copy();
        this.inventoryBefore = carriedMatching(player, prototype);
        this.largestObservedStack = entity.getItem().getCount();
    }

    public static NativePickupReceipt begin(LocalPlayer player, ItemEntity entity) {
        return new NativePickupReceipt(player, entity);
    }

    /** Resolve the same entity identity in the current synchronized client world. */
    public ItemEntity liveEntity(LocalPlayer player) {
        Entity entity = player.clientLevel.getEntity(entityId);
        return entity instanceof ItemEntity itemEntity && !itemEntity.isRemoved()
                ? itemEntity : null;
    }

    /** Observe one tick of entity/inventory synchronization. */
    // 实体还在就继续等；看不到实体后，要求同种同组件物品的背包增加量达到最大观察堆量。
    // 背包暂未对上时再给一段同步等待，不立刻把消失当成拾取。
    public State poll(LocalPlayer player, int inventorySyncTicks) {
        ItemEntity live = liveEntity(player);
        if (live != null) {
            largestObservedStack = Math.max(largestObservedStack, live.getItem().getCount());
            missingSince = Long.MIN_VALUE;
            return State.LIVE;
        }

        long now = player.level().getGameTime();
        if (missingSince == Long.MIN_VALUE) missingSince = now;
        if (inventoryGain(player) >= largestObservedStack) return State.RECEIVED;
        return now - missingSince < Math.max(0, inventorySyncTicks)
                ? State.AWAITING_INVENTORY_SYNC
                : State.DISAPPEARED_WITHOUT_RECEIPT;
    }

    /** Positive main-inventory delta for the tracked item and data-component identity. */
    public int inventoryGain(LocalPlayer player) {
        return Math.max(0, carriedMatching(player, prototype) - inventoryBefore);
    }

    /** Quantity safely attributable to this entity when {@link State#RECEIVED}. */
    // 最多按记录里的最大观察数量报告，避免背包别处的大幅增加直接放大本次数字。
    public int confirmedUnits(LocalPlayer player) {
        return Math.min(largestObservedStack, inventoryGain(player));
    }

    /** Largest complete stack size observed before this entity disappeared. */
    public int expectedUnits() {
        return largestObservedStack;
    }

    /** Whether another live entity can be the same component-sensitive stack after a merge. */
    public boolean sameStackKind(ItemEntity candidate) {
        return candidate != null
                && ItemStack.isSameItemSameComponents(
                        prototype, candidate.getItem());
    }

    /** Non-mutating form used as a navigator's reached predicate. */
    public boolean received(LocalPlayer player) {
        return liveEntity(player) == null && inventoryGain(player) >= largestObservedStack;
    }

    /**
     * Mirrors the exact candidate box used by vanilla {@code Player#aiStep} when
     * it invokes {@code ItemEntity#playerTouch}; this is evidence that pickup was
     * actually attempted, not a guessed spherical radius.
     */
    // 按原版拾取查询的身体范围判断是否接触；乘坐时还合并载具范围，不只是比较两点距离。
    public static boolean insideVanillaTouchEnvelope(LocalPlayer player, ItemEntity item) {
        AABB touchBox;
        if (player.isPassenger() && player.getVehicle() != null) {
            touchBox = player.getBoundingBox().minmax(player.getVehicle().getBoundingBox())
                    .inflate(1.0D, 0.0D, 1.0D);
        } else {
            touchBox = player.getBoundingBox().inflate(1.0D, 0.5D, 1.0D);
        }
        return touchBox.intersects(item.getBoundingBox());
    }

    /**
     * Concrete local capacity evidence for a pickup. A full inventory is only
     * reported when no main-inventory slot can accept any of this exact stack.
     */
    // 只要有一个空格或同种堆叠还能加一件就返回 true；表示能接收一部分，不保证整堆全装得下。
    public static boolean canAccept(LocalPlayer player, ItemStack wanted) {
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack existing = player.getInventory().items.get(slot);
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, wanted)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), wanted.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private static int carriedMatching(LocalPlayer player, ItemStack wanted) {
        int count = 0;
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack existing = player.getInventory().items.get(slot);
            if (!existing.isEmpty()
                    && ItemStack.isSameItemSameComponents(existing, wanted)) {
                count += existing.getCount();
            }
        }
        return count;
    }

    /** Total number of items in the main inventory, useful when a spawned drop
     * is absorbed before the client ever renders an ItemEntity. */
    public static int carriedUnits(LocalPlayer player) {
        int total = 0;
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!stack.isEmpty()) total += stack.getCount();
        }
        return total;
    }
}

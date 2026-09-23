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
        /** 正在跟踪的掉落实体仍处于加载状态。 */
        LIVE,
        /** 实体已消失；留出有界时间等待背包数据包同步。 */
        AWAITING_INVENTORY_SYNC,
        /** 实体消失与完整的同步背包增量相互吻合。 */
        RECEIVED,
        /** 同步窗口已结束，但没有出现匹配的背包增量。 */
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

    /** 在当前已同步客户端世界中解析同一实体身份。 */
    public ItemEntity liveEntity(LocalPlayer player) {
        Entity entity = player.clientLevel.getEntity(entityId);
        return entity instanceof ItemEntity itemEntity && !itemEntity.isRemoved()
                ? itemEntity : null;
    }

    /** 观察一刻内实体与背包的同步变化。 */
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

    /** 与目标物品及数据组件身份匹配的主背包正向增量。 */
    public int inventoryGain(LocalPlayer player) {
        return Math.max(0, carriedMatching(player, prototype) - inventoryBefore);
    }

    /** 状态为 {@link State#RECEIVED} 时，可安全归属此实体的物品数量。 */
    // 最多按记录里的最大观察数量报告，避免背包别处的大幅增加直接放大本次数字。
    public int confirmedUnits(LocalPlayer player) {
        return Math.min(largestObservedStack, inventoryGain(player));
    }

    /** 实体消失前观察到的最大完整堆叠数量。 */
    public int expectedUnits() {
        return largestObservedStack;
    }

    /** 是否存在另一仍存活的实体，可能是按数据组件区分后与该堆叠合并的物品。 */
    public boolean sameStackKind(ItemEntity candidate) {
        return candidate != null
                && ItemStack.isSameItemSameComponents(
                        prototype, candidate.getItem());
    }

    /** 不修改状态的检测形式，供导航器的到达判定使用。 */
    public boolean received(LocalPlayer player) {
        return liveEntity(player) == null && inventoryGain(player) >= largestObservedStack;
    }

    /**
     * 与原版 {@code Player#aiStep} 调用 {@code ItemEntity#playerTouch} 时使用的候选箱完全一致；
     * 这能证明游戏确实尝试拾取，而非根据猜测的球形半径推断。
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
     * 判断当前是否有实际槽位可接收该掉落物。只有主背包所有槽位都无法接纳此精确堆叠中的任何物品时，才报告背包已满。
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

    /** 主背包中的物品总数；当新掉落在客户端渲染 ItemEntity 前就已被吸收时，可通过此值确认拾取。 */
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

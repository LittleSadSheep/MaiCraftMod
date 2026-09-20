// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import net.minecraft.world.level.material.FluidState;

/** 一次投掷的来源扣减和接收区实体增量必须同刻吻合；只保存观察，不生成掉落或将附近整堆认作自有材料。 */
final class TargetedDropReceipt implements NativeConfirmation {
    record Received(ItemEntityReceipts.ObservedDrop observed, int count) {}
    private final LocalPlayer player;
    private final Object world;
    private final BlockPos receiver;
    private final TargetedDropRegion region;
    private final ItemStack before, after, kind;
    private final int selected, inventoryBefore, amount;
    private final FluidState receiverFluid;
    private final long cursor;
    private final Map<UUID, ItemEntityReceipts.ObservedDrop> baseline = new LinkedHashMap<>();
    private List<Received> candidate = List.of();

    TargetedDropReceipt(LocalPlayer player, BlockPos receiver, ItemStack selectedStack, int amount) {
        this(player,receiver,selectedStack,amount,TargetedDropRegion.ofCells(List.of(receiver)));
    }

    TargetedDropReceipt(LocalPlayer player, BlockPos receiver, ItemStack selectedStack, int amount, TargetedDropRegion region) {
        this.player = player; world = player.level(); this.receiver = receiver;
        this.region = region;
        before = selectedStack.copy(); after = before.copyWithCount(before.getCount() - amount); kind = before.copyWithCount(1);
        selected = player.getInventory().selected; inventoryBefore = count(player, kind); this.amount = amount;
        receiverFluid = player.level().getFluidState(receiver);
        for (var observed : ItemEntityReceipts.snapshot(player, region.observationBounds())) baseline.put(observed.uuid(), observed);
        cursor = ItemEntityReceipts.cursor(player);
    }

    @Override public Verdict observe(LocalPlayerContext context) {
        if (context.player() != player || player.level() != world || !player.level().isLoaded(receiver)) return Verdict.DIVERGED;
        ItemStack source = player.getInventory().getItem(selected);
        int remaining = count(player, kind);
        if ((!ItemStack.matches(source, before) && !ItemStack.matches(source, after))
                || remaining != inventoryBefore && remaining != inventoryBefore - amount) return Verdict.DIVERGED;
        if (!ItemStack.matches(source, after) || remaining != inventoryBefore - amount) return Verdict.PENDING;
        var received = new ArrayList<Received>(); int credited = 0;
        for (var observed : ItemEntityReceipts.snapshot(player, region.observationBounds())) {
            // 包围盒只帮助找到候选实体；落点必须逐格属于冻结域，也必须满足原生反应邻域交集。
            if (!region.contains(observed.position())) continue;
            if (!ItemStack.isSameItemSameComponents(observed.stack(), kind)) continue;
            var previous = baseline.get(observed.uuid());
            if (previous != null && !ItemStack.isSameItemSameComponents(previous.stack(), kind)) return Verdict.DIVERGED;
            if (previous == null && !ItemEntityReceipts.spawnedAfter(player, observed.uuid(), cursor)) continue;
            int increase = observed.stack().getCount() - (previous == null ? 0 : previous.stack().getCount());
            if (increase <= 0) continue;
            var live = player.clientLevel.getEntity(observed.entityId());
            // 飞过接收区上空还不算到货；必须真实落地或进入液体，且编号仍对应这份 UUID。
            if (!(live instanceof ItemEntity item) || !item.getUUID().equals(observed.uuid())
                    || !item.onGround() && !item.isInWater() && !item.isInLava()) continue;
            // 接收格原本是流体时，实际中心必须进入相容的源流同族流体；靠在池沿但尚未入水的实体继续等待。
            BlockPos actual = BlockPos.containing(observed.position());
            if (!receiverFluid.isEmpty() && (!player.level().isLoaded(actual)
                    || !receiverFluid.getType().isSame(player.level().getFluidState(actual).getType()))) continue;
            credited += increase; received.add(new Received(observed, increase));
        }
        if (credited > amount) return Verdict.DIVERGED;
        if (credited != amount) return Verdict.PENDING;
        candidate = List.copyOf(received); return Verdict.APPLIED;
    }

    @Override public int stableTicksRequired() { return 1; }
    List<Received> received() { return candidate; }
    int amount() { return amount; }
    int observedDebit() { return Math.max(0, inventoryBefore - count(player, kind)); }

    static int count(LocalPlayer player, ItemStack kind) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, kind)) count += stack.getCount();
        }
        return count;
    }
}

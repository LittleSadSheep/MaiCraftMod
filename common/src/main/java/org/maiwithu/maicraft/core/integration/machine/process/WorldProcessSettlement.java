// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;

/** 冻结本批真实成品，再以同UUID本人拾取与完整组件库存增量结算；暂停不能把已确认历史降回待生成状态。 */
final class WorldProcessSettlement {
    private final LocalPlayer player;
    private final WorldProcessRecipe recipe;
    private final WorldProcessInventory inventory;
    private final long cursor;
    private final boolean nativeEvents;
    private WorldProcessEventEvidence.Output output;
    private boolean ready, collected;

    WorldProcessSettlement(LocalPlayer player, WorldProcessRecipe recipe, WorldProcessInventory inventory,
                           long cursor, boolean nativeEvents) {
        this.player = player; this.recipe = recipe; this.inventory = inventory;
        this.cursor = cursor; this.nativeEvents = nativeEvents;
    }

    WorldProcessEventEvidence.Output output() { return output; }
    boolean ready() { return ready; }

    void acceptNative(WorldProcessEventEvidence.Output found) {
        if (!nativeEvents) throw new IllegalStateException("world_process_native_event_mode_missing");
        if (found != null) freeze(found);
    }

    void observe(List<ItemEntityReceipts.ObservedDrop> drops, boolean inputsGone) {
        if (!inputsGone) return;
        // 纯客户端只能认领实际看见的新成品；不会根据背包增长或未知位置的拾取包反推出一次原生加工。
        if (!nativeEvents && output == null && drops.size() == 1) {
            var seen = drops.getFirst();
            if (ItemEntityReceipts.spawnedAfter(player, seen.uuid(), cursor) && ItemStack.matches(recipe.result(), seen.stack()))
                freeze(new WorldProcessEventEvidence.Output(seen.uuid(), seen.stack()));
        }
        if (output == null) return;
        var actual = drops.stream().filter(drop -> drop.uuid().equals(output.uuid())).findFirst().orElse(null);
        if (actual != null && !ItemStack.matches(actual.stack(), output.stack()))
            throw new IllegalStateException("world_process_output_changed");
        // 原生事件已证明真实生成；若暂停期间实体已被本人拾走，同UUID的Take回执可接替仍在地上的实体。
        if (actual != null || pickupAmount() > 0) ready = true;
    }

    int pickupAmount() {
        if (output == null) return 0;
        int amount = 0;
        for (var pickup : ItemEntityReceipts.pickups(player, cursor)) {
            if (!pickup.uuid().equals(output.uuid())) continue;
            ItemStack stack = pickup.stack();
            // 拾取包可能先于组件同步；已有原生/现场成品快照可补证，但明确不同的组件必须拒绝。
            if (!stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, output.stack()))
                throw new IllegalStateException("world_process_pickup_components_changed");
            amount = Math.addExact(amount, pickup.amount());
        }
        if (amount > output.stack().getCount()) throw new IllegalStateException("world_process_unexpected_pickup_amount");
        return amount;
    }

    boolean collect() {
        if (collected) return true;
        if (!ready || output == null) return false;
        int amount = pickupAmount();
        // 单独的背包净增、别人的拾取，以及尚未收到的背包同步都不能完成这一批。
        collected = amount == output.stack().getCount() && inventory.collected(output.stack(), amount);
        return collected;
    }

    private void freeze(WorldProcessEventEvidence.Output found) {
        if (output != null && (!output.uuid().equals(found.uuid()) || !ItemStack.matches(output.stack(), found.stack())))
            throw new IllegalStateException("world_process_multiple_native_outputs");
        output = new WorldProcessEventEvidence.Output(found.uuid(), found.stack());
        inventory.expectOutput(output.stack());
    }
}

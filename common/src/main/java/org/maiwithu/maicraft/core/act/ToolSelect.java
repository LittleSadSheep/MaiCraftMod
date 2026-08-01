package org.maiwithu.maicraft.core.act;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The two "swap the best implement into the main hand" helpers, unified from
 * the duplicated block-breaking and combat inventory scans.
 *
 * <p>The scan covers the usable main inventory and returns a slot decision only.
 * Selection and main-inventory staging are synchronized transactions owned across ticks by the
 * caller through the client action ports; this class never mutates inventory state.
 */
public final class ToolSelect {

    private ToolSelect() {}

    /**
     * Hold the best implement for breaking {@code state}: among tools that beat
     * the bare hand, one that actually harvests the block (correct tier when the
     * block gates its drops) outranks a merely faster one.
     */
    public static int holdBestTool(LocalPlayer p, BlockState state) {
        return bestSlot(p, state);
    }

    /**
     * Return the best inventory slot. Slots 9..35 require an asynchronous MenuPort staging receipt
     * before a native action may use them.
     */
    public static int bestSlot(LocalPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        boolean tierGated = state.requiresCorrectToolForDrops();
        int harvest = -1, any = -1;
        float harvestSpeed = 1.0f, anySpeed = 1.0f;
        int usableSlots = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < usableSlots; i++) {
            ItemStack s = inv.getItem(i);
            float spd = s.getDestroySpeed(state);
            if (spd <= 1.0f) continue;
            if (!tierGated || s.isCorrectToolForDrops(state)) {
                if (spd > harvestSpeed) { harvestSpeed = spd; harvest = i; }
            } else if (spd > anySpeed) {
                anySpeed = spd;
                any = i;
            }
        }
        return harvest >= 0 ? harvest : any;
    }
}

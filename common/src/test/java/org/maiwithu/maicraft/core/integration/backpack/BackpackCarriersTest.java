// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import java.util.List;
import java.util.Map;

/** 穿戴与饰品位置属于模组的库存处理器，不能把其slot零号当作玩家快捷栏零号。 */
public final class BackpackCarriersTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(BackpackCarriers.Carrier.vanilla(8).vanillaSlot() == 8 && BackpackCarriers.Carrier.vanilla(40).vanillaSlot() == 40,
                "existing main and offhand references retain their native inventory mapping");
        var armor = new BackpackCarriers.Carrier("armor", "", 0);
        var curios = new BackpackCarriers.Carrier("curios", "back", 0);
        check(armor.vanillaSlot() == -1 && curios.vanillaSlot() == -1 && !armor.key().equals(curios.key()),
                "worn slots preserve handler and identifier instead of aliasing the hotbar");
        var request = new BackpackSupplyTaskRecord("worn", 1000, curios, BackpackSupplyTaskRecord.Operation.OBSERVE, List.of(), 0, Map.of());
        check(request.carrier.equals(curios) && request.backpackSlot == -1, "worn requests retain their native address without an invented vanilla slot");
        try { BackpackCarriers.Carrier.vanilla(38); throw new AssertionError("armor cannot masquerade as a hand slot"); }
        catch (IllegalArgumentException expected) { }
        var original = new ItemStack(Items.CHEST); var entry = new BackpackCarriers.Entry(armor, original);
        original.setCount(3); entry.stack().setCount(4);
        check(entry.stack().getCount() == 1, "observed carrier stacks are isolated snapshots");
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.STONE));
            check(BackpackCarriers.current(h.player, BackpackCarriers.Carrier.vanilla(0)).is(Items.STONE), "main slot lookup reads the live carried item");
            check(BackpackCarriers.observe(h.player).entries().isEmpty() && h.itemUses() == 0 && h.blockUses() == 0,
                    "optional-mod discovery never opens a menu or treats ordinary items as backpacks");
        }
        System.out.println("BackpackCarriersTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

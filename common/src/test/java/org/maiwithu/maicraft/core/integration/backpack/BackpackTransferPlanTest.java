// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 精确搬运先合并已有物品；不能把升级槽、无限图标或其他物品当作可搬的当前需求。 */
public final class BackpackTransferPlanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var storage = new SimpleContainer(2); storage.setItem(0, new ItemStack(Items.QUARTZ)); storage.getItem(0).setCount(512);
            var menu = new Fixture(h.player, storage); h.player.containerMenu = menu; menu.setCarried(ItemStack.EMPTY);
            var view = BackpackMenuAccess.capture(h.player, menu, "fixture", ItemStack.EMPTY, 2, index -> index < 2, index -> false, index -> false);
            var quartz = BuiltInRegistries.ITEM.getKey(Items.QUARTZ);
            h.inventory.setItem(0, new ItemStack(Items.QUARTZ, 60));
            var take = BackpackTransferPlan.next(h.player, view, false, Map.of(quartz, 15));
            check(take.move().from() == 0 && take.move().to() == 2 && take.move().count() == 4, "fill existing player stack before consuming an empty slot");
            h.inventory.setItem(0, new ItemStack(Items.QUARTZ, 15));
            var deposit = BackpackTransferPlan.next(h.player, view, true, Map.of(quartz, 10));
            check(deposit.move().from() == 2 && deposit.move().to() == 0 && deposit.move().count() == 10, "upgraded storage may merge above ordinary item maximum");
            for (int i = 0; i < 36; i++) h.inventory.setItem(i, new ItemStack(Items.STONE, 64));
            check("inventory_full".equals(BackpackTransferPlan.next(h.player, view, false, Map.of(quartz, 1)).blocked()), "known stock with no destination is capacity failure");
            var infinite = BackpackMenuAccess.capture(h.player, menu, "infinite", ItemStack.EMPTY, 2, index -> index < 2, index -> false, index -> index == 0);
            check("backpack_infinite_transfer_unverified".equals(BackpackTransferPlan.next(h.player, infinite, false, Map.of(quartz, 1)).blocked()),
                    "infinite source is an unsupported transfer, not zero observed stock");
            menu.setCarried(new ItemStack(Items.DIAMOND));
            check("menu_or_cursor_changed".equals(BackpackTransferPlan.next(h.player, view, false, Map.of(quartz, 1)).blocked()), "foreign cursor prevents any new transfer");
        }
        System.out.println("BackpackTransferPlanTest: passed");
    }
    private static final class Fixture extends AbstractContainerMenu {
        Fixture(Player player, SimpleContainer storage) {
            super(MenuType.GENERIC_9x3, 8);
            for (int i = 0; i < 2; i++) addSlot(new Slot(storage, i, 0, i * 18) {
                @Override public int getMaxStackSize(ItemStack stack) { return 1024; }
            });
            for (int i = 0; i < 36; i++) addSlot(new Slot(player.getInventory(), i, 0, i * 18));
        }
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        public boolean stillValid(Player player) { return true; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

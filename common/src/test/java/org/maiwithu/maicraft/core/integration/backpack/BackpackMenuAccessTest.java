// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 验证读取的槽位角色和数量边界；真实精妙类的API兼容性另由安装版本核对。 */
public final class BackpackMenuAccessTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var storage = new SimpleContainer(3) { @Override public int getMaxStackSize() { return 1024; } };
            storage.setItem(0, new ItemStack(Items.QUARTZ, 512)); storage.setItem(1, new ItemStack(Items.QUARTZ, 3));
            // 普通夹具容器会钳制叠数；在读取前构造大堆叠槽读数，单独验证观察层不再按64裁剪。
            storage.getItem(0).setCount(512);
            storage.setItem(2, new ItemStack(Items.TORCH, 2));
            var upgrade = new SimpleContainer(1); upgrade.setItem(0, new ItemStack(Items.REDSTONE, 64));
            h.inventory.setItem(0, new ItemStack(Items.QUARTZ, 10));
            var menu = new FixtureMenu(h.player, storage, upgrade);
            // 无UUID的旧物品只给菜单临时身份；读取前后组件保持原样，不触发模组迁移写回。
            var oldStack = new ItemStack(Items.LEATHER_CHESTPLATE); var before = oldStack.copy();
            String identity = BackpackMenuAccess.storedIdentity(oldStack, menu);
            check(identity.equals(BackpackMenuAccess.storedIdentity(oldStack, menu))
                    && ItemStack.isSameItemSameComponents(oldStack, before), "read-only fallback identity remains stable within one menu");
            var view = BackpackMenuAccess.capture(h.player, menu, "test-backpack", new ItemStack(Items.LEATHER_CHESTPLATE), 3,
                    slot -> slot < 3, slot -> slot == 1, slot -> slot == 0);
            // 存储升级的大堆叠不能按普通64上限截断；被锁格仍可观察，但不能算作可取材料。
            var quartz = ResourceLocation.parse("minecraft:quartz");
            check(view.stored().get(quartz) == 515 && view.extractable().get(quartz) == 512, "stored and extractable counts remain distinct");
            check(!view.stored().containsKey(ResourceLocation.parse("minecraft:redstone")), "upgrade contents are excluded from main storage");
            check(view.playerSlots().size() == 36 && view.playerSlots().get(0) == 4 && view.infiniteSlots().contains(0),
                    "native player slot mapping and infinite markers are retained separately");
            storage.setItem(0, new ItemStack(Items.QUARTZ, 1));
            check(view.stored().get(quartz) == 515, "later storage changes do not rewrite the observed quantity");
            try {
                BackpackMenuAccess.capture(h.player, menu, "bad-layout", ItemStack.EMPTY, 4, slot -> slot < 3, slot -> false, slot -> false);
                throw new AssertionError("a partial layout cannot be reported as complete empty stock");
            } catch (IllegalArgumentException expected) { }
            check(!BackpackMenuAccess.supports(menu) && "not_open".equals(BackpackMenuAccess.read(h.player).status()),
                    "a lookalike or ordinary menu cannot claim the optional native backpack provider");
            check(h.blockUses() == 0 && h.itemUses() == 0, "inventory observation never opens or mutates a container");
        }
        System.out.println("BackpackMenuAccessTest: passed");
    }
    private static final class FixtureMenu extends AbstractContainerMenu {
        FixtureMenu(Player player, SimpleContainer storage, SimpleContainer upgrade) {
            super(MenuType.GENERIC_9x3, 8);
            for (int slot = 0; slot < 3; slot++) addSlot(new Slot(storage, slot, slot * 18, 0));
            addSlot(new Slot(upgrade, 0, 0, 20));
            for (int slot = 0; slot < 36; slot++) addSlot(new Slot(player.getInventory(), slot, (slot % 9) * 18, 40 + (slot / 9) * 18));
        }
        public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        public boolean stillValid(Player player) { return true; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

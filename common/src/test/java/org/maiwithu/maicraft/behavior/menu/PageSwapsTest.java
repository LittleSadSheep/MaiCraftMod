// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 服务端换页：认领的那一页不在了以后，此刻开着的界面接不接着用，只看打开者声明的换法。
 * 界面被关上（回到背包界面）、还是原来那一份、声明不认的界面，都不跟。真实客户端的换页时序留给实机验收。
 */
class PageSwapsTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 只有编号的界面替身：换页判断只认对象本身。 */
    private static AbstractContainerMenu page(int containerId) {
        return new AbstractContainerMenu(null, containerId) {
            @Override public ItemStack quickMoveStack(Player player, int slot) {
                return ItemStack.EMPTY;
            }

            @Override public boolean stillValid(Player player) {
                return true;
            }
        };
    }

    @Test
    void 声明认的换法_服务端换上来的下一页接着用() {
        AbstractContainerMenu terminal = page(3);
        AbstractContainerMenu amountPage = page(4);
        AbstractContainerMenu inventory = page(0);
        PageSwaps craftPages = (from, to) -> to == amountPage;
        assertTrue(ClientOpenedMenu.followable(terminal, amountPage, inventory, craftPages));
    }

    @Test
    void 没声明_换了页就当界面不在了() {
        AbstractContainerMenu terminal = page(3);
        assertFalse(ClientOpenedMenu.followable(terminal, page(4), page(0), PageSwaps.NONE));
    }

    @Test
    void 界面被关上回到背包_或换上来的是声明不认的界面_都不跟() {
        AbstractContainerMenu terminal = page(3);
        AbstractContainerMenu amountPage = page(4);
        AbstractContainerMenu inventory = page(0);
        PageSwaps craftPages = (from, to) -> to == amountPage || to == inventory;
        assertFalse(ClientOpenedMenu.followable(terminal, inventory, inventory, craftPages), "关上了不是换页");
        assertFalse(ClientOpenedMenu.followable(terminal, page(5), inventory, craftPages), "别的界面顶上来不跟");
        assertFalse(ClientOpenedMenu.followable(terminal, terminal, inventory, (from, to) -> true), "还是原来那一份");
    }
}

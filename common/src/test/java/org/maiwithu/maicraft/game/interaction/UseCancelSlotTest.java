// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 拉弓中途停手切槽：先剑斧、再空格、再不是箭的；不切到挨着弓的箭上。 */
class UseCancelSlotTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<ItemStack> hotbar() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(new ItemStack(Items.COBBLESTONE, 64));
        return items;
    }

    @Test
    void theSwordIsPreferredOverTheArrowsNextToTheBow() {
        // 弓在 2 号格、箭在 3 号格、剑在 7 号格：切到剑上。
        List<ItemStack> items = hotbar();
        items.set(2, new ItemStack(Items.BOW));
        items.set(3, new ItemStack(Items.ARROW, 30));
        items.set(7, new ItemStack(Items.IRON_SWORD));
        assertEquals(7, UseCancelSlot.pick(2, items::get));
    }

    @Test
    void withoutAWeaponAnEmptySlotThenAnythingButArrows() {
        List<ItemStack> items = hotbar();
        items.set(0, new ItemStack(Items.BOW));
        items.set(1, new ItemStack(Items.ARROW, 30));
        items.set(5, ItemStack.EMPTY);
        assertEquals(5, UseCancelSlot.pick(0, items::get), "有空格切空格");
        items.set(5, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(2, UseCancelSlot.pick(0, items::get), "没有空格就切到箭后面那格圆石");
    }
}

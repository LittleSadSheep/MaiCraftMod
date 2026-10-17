// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.menu.CursorStowing.Spot;

/** 关界面前光标上的东西放回背包：先叠上同样的，再找主背包空格，快捷栏最后；一格都装不下就不放。 */
class CursorStowingTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Spot spot(int slotId, int inventoryIndex, ItemStack current) {
        return new Spot(slotId, inventoryIndex, current, 64);
    }

    @Test
    void 先叠到同样的东西上() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        int chosen = CursorStowing.choose(List.of(
                spot(10, 9, ItemStack.EMPTY),
                spot(11, 10, new ItemStack(Items.COBBLESTONE, 60)),
                spot(12, 11, new ItemStack(Items.COBBLESTONE, 30))), carried);
        assertEquals(12, chosen, "60 个那格叠不下 5 个，叠到 30 个那格");
    }

    @Test
    void 没有能叠的就放主背包空格_快捷栏放最后() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(40, CursorStowing.choose(List.of(
                spot(36, 0, ItemStack.EMPTY),
                spot(40, 12, ItemStack.EMPTY)), carried));
        assertEquals(36, CursorStowing.choose(List.of(
                spot(36, 0, ItemStack.EMPTY),
                spot(40, 12, new ItemStack(Items.DIRT, 64))), carried), "主背包满了才放快捷栏");
    }

    @Test
    void 一格都装不下就不放_交给原版关界面() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(-1, CursorStowing.choose(List.of(
                spot(10, 9, new ItemStack(Items.DIRT, 64)),
                spot(11, 10, new ItemStack(Items.COBBLESTONE, 62))), carried));
    }
}

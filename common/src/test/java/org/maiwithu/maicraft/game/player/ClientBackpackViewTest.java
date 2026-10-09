// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 背包格的分类：工具、武器与穿戴按物品类型认，存东西时这些留在身上。 */
class ClientBackpackViewTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 剪刀打火石钓竿鞘翅重锤都算工具或穿戴() {
        for (Item item : new Item[] {Items.SHEARS, Items.FLINT_AND_STEEL,
                Items.FISHING_ROD, Items.ELYTRA, Items.MACE, Items.IRON_PICKAXE, Items.SHIELD}) {
            assertTrue(ClientBackpackView.snapshot(new ItemStack(item)).gear(), item.toString());
        }
        assertFalse(ClientBackpackView.snapshot(new ItemStack(Items.ROTTEN_FLESH)).gear());
        assertTrue(ClientBackpackView.snapshot(new ItemStack(Items.COBBLESTONE)).buildingMaterial());
    }
}

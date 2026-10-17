// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 能吃的口粮一大类：面包、熟肉、生牛肉算；吃了有害的、金苹果紫颂果这类特殊的、谜之炖菜不算。 */
class EverydayFoodTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 普通口粮算_生牛肉也算() {
        assertTrue(EverydayFood.is(Items.BREAD));
        assertTrue(EverydayFood.is(Items.COOKED_BEEF));
        assertTrue(EverydayFood.is(Items.BEEF));
        assertTrue(EverydayFood.is(Items.SWEET_BERRIES));
        assertTrue(EverydayFood.all().contains("minecraft:baked_potato"));
    }

    @Test
    void 吃了有害的和特殊的不算() {
        assertFalse(EverydayFood.is(Items.ROTTEN_FLESH), "腐肉会饥饿");
        assertFalse(EverydayFood.is(Items.CHICKEN), "生鸡肉可能饥饿");
        assertFalse(EverydayFood.is(Items.SPIDER_EYE), "蜘蛛眼中毒");
        assertFalse(EverydayFood.is(Items.GOLDEN_APPLE), "金苹果留着保命");
        assertFalse(EverydayFood.is(Items.CHORUS_FRUIT), "紫颂果会乱传送");
        assertFalse(EverydayFood.is(Items.SUSPICIOUS_STEW), "谜之炖菜效果随机");
        assertFalse(EverydayFood.is(Items.IRON_INGOT));
    }
}

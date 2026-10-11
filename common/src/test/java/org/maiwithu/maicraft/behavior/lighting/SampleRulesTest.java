// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 照明的分类规则：能站住的格与庄稼进样本；敏感面一律不落灯、不改动。 */
class SampleRulesTest {

    private static BlockState AIR;
    private static BlockState GRASS_BLOCK;
    private static BlockState WHEAT;
    private static BlockState FARMLAND;
    private static BlockState CHEST;
    private static BlockState WATER;
    private static BlockState DIRT_PATH;
    private static BlockState FIRE;
    private static BlockState CAMPFIRE;
    private static BlockState PORTAL;
    private static BlockState LAVA;
    private static BlockState REDSTONE_BLOCK;
    private static BlockState TALL_GRASS;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
        GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();
        WHEAT = Blocks.WHEAT.defaultBlockState();
        FARMLAND = Blocks.FARMLAND.defaultBlockState();
        CHEST = Blocks.CHEST.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        DIRT_PATH = Blocks.DIRT_PATH.defaultBlockState();
        FIRE = Blocks.FIRE.defaultBlockState();
        CAMPFIRE = Blocks.CAMPFIRE.defaultBlockState();
        PORTAL = Blocks.NETHER_PORTAL.defaultBlockState();
        LAVA = Blocks.LAVA.defaultBlockState();
        REDSTONE_BLOCK = Blocks.REDSTONE_BLOCK.defaultBlockState();
        TALL_GRASS = Blocks.SHORT_GRASS.defaultBlockState();
    }

    @Test
    void 能站的格与草丛是样本_不是敏感面() {
        assertFalse(SampleRules.sensitive(AIR));
        assertFalse(SampleRules.sensitive(GRASS_BLOCK));
        assertTrue(SampleRules.passable(AIR));
        assertTrue(SampleRules.passable(TALL_GRASS), "可替换的草丛能被火把原地替换");
        assertFalse(SampleRules.passable(GRASS_BLOCK));
        assertFalse(SampleRules.passable(WATER), "液体不能占");
    }

    @Test
    void 庄稼与耕地按样本对待_但也是敏感面_不落灯() {
        assertTrue(SampleRules.isCrop(WHEAT));
        assertTrue(SampleRules.sensitive(WHEAT));
        assertTrue(SampleRules.sensitive(FARMLAND));
        assertFalse(SampleRules.passable(WHEAT));
    }

    @Test
    void 敏感面各一例_不落灯不改动() {
        assertTrue(SampleRules.sensitive(CHEST), "有方块实体");
        assertTrue(SampleRules.sensitive(WATER), "流体");
        assertTrue(SampleRules.sensitive(LAVA), "岩浆");
        assertTrue(SampleRules.sensitive(DIRT_PATH), "土径");
        assertTrue(SampleRules.sensitive(FIRE), "火");
        assertTrue(SampleRules.sensitive(CAMPFIRE), "营火");
        assertTrue(SampleRules.sensitive(PORTAL), "传送门");
        assertTrue(SampleRules.sensitive(REDSTONE_BLOCK), "红石信号源");
        assertTrue(SampleRules.foreign(ResourceLocation.parse("testlights:odd_machine")), "非原版方块");
        assertTrue(SampleRules.foreign(null), "注册表里查不到的也当敏感面");
        assertFalse(SampleRules.foreign(ResourceLocation.parse("minecraft:stone")));
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallTorchBlock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 光源物品：默认火把；指定的物品按它的落法解析；发射量到不了验收亮度的不选。 */
class LampItemTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 默认火把_立式贴墙都有() {
        LampItem torch = LampItem.torch();
        assertEquals(14, torch.emission());
        assertEquals(Blocks.TORCH, torch.standing().getBlock());
        assertEquals(Blocks.WALL_TORCH, torch.wall().getBlock());
    }

    @Test
    void 指定灯笼_只有立式_不选悬挂态() {
        Optional<LampItem> lantern = LampItem.resolve("minecraft:lantern", 8);
        assertTrue(lantern.isPresent());
        assertEquals(15, lantern.get().emission());
        assertEquals(Blocks.LANTERN, lantern.get().standing().getBlock());
        assertEquals(Boolean.FALSE, lantern.get().standing().getValue(LanternBlock.HANGING));
        assertTrue(lantern.get().wall() == null, "灯笼没有贴墙形态");
    }

    @Test
    void 指定火把_贴墙形态带朝向属性() {
        Optional<LampItem> torch = LampItem.resolve("minecraft:torch", 8);
        assertTrue(torch.isPresent());
        assertEquals(WallTorchBlock.FACING, torch.get().wall().getProperties().iterator().next());
    }

    @Test
    void 发射量到不了验收亮度_不选() {
        assertTrue(LampItem.resolve("minecraft:soul_torch", 14).isEmpty(), "灵魂火把只有 10");
        assertTrue(LampItem.resolve("minecraft:torch", 15).isEmpty(), "火把只有 14，验收 15 永远点不满");
    }

    @Test
    void 不是可放置光源的物品_不选() {
        assertTrue(LampItem.resolve("minecraft:stick", 8).isEmpty());
        assertTrue(LampItem.resolve("minecraft:no_such_item", 8).isEmpty());
        assertTrue(LampItem.resolve("not an id", 8).isEmpty());
    }

    @Test
    void 整块发光方块_整格放() {
        Optional<LampItem> glowstone = LampItem.resolve("minecraft:glowstone", 8);
        assertTrue(glowstone.isPresent());
        assertEquals(Blocks.GLOWSTONE, glowstone.get().standing().getBlock());
        assertEquals(15, glowstone.get().emission());
    }
}

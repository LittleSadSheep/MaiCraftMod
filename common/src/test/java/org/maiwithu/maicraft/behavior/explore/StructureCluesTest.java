// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 结构线索的证据组合：各组的方块都注册得出来，凑够数给锚点，凑不够不给。 */
class StructureCluesTest {

    @BeforeAll
    static void bootMinecraft() {
        // 证据表按真实注册表解析：注册表引导过才能碰。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 常见原版结构有线索档案() {
        assertTrue(StructureClues.known("minecraft:village_plains"));
        assertTrue(StructureClues.known("minecraft:mineshaft"));
        assertTrue(StructureClues.known("minecraft:nether_fortress"));
        assertFalse(StructureClues.known("minecraft:not_a_structure"));
    }

    @Test
    void 档案里的方块都解析得出来() {
        for (String structureId : StructureClues.knownIds()) {
            Optional<StructureClues.Resolved> resolved = StructureClues.resolve(structureId);
            assertTrue(resolved.isPresent(), structureId + " 的证据方块应全部注册");
            assertFalse(resolved.get().unionBlocks().isEmpty());
        }
    }

    @Test
    void 证据凑够数给聚集锚点() {
        StructureClues.Resolved village = StructureClues.resolve("minecraft:village_plains").orElseThrow();
        List<StructureClues.Evidence> evidence = new ArrayList<>();
        evidence.add(new StructureClues.Evidence(new BlockPos(0, 64, 0), Blocks.BELL));
        evidence.add(new StructureClues.Evidence(new BlockPos(4, 64, 3), Blocks.COMPOSTER));
        for (int i = 1; i <= 6; i++) {
            evidence.add(new StructureClues.Evidence(new BlockPos(-4 - i, 64, 2 + i), Blocks.BARREL));
        }
        Optional<BlockPos> anchor = StructureClues.anchorOf(village, evidence);
        assertEquals(new BlockPos(0, 64, 0), anchor.orElseThrow());
    }

    @Test
    void 只有一组证据凑不够数不给锚点() {
        StructureClues.Resolved village = StructureClues.resolve("minecraft:village_plains").orElseThrow();
        // 只有中心证据（土径），没有活动证据：孤证不成线索。
        List<StructureClues.Evidence> onlyCenter = List.of(
                new StructureClues.Evidence(new BlockPos(0, 64, 0), Blocks.DIRT_PATH));
        assertTrue(StructureClues.anchorOf(village, onlyCenter).isEmpty());
    }

    @Test
    void 证据散得太开凑不成一团() {
        StructureClues.Resolved village = StructureClues.resolve("minecraft:village_plains").orElseThrow();
        // 聚集半径 64 之外的活动证据不算：隔了一片海的箱子不是同一个村庄。
        List<StructureClues.Evidence> scattered = List.of(
                new StructureClues.Evidence(new BlockPos(0, 64, 0), Blocks.BELL),
                new StructureClues.Evidence(new BlockPos(4, 64, 3), Blocks.COMPOSTER),
                new StructureClues.Evidence(new BlockPos(500, 64, 500), Blocks.BARREL));
        assertTrue(StructureClues.anchorOf(village, scattered).isEmpty());
    }
}

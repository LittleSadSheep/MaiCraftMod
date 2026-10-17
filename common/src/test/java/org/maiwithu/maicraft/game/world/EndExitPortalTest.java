// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出口门亮没亮：门洞那一层离门心两格的四格里有末地传送门方块就是亮了，读不到的格不算。 */
class EndExitPortalTest {

    private static final BlockPos CENTER = new BlockPos(0, 62, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 摆一座原版出口门座：门心一柱基岩，门洞那一层不到 2.5 格是门格（亮着放门方块、没亮是空气），外圈基岩边沿。 */
    private static Map<BlockPos, BlockState> podium(boolean lit) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int distance = dx * dx + dz * dz;
                BlockPos cell = CENTER.offset(dx, 0, dz);
                if (dx == 0 && dz == 0) {
                    blocks.put(cell, Blocks.BEDROCK.defaultBlockState());
                } else if (distance < 6.25) {
                    blocks.put(cell, lit ? Blocks.END_PORTAL.defaultBlockState() : Blocks.AIR.defaultBlockState());
                } else if (distance < 12.25) {
                    blocks.put(cell, Blocks.BEDROCK.defaultBlockState());
                }
            }
        }
        return blocks;
    }

    private static Function<BlockPos, Optional<BlockState>> reader(Map<BlockPos, BlockState> blocks) {
        return cell -> Optional.ofNullable(blocks.get(cell));
    }

    @Test
    void ringCellsAreTwoFromTheCenterOnThePortalLayer() {
        List<BlockPos> ring = EndExitPortal.ringCells(CENTER);
        assertEquals(4, ring.size());
        for (BlockPos cell : ring) {
            assertEquals(CENTER.getY(), cell.getY(), "在门洞那一层");
            assertEquals(4, cell.distSqr(CENTER), "离门心两格：" + cell);
        }
    }

    @Test
    void portalBlocksInTheHoleMeanLit() {
        assertTrue(EndExitPortal.lit(reader(podium(true)), CENTER), "龙死后门格都是门方块");
    }

    @Test
    void airHoleInsideTheBedrockRimIsNotLit() {
        assertFalse(EndExitPortal.lit(reader(podium(false)), CENTER), "门座在、门洞是空的：龙还活着");
    }

    @Test
    void oneRingCellIsEnough() {
        Map<BlockPos, BlockState> blocks = podium(false);
        blocks.put(CENTER.offset(0, 0, -2), Blocks.END_PORTAL.defaultBlockState());
        assertTrue(EndExitPortal.lit(reader(blocks), CENTER), "四格里有一格门方块就算亮");
    }

    @Test
    void unreadableCellsAreNoEvidence() {
        assertFalse(EndExitPortal.lit(cell -> Optional.empty(), CENTER), "没加载的格读不到，不算亮");
    }

    @Test
    void wrongLayerIsNotLit() {
        // 门洞那一层认错了（比方说高了一格）：那一层是空气，不能当成亮了。
        assertFalse(EndExitPortal.lit(reader(podium(true)), CENTER.above()));
    }
}

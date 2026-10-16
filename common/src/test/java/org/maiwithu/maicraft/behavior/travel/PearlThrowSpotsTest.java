// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 扔珍珠的站位：主岛那一圈的门悬在虚空上，门洞一格高、上下是基岩、四面敞着；
 * 能看见门洞侧面、够得着的脚位才算，现成站得住的优先，不许垫方块时只给现成站得住的。
 */
class PearlThrowSpotsTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos GATE = new BlockPos(96, 75, 0);

    /** 原版折跃门的样子：门在中间，上下一格是十字形基岩，再上下一格是一块基岩，同一层四周是空气；周围一片虚空。 */
    private final Map<BlockPos, BlockState> world = new HashMap<>();

    PearlThrowSpotsTest() {
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    world.put(GATE.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        world.put(GATE, Blocks.END_GATEWAY.defaultBlockState());
        for (int dy : new int[] {-1, 1}) {
            world.put(GATE.offset(0, dy, 0), Blocks.BEDROCK.defaultBlockState());
            world.put(GATE.offset(1, dy, 0), Blocks.BEDROCK.defaultBlockState());
            world.put(GATE.offset(-1, dy, 0), Blocks.BEDROCK.defaultBlockState());
            world.put(GATE.offset(0, dy, 1), Blocks.BEDROCK.defaultBlockState());
            world.put(GATE.offset(0, dy, -1), Blocks.BEDROCK.defaultBlockState());
        }
        world.put(GATE.offset(0, 2, 0), Blocks.BEDROCK.defaultBlockState());
        world.put(GATE.offset(0, -2, 0), Blocks.BEDROCK.defaultBlockState());
    }

    private Optional<BlockState> read(BlockPos cell) {
        return Optional.ofNullable(world.get(cell));
    }

    private List<PearlThrowSpots.Spot> spots(boolean mayPlace) {
        return PearlThrowSpots.around(GATE, this::read, mayPlace, GATE.offset(10, -15, 0), Set.of());
    }

    @Test
    void 悬在虚空里的门_不许垫方块就没有站位_许可垫方块就给要垫的脚位() {
        assertTrue(spots(false).isEmpty(), "四周没有一格踩得住，又不许垫");
        List<PearlThrowSpots.Spot> placed = spots(true);
        assertFalse(placed.isEmpty());
        for (PearlThrowSpots.Spot spot : placed) {
            assertFalse(spot.standsAlready());
            BlockPos offset = spot.feet().subtract(GATE);
            assertTrue(Math.max(Math.abs(offset.getX()), Math.abs(offset.getZ())) >= 2, spot + " 不贴着门框");
            assertTrue(offset.getY() >= -2 && offset.getY() <= 0, spot + " 脚比门洞低 0 到 2 格");
        }
        assertTrue(placed.size() <= PearlThrowSpots.MAX_SPOTS);
    }

    @Test
    void 有人搭了平台_现成站得住的排最前() {
        // 门东边三格、脚低一格处垫着一块末地石：眼睛平着门洞。
        BlockPos feet = GATE.offset(3, -1, 0);
        world.put(feet.below(), Blocks.END_STONE.defaultBlockState());
        assertEquals(List.of(new PearlThrowSpots.Spot(feet, true)), spots(false));
        assertEquals(new PearlThrowSpots.Spot(feet, true), spots(true).get(0));
    }

    @Test
    void 视线被挡_或从高处往下被门框基岩挡住的不算() {
        BlockPos feet = GATE.offset(3, -1, 0);
        assertTrue(PearlThrowSpots.spotAt(GATE, feet, this::read).isPresent());
        world.put(GATE.offset(2, 0, 0), Blocks.OBSIDIAN.defaultBlockState());
        assertTrue(PearlThrowSpots.spotAt(GATE, feet, this::read).isEmpty(), "眼睛和门洞之间隔着一块黑曜石");

        // 脚和门洞同高、离门两格：眼睛高过门洞，往下看要穿过门框上面那一格基岩。
        assertTrue(PearlThrowSpots.spotAt(GATE, GATE.offset(0, 0, 2), this::read).isEmpty());
    }

    @Test
    void 够不着和读不到的不算_试过不行的不再给() {
        assertTrue(PearlThrowSpots.spotAt(GATE, GATE.offset(4, -1, 4), this::read).isEmpty(), "斜着四格够不着");
        assertTrue(PearlThrowSpots.spotAt(GATE, GATE.offset(30, -1, 0), this::read).isEmpty(), "没加载的格不算");

        BlockPos feet = GATE.offset(3, -1, 0);
        world.put(feet.below(), Blocks.END_STONE.defaultBlockState());
        assertTrue(PearlThrowSpots.around(GATE, this::read, false, feet, Set.of(feet)).isEmpty());
    }
}

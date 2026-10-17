// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * 吃过亏的方块认人的离线测试：名字对得上的一次认定，唯一碰着的算嫌疑，地面、墙、草和摔伤这类不冤枉。
 */
class PainCulpritTest {

    private static final PainCulprit.Touched GROUND =
            new PainCulprit.Touched("minecraft:netherrack", true, false, true, false);
    private static final PainCulprit.Touched GRASS =
            new PainCulprit.Touched("minecraft:short_grass", false, false, false, true);
    private static final PainCulprit.Touched BRAMBLE =
            new PainCulprit.Touched("biomesoplenty:bramble", false, false, false, false);

    @Test
    void modDamageNamedAfterTheBlockIsCertain() {
        Optional<PainCulprit.Verdict> verdict = PainCulprit.judge("biomesoplenty:bramble", List.of(GROUND, GRASS, BRAMBLE));
        assertEquals("biomesoplenty:bramble", verdict.orElseThrow().blockId());
        assertTrue(verdict.orElseThrow().certain());
    }

    @Test
    void onlyTouchedOddBlockIsASuspect() {
        Optional<PainCulprit.Verdict> verdict = PainCulprit.judge("othermod:thorns", List.of(GROUND, GRASS, BRAMBLE));
        assertEquals("biomesoplenty:bramble", verdict.orElseThrow().blockId());
        assertFalse(verdict.orElseThrow().certain());
    }

    @Test
    void twoOddBlocksAreTooUnclear() {
        PainCulprit.Touched torch = new PainCulprit.Touched("minecraft:torch", false, false, false, false);
        assertTrue(PainCulprit.judge("othermod:thorns", List.of(GROUND, BRAMBLE, torch)).isEmpty());
    }

    @Test
    void groundWallAndGrassAreNeverBlamedWithoutTheName() {
        PainCulprit.Touched wall = new PainCulprit.Touched("minecraft:stone", false, false, true, false);
        assertTrue(PainCulprit.judge("othermod:thorns", List.of(GROUND, GRASS, wall)).isEmpty());
    }

    @Test
    void damageUnrelatedToTouchIsIgnored() {
        assertTrue(PainCulprit.judge("minecraft:fall", List.of(GROUND, BRAMBLE)).isEmpty());
        assertTrue(PainCulprit.judge("minecraft:on_fire", List.of(GROUND, BRAMBLE)).isEmpty());
        assertTrue(PainCulprit.judge("minecraft:generic", List.of(GROUND, BRAMBLE)).isEmpty());
    }

    @Test
    void vanillaHazardsAlreadyAvoidedAreNotRelearned() {
        PainCulprit.Touched cactus = new PainCulprit.Touched("minecraft:cactus", false, true, false, false);
        assertTrue(PainCulprit.judge("minecraft:cactus", List.of(GROUND, cactus)).isEmpty());
    }

    @Test
    void campfireBurnIsLearnedByName() {
        PainCulprit.Touched campfire = new PainCulprit.Touched("minecraft:campfire", true, false, false, false);
        assertEquals("minecraft:campfire",
                PainCulprit.judge("minecraft:campfire", List.of(GROUND, campfire)).orElseThrow().blockId());
    }
}

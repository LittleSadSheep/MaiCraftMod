// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 途中发现账：重复看见合并成一条，远望到了升级成到访、不降级、不重写。 */
class ExploreFindingsTest {

    private static final WorldPosition SPOT = new WorldPosition(10, 64, -5, "minecraft:overworld");

    @Test
    void 同一个群系重复望见只留一条() {
        ExploreFindings findings = new ExploreFindings();
        assertEquals(ExploreFindings.Entry.NEW, findings.record(new ExploreFinding(
                ExploreFinding.Kind.BIOME, "minecraft:plains", SPOT, MemoryOrigin.SEEN)).entry());
        WorldPosition elsewhere = new WorldPosition(200, 64, -300, "minecraft:overworld");
        assertEquals(ExploreFindings.Entry.KNOWN, findings.record(new ExploreFinding(
                ExploreFinding.Kind.BIOME, "minecraft:plains", elsewhere, MemoryOrigin.SEEN)).entry());
        assertEquals(1, findings.all().size());
        // 位置不重写：留第一次看见的地方。
        assertEquals(SPOT, findings.all().get(0).position());
    }

    @Test
    void 远望到了升级成到访_不降级也不重写位置() {
        ExploreFindings findings = new ExploreFindings();
        findings.record(new ExploreFinding(ExploreFinding.Kind.BIOME, "minecraft:jungle",
                SPOT, MemoryOrigin.SEEN));
        WorldPosition visitedAt = new WorldPosition(40, 70, -40, "minecraft:overworld");
        ExploreFindings.Result upgraded = findings.record(new ExploreFinding(
                ExploreFinding.Kind.BIOME, "minecraft:jungle", visitedAt, MemoryOrigin.VISITED));
        assertEquals(ExploreFindings.Entry.UPGRADED, upgraded.entry());
        assertEquals(MemoryOrigin.VISITED, upgraded.effective().origin());
        assertEquals(SPOT, upgraded.effective().position(), "位置留第一次记下的");
        // 再望一眼不退回去。
        assertEquals(ExploreFindings.Entry.KNOWN, findings.record(new ExploreFinding(
                ExploreFinding.Kind.BIOME, "minecraft:jungle", SPOT, MemoryOrigin.SEEN)).entry());
        assertEquals(MemoryOrigin.VISITED, findings.all().get(0).origin());
    }

    @Test
    void 两处岩浆湖是两条发现() {
        ExploreFindings findings = new ExploreFindings();
        findings.record(new ExploreFinding(ExploreFinding.Kind.TERRAIN, "lava_pool", SPOT, MemoryOrigin.SEEN));
        WorldPosition another = new WorldPosition(30, 32, 20, "minecraft:overworld");
        findings.record(new ExploreFinding(ExploreFinding.Kind.TERRAIN, "lava_pool", another, MemoryOrigin.SEEN));
        assertEquals(2, findings.all().size());
    }

    @Test
    void 有没有见过某一种发现() {
        ExploreFindings findings = new ExploreFindings();
        findings.record(new ExploreFinding(ExploreFinding.Kind.TERRAIN, "lava_pool", SPOT, MemoryOrigin.SEEN));
        assertTrue(findings.hasAny(ExploreFinding.Kind.TERRAIN));
        assertFalse(findings.hasAny(ExploreFinding.Kind.BIOME));
    }
}

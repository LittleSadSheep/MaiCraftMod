// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 卡坑判断与自动脱困的升降：小坑、走得出的浅坑、水柱游上去、封死的洞穴；只走 → 搭 → 挖，出坑降回。 */
class TrapCheckTest {

    /** 平地：63 层及以下是实心，挖掉的格子和放进去的水另记。 */
    private static final class Ground implements TrapCheck.Cells {
        final Set<BlockPos> dugOut = new HashSet<>();
        final Set<BlockPos> water = new HashSet<>();
        final Set<BlockPos> solid = new HashSet<>();
        int roofAt = Integer.MAX_VALUE;

        boolean solidAt(int x, int y, int z) {
            BlockPos at = new BlockPos(x, y, z);
            if (solid.contains(at)) return true;
            return y <= 63 && !dugOut.contains(at) && !water.contains(at);
        }

        @Override public boolean open(int x, int y, int z) {
            return !solidAt(x, y, z);
        }

        @Override public boolean floor(int x, int y, int z) {
            return solidAt(x, y, z) || water(x, y, z);
        }

        @Override public boolean water(int x, int y, int z) {
            return water.contains(new BlockPos(x, y, z));
        }

        @Override public boolean sky(int x, int y, int z) {
            return y < roofAt && y > 63;
        }

        @Override public boolean loaded(int x, int z) {
            return true;
        }
    }

    @Test
    void twoDeepOneByOnePitIsATrap() {
        Ground ground = new Ground();
        ground.dugOut.add(new BlockPos(0, 63, 0));
        ground.dugOut.add(new BlockPos(0, 62, 0));
        assertTrue(TrapCheck.trapped(ground, new BlockPos(0, 62, 0)), "两格深的一格坑跳不出来");
    }

    @Test
    void oneDeepPitAndFlatGroundAreNotTraps() {
        Ground ground = new Ground();
        ground.dugOut.add(new BlockPos(0, 63, 0));
        assertFalse(TrapCheck.trapped(ground, new BlockPos(0, 63, 0)), "一格深跳一下就出去了");
        assertFalse(TrapCheck.trapped(new Ground(), new BlockPos(5, 64, 5)), "平地铺到边上");
    }

    @Test
    void waterColumnCountsAsAWayOut() {
        Ground ground = new Ground();
        for (int y = 60; y <= 63; y++) ground.water.add(new BlockPos(0, y, 0));
        assertFalse(TrapCheck.trapped(ground, new BlockPos(0, 60, 0)), "水柱能游上去，不算困住");
    }

    @Test
    void sealedCavePocketWithoutSkyIsATrapEvenIfRoomy() {
        Ground ground = new Ground();
        // 地下 50 层一个 6×6 的洞，四周和顶上都是石头，见不到天。
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                ground.dugOut.add(new BlockPos(x, 50, z));
                ground.dugOut.add(new BlockPos(x, 51, z));
            }
        }
        assertTrue(TrapCheck.trapped(ground, new BlockPos(2, 50, 2)), "36 格的洞但封死见不到天");
    }

    @Test
    void walledCourtyardUnderTheSkyIsNotATrap() {
        Ground ground = new Ground();
        // 地面上一圈两格高的墙围出 7×7 的院子，见得到天：多半是有人围的，不算坑。
        for (int i = -4; i <= 4; i++) {
            for (int y = 64; y <= 65; y++) {
                ground.solid.add(new BlockPos(i, y, -4));
                ground.solid.add(new BlockPos(i, y, 4));
                ground.solid.add(new BlockPos(-4, y, i));
                ground.solid.add(new BlockPos(4, y, i));
            }
        }
        assertFalse(TrapCheck.trapped(ground, new BlockPos(0, 64, 0)));
    }

    @Test
    void escapeClimbsWalkThenBuildThenDigAndStepsBackDown() {
        TrapEscape escape = new TrapEscape(TerrainPermit.WALK_ONLY);
        assertTrue(escape.raise(() -> true, 0));
        assertEquals(Permissions.BlockChanges.TEMPORARY, escape.effective().changes(), "先搭");
        escape.notePlaced();
        assertTrue(escape.raise(() -> true, 5));
        assertEquals(Permissions.BlockChanges.NATURAL, escape.effective().changes(), "搭不出去再挖");
        assertFalse(escape.raise(() -> true, 9), "最多到挖天然方块");
        Problem stuck = escape.explain(Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线", null));
        assertTrue(stuck.message().contains("还是没出去"), stuck.message());
        escape.noteDug();
        escape.restore();
        assertEquals(Permissions.BlockChanges.NONE, escape.effective().changes(), "出坑降回任务的许可");
        assertTrue(escape.notes().getFirst().contains("垫了 1 块、挖开 1 格"), escape.notes().getFirst());
    }

    @Test
    void enoughPermissionOrOpenGroundNeverRaises() {
        assertNull(TrapEscape.next(TerrainPermit.NATURAL));
        assertFalse(new TrapEscape(TerrainPermit.NATURAL).raise(() -> true, 0), "任务许可本来就能挖");
        assertFalse(new TrapEscape(TerrainPermit.WALK_ONLY).raise(() -> false, 0), "没困住就不放宽许可");
    }
}

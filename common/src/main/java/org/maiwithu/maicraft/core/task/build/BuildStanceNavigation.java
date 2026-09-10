// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/**
 * 对整组站位依次尝试保持高度、已有地形和施工导航；单个站位失败不能提前放宽为搭支撑。
 */
final class BuildStanceNavigation {
    private final PlayerNav.ContextProvider construction;
    private int pass, floor = Integer.MIN_VALUE;

    BuildStanceNavigation(PlayerNav.ContextProvider construction) {
        this.construction = construction;
    }

    PlayerNav.ContextProvider contextFor(BlockPos stance) {
        return pass == 2 ? construction : walkingContext(pass == 0 ? floor : Integer.MIN_VALUE);
    }

    PlayerNav.ContextProvider walkingContext(int minimum) {
        return new ExistingFooting(construction, Math.max(minimum, construction.minimumFeetY()));
    }

    boolean allows(BlockPos stance) { return pass != 0 || stance.getY() >= floor; }
    boolean nextExistingPass() { if (pass != 0) return false; pass = 1; return true; }
    boolean allowTerrain() { if (pass != 1 || !construction.permit().mayAlter()) return false; pass = 2; return true; }
    String stage() { return pass == 0 ? "retain_height" : pass == 1 ? "existing_footing" : "construction_access"; }
    void reset() { pass = 0; floor = Integer.MIN_VALUE; }
    void startAt(BlockPos feet) { reset(); floor = feet.getY(); }

    private record ExistingFooting(PlayerNav.ContextProvider construction, int minimumFeetY) implements PlayerNav.ContextProvider {
        @Override public TerrainPermit permit() { return TerrainPermit.PRESERVE; }

        @Override public LongSet embeddedProtectedMutationCells() {
            return construction.embeddedProtectedMutationCells();
        }

        @Override public LongSet embeddedForbiddenBodyCells() {
            return construction.embeddedForbiddenBodyCells();
        }

    }
}

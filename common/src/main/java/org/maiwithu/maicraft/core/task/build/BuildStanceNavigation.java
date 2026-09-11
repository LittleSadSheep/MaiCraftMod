// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/**
 * 对整组站位依次尝试保持高度、已有地形和施工导航；单个站位失败不能提前放宽为搭支撑。
 */
final class BuildStanceNavigation {
    private final PlayerNav.ContextProvider construction;
    private int pass, floor = Integer.MIN_VALUE;
    private BlockPos target;
    private final Set<BlockPos> unreachable = new HashSet<>();
    private int routeAttempts, skippedGestures;
    private long environmentRevision, attemptedRevision = -1;
    private String lastFailure = "";

    BuildStanceNavigation(PlayerNav.ContextProvider construction) {
        this.construction = construction;
    }

    PlayerNav.ContextProvider contextFor(BlockPos stance) {
        return pass == 2 ? construction : walkingContext(pass == 0 ? floor : Integer.MIN_VALUE);
    }

    PlayerNav.ContextProvider walkingContext(int minimum) {
        return new ExistingFooting(construction, Math.max(minimum, construction.minimumFeetY()));
    }

    void forTarget(BlockPos next, BlockPos feet) {
        if (!next.equals(target)) { startAt(feet); target = next.immutable(); }
    }
    boolean allows(BlockPos stance) { return !unreachable.contains(stance) && (pass != 0 || stance.getY() >= floor); }
    void attempted() { routeAttempts++; attemptedRevision = environmentRevision; }
    void failed(BlockPos stance, String reason) {
        if (attemptedRevision != environmentRevision) return;
        unreachable.add(stance.immutable());
        lastFailure = reason == null ? "navigation failed" : reason.substring(0, Math.min(240, reason.length()));
    }
    void skipped(BlockPos stance) { if (unreachable.contains(stance)) skippedGestures++; }
    void environmentChanged() { environmentRevision++; unreachable.clear(); lastFailure = ""; }
    private void clearPass() { environmentChanged(); routeAttempts = 0; skippedGestures = 0; }
    boolean nextExistingPass() { if (pass != 0) return false; pass = 1; clearPass(); return true; }
    boolean allowTerrain() { if (pass != 1 || !construction.permit().mayAlter()) return false; pass = 2; clearPass(); return true; }
    String stage() { return pass == 0 ? "retain_height" : pass == 1 ? "existing_footing" : "construction_access"; }
    Map<String, Object> evidence() {
        return Map.of("route_attempts", routeAttempts, "failed_stances", unreachable.size(),
                "duplicate_gestures_skipped", skippedGestures, "last_failure", lastFailure);
    }
    void reset() { pass = 0; floor = Integer.MIN_VALUE; target = null; clearPass(); }
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

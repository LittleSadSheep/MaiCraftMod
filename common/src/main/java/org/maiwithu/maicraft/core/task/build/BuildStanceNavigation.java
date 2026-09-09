// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/** Try existing terrain and completed construction before allowing a stance to need scaffolding. */
final class BuildStanceNavigation {
    private final PlayerNav.ContextProvider construction;
    private final PlayerNav.ContextProvider existingFooting;
    private final Set<BlockPos> terrainRetries = new HashSet<>();

    BuildStanceNavigation(PlayerNav.ContextProvider construction) {
        this.construction = construction;
        existingFooting = new ExistingFooting(construction);
    }

    PlayerNav.ContextProvider contextFor(BlockPos stance) {
        return terrainRetries.contains(stance) ? construction : existingFooting;
    }

    /** A failed preserve route gets one construction retry before the caller rejects this stance. */
    boolean retryWithTerrain(BlockPos stance) {
        return construction.permit().mayAlter() && terrainRetries.add(stance.immutable());
    }

    void reset() { terrainRetries.clear(); }

    private record ExistingFooting(PlayerNav.ContextProvider construction) implements PlayerNav.ContextProvider {
        @Override public TerrainPermit permit() { return TerrainPermit.PRESERVE; }

        @Override public LongSet embeddedProtectedMutationCells() {
            return construction.embeddedProtectedMutationCells();
        }

        @Override public LongSet embeddedForbiddenBodyCells() {
            return construction.embeddedForbiddenBodyCells();
        }

        // Keep the retained cost interfaces conservative too; delegating them would restore TERRAFORM.
        @Override public CalculationContext forSearch(LocalPlayer player, LongSet sacred,
                LongSet deniedPlace, LongSet forbiddenBodyCells) {
            return PlayerNav.ContextProvider.DEFAULT.forSearch(player,
                    union(sacred, embeddedProtectedMutationCells()), deniedPlace,
                    union(forbiddenBodyCells, embeddedForbiddenBodyCells()));
        }

        @Override public CalculationContext forExecution(LocalPlayer player, LongSet sacred,
                LongSet deniedPlace, LongSet forbiddenBodyCells) {
            return PlayerNav.ContextProvider.DEFAULT.forExecution(player,
                    union(sacred, embeddedProtectedMutationCells()), deniedPlace,
                    union(forbiddenBodyCells, embeddedForbiddenBodyCells()));
        }

        private static LongSet union(LongSet first, LongSet second) {
            var result = new LongOpenHashSet(first);
            result.addAll(second);
            return result;
        }
    }
}

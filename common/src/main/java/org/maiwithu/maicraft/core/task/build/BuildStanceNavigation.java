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

/**
 * 去施工站位时先尝试沿已有地形和已建方块走；失败后，只有原任务本来允许改地形，才对这一站位再试一次施工导航。
 */
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

    /**
     * 每个站位最多获得一次改地形重试；一个站位失败不会让其他站位直接跳过已有道路尝试。
     */
    boolean retryWithTerrain(BlockPos stance) {
        return construction.permit().mayAlter() && terrainRetries.add(stance.immutable());
    }

    // 换到下一项施工工作时清掉重试记录；刚建好的方块可能已经提供了新的路。
    void reset() { terrainRetries.clear(); }

    private record ExistingFooting(PlayerNav.ContextProvider construction) implements PlayerNav.ContextProvider {
        @Override public TerrainPermit permit() { return TerrainPermit.PRESERVE; }

        @Override public LongSet embeddedProtectedMutationCells() {
            return construction.embeddedProtectedMutationCells();
        }

        @Override public LongSet embeddedForbiddenBodyCells() {
            return construction.embeddedForbiddenBodyCells();
        }

        // 旧成本接口也返回禁止改地形的上下文；当前主导航读的是上面的许可和保护格，旧接口仍留作兼容。
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

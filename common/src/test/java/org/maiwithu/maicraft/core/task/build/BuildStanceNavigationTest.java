// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/**
 * 检查先走已有道路、失败只对该站位放宽一次、保护格变化仍可见，并且帮助类不能授予原任务没有的改地形许可。
 */
public final class BuildStanceNavigationTest {
    public static void main(String[] args) {
        var protectedCells = new LongOpenHashSet();
        var forbiddenCells = new LongOpenHashSet();
        protectedCells.add(BlockPos.asLong(3, 1, 5));
        forbiddenCells.add(BlockPos.asLong(5, 2, 3));
        PlayerNav.ContextProvider construction = new PlayerNav.ContextProvider() {
            @Override public TerrainPermit permit() { return TerrainPermit.TERRAFORM; }
            @Override public LongSet embeddedProtectedMutationCells() { return protectedCells; }
            @Override public LongSet embeddedForbiddenBodyCells() { return forbiddenCells; }
        };
        var routes = new BuildStanceNavigation(construction);
        BlockPos first = new BlockPos(4, 2, 6), second = first.east();
        var existing = routes.contextFor(first);
        check(existing.permit() == TerrainPermit.PRESERVE, "first approach must forbid new scaffolds and digging");
        check(existing.embeddedProtectedMutationCells().equals(protectedCells), "completed structure remains protected");
        check(existing.embeddedForbiddenBodyCells().equals(forbiddenCells), "body exclusion remains protected");
        protectedCells.add(BlockPos.asLong(3, 2, 5));
        forbiddenCells.add(BlockPos.asLong(5, 3, 3));
        check(existing.embeddedProtectedMutationCells().equals(protectedCells)
                        && existing.embeddedForbiddenBodyCells().equals(forbiddenCells),
                "changes in task protection must remain visible to the navigation wrapper");
        check(routes.retryWithTerrain(first), "a failed walking route must retry before rejecting the stance");
        check(routes.contextFor(first) == construction, "fallback must retain original ownership and permissions");
        check(!routes.retryWithTerrain(first), "a failed construction retry exhausts the stance without looping");
        check(routes.contextFor(second).permit() == TerrainPermit.PRESERVE, "one failed stance cannot enable scaffolds everywhere");
        check(routes.retryWithTerrain(second), "a second stance has its own bounded fallback");
        routes.reset();
        check(routes.contextFor(first).permit() == TerrainPermit.PRESERVE && routes.retryWithTerrain(first),
                "new construction work must reconsider the newly completed footing");
        check(!new BuildStanceNavigation(PlayerNav.ContextProvider.DEFAULT).retryWithTerrain(first),
                "the helper cannot grant construction permission its caller did not have");
        System.out.println("BuildStanceNavigationTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

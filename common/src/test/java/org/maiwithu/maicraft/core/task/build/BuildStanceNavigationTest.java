// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/**
 * 整组站位先保高度，再试下降；之后才允许施工导航，且保持原任务的保护约束。
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
        routes.startAt(first);
        var existing = routes.contextFor(first);
        check(existing.permit() == TerrainPermit.PRESERVE, "first approach must forbid new scaffolds and digging");
        check(existing.embeddedProtectedMutationCells().equals(protectedCells), "completed structure remains protected");
        check(existing.embeddedForbiddenBodyCells().equals(forbiddenCells), "body exclusion remains protected");
        protectedCells.add(BlockPos.asLong(3, 2, 5));
        forbiddenCells.add(BlockPos.asLong(5, 3, 3));
        check(existing.embeddedProtectedMutationCells().equals(protectedCells)
                        && existing.embeddedForbiddenBodyCells().equals(forbiddenCells),
                "changes in task protection must remain visible to the navigation wrapper");
        check(existing.minimumFeetY() == 2 && !routes.allows(first.below()), "first pass must retain construction height");
        check(!routes.allowTerrain(), "one failed stance cannot grant a terrain retry before the whole existing-footing pass");
        routes.attempted(); routes.failed(first, "no existing route");
        check(!routes.allows(first) && routes.allows(second), "only the failed stance is skipped within the current pass");
        check(routes.contextFor(second).permit() == TerrainPermit.PRESERVE, "other stances still require existing footing");
        check(routes.nextExistingPass() && routes.allows(first.below()), "only exhausted height-preserving candidates allow descent");
        check(routes.contextFor(first).minimumFeetY() == Integer.MIN_VALUE
                && routes.contextFor(first).permit() == TerrainPermit.PRESERVE, "descent still cannot place supports");
        check(routes.allows(first), "a new pass retries stances that failed under the preceding height floor");
        routes.attempted(); routes.failed(first, "descent did not help");
        check(routes.allowTerrain() && routes.contextFor(first) == construction, "final fallback retains original permissions");
        check(routes.allows(first), "construction access does not inherit an existing-footing failure");
        check(!routes.allowTerrain(), "construction pass cannot loop");
        routes.startAt(second);
        routes.forTarget(new BlockPos(8, 2, 8), second); routes.attempted(); routes.failed(first, "blocked");
        routes.forTarget(new BlockPos(8, 2, 9), second);
        check(routes.allows(first), "another construction target starts with fresh route evidence");
        routes.attempted(); routes.failed(first, "blocked before a support changed");
        routes.environmentChanged();
        check(routes.allows(first), "confirmed world changes invalidate failed navigation stances");
        routes.failed(first, "late failure from the old route");
        check(routes.allows(first), "a stale route result cannot reject a stance after the world changed");
        routes.attempted(); routes.failed(first, "new route also failed");
        check(!routes.allows(first), "a fresh failure remains bounded after invalidation");
        check(routes.contextFor(first).permit() == TerrainPermit.PRESERVE && !routes.allowTerrain(),
                "new construction work must reconsider the newly completed footing");
        var restricted = new BuildStanceNavigation(PlayerNav.ContextProvider.DEFAULT); restricted.nextExistingPass();
        check(!restricted.allowTerrain(),
                "the helper cannot grant construction permission its caller did not have");
        var snapshot = org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy.capture(
                null, protectedCells, forbiddenCells, 2);
        check(snapshot.forbidsBody(100, 1, 100) && !snapshot.forbidsBody(100, 2, 100),
                "route workers and execution share the same height floor");
        check(!org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy.capture(null, null, null)
                .forbidsBody(100, -60, 100), "other tasks retain unrestricted route heights by default");
        System.out.println("BuildStanceNavigationTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

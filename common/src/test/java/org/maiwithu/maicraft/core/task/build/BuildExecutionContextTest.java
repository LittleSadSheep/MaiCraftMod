// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;

/** Supply batches must retain the same mutable receipt ledger, not a stale copy or no guards. */
public final class BuildExecutionContextTest {
    public static void main(String[] args) {
        BuildTaskRecord original = new BuildTaskRecord("original", 100, List.of(), false, true);
        BlockPos protectedCell = new BlockPos(2, 3, 4);
        AtomicInteger generation = new AtomicInteger();
        original.executionGuards(List.of(protectedCell), player -> generation.get() == 0,
                (player, position) -> position.getX() == generation.get(),
                (player, position) -> generation.incrementAndGet());
        BuildTaskRecord rebound = new BuildTaskRecord("rebound", 100, List.of(), false, true);
        BuildTaskRecord batch = new BuildTaskRecord("batch", 100, List.of(), false, true);
        original.copyExecutionContextTo(rebound);
        rebound.copyExecutionContextTo(batch);
        check(batch.hasExecutionGuards(), "batch lost guards");
        check(batch.protectedNavigationCells().equals(List.of(protectedCell)), "batch lost protection");
        check(batch.preflightGuardMatches(null), "initial receipt should match");
        batch.confirmedMutation(null, BlockPos.ZERO);
        check(!original.preflightGuardMatches(null), "receipt ledger was copied instead of shared");
        check(!batch.mutationGuardMatches(null, BlockPos.ZERO), "stale cell was accepted");
        check(batch.mutationGuardMatches(null, new BlockPos(1, 0, 0)), "confirmed state was lost");
        System.out.println("BuildExecutionContextTest: passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

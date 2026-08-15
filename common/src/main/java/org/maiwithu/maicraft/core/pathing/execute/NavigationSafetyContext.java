// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.execute;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * Client-thread task scope for body cells that navigation must never occupy.
 *
 * <p>The semantic parent owns the observed constraint; every nested internal task creates and
 * executes its {@link PlayerNav} while the scope is active. Search contexts copy the set before
 * worker dispatch, so no thread-local state crosses the client-thread boundary.</p>
 */
public final class NavigationSafetyContext {
    private static final ThreadLocal<LongSet> FORBIDDEN_BODY_CELLS = new ThreadLocal<>();

    private NavigationSafetyContext() {}

    public static <T> T withForbiddenBodyCells(
            Iterable<BlockPos> cells, Supplier<T> operation) {
        LongSet previous = FORBIDDEN_BODY_CELLS.get();
        LongOpenHashSet combined = previous == null
                ? new LongOpenHashSet() : new LongOpenHashSet(previous);
        if (cells != null) {
            for (BlockPos cell : cells) if (cell != null) combined.add(cell.asLong());
        }
        if (combined.isEmpty()) return operation.get();
        FORBIDDEN_BODY_CELLS.set(LongSets.unmodifiable(combined));
        try {
            return operation.get();
        } finally {
            if (previous == null) FORBIDDEN_BODY_CELLS.remove();
            else FORBIDDEN_BODY_CELLS.set(previous);
        }
    }

    static LongSet forbiddenBodyCells() {
        LongSet cells = FORBIDDEN_BODY_CELLS.get();
        return cells == null ? LongSets.emptySet() : cells;
    }
}

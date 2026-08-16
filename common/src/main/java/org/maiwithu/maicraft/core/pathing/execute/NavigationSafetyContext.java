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
    private static final ThreadLocal<LongSet> PROTECTED_MUTATION_CELLS = new ThreadLocal<>();
    private static final ThreadLocal<LongSet> FORBIDDEN_BODY_CELLS = new ThreadLocal<>();

    private NavigationSafetyContext() {}

    public static <T> T withForbiddenBodyCells(
            Iterable<BlockPos> cells, Supplier<T> operation) {
        return withProtectedArea(null, cells, operation);
    }

    /**
     * Run one nested task operation with an exact, internally observed area protection.
     * Existing nested scopes are unioned, so a child cannot weaken its parent's hard constraint.
     */
    public static <T> T withProtectedArea(
            Iterable<BlockPos> mutationCells,
            Iterable<BlockPos> bodyCells,
            Supplier<T> operation) {
        LongOpenHashSet mutation = packed(mutationCells);
        LongOpenHashSet body = packed(bodyCells);
        return withProtectedArea(mutation, body, operation);
    }

    /** Packed-cell overload used by a semantic parent on every tick without rebuilding positions. */
    public static <T> T withProtectedArea(
            LongSet mutationCells,
            LongSet bodyCells,
            Supplier<T> operation) {
        LongSet previousMutation = PROTECTED_MUTATION_CELLS.get();
        LongSet previous = FORBIDDEN_BODY_CELLS.get();
        LongSet combinedMutation = combined(previousMutation, mutationCells);
        LongSet combinedBody = combined(previous, bodyCells);
        if (combinedMutation.isEmpty() && combinedBody.isEmpty()) return operation.get();
        if (!combinedMutation.isEmpty()) {
            PROTECTED_MUTATION_CELLS.set(LongSets.unmodifiable(combinedMutation));
        }
        if (!combinedBody.isEmpty()) {
            FORBIDDEN_BODY_CELLS.set(LongSets.unmodifiable(combinedBody));
        }
        try {
            return operation.get();
        } finally {
            if (previousMutation == null) PROTECTED_MUTATION_CELLS.remove();
            else PROTECTED_MUTATION_CELLS.set(previousMutation);
            if (previous == null) FORBIDDEN_BODY_CELLS.remove();
            else FORBIDDEN_BODY_CELLS.set(previous);
        }
    }

    /** Current immutable set; ContextFactory copies it before threaded search dispatch. */
    public static LongSet protectedMutationCells() {
        LongSet cells = PROTECTED_MUTATION_CELLS.get();
        return cells == null ? LongSets.emptySet() : cells;
    }

    /** Current immutable set; ContextFactory copies it before threaded search dispatch. */
    public static LongSet forbiddenBodyCells() {
        LongSet cells = FORBIDDEN_BODY_CELLS.get();
        return cells == null ? LongSets.emptySet() : cells;
    }

    public static boolean protectsMutation(BlockPos pos) {
        return pos != null && protectedMutationCells().contains(pos.asLong());
    }

    public static boolean forbidsBody(BlockPos pos) {
        return pos != null && forbiddenBodyCells().contains(pos.asLong());
    }

    private static LongOpenHashSet packed(Iterable<BlockPos> cells) {
        LongOpenHashSet result = new LongOpenHashSet();
        if (cells != null) {
            for (BlockPos cell : cells) if (cell != null) result.add(cell.asLong());
        }
        return result;
    }

    private static LongSet combined(LongSet outer, LongSet inner) {
        if (outer == null || outer.isEmpty()) {
            return inner == null ? LongSets.emptySet() : inner;
        }
        if (inner == null || inner.isEmpty()) return outer;
        LongOpenHashSet result = new LongOpenHashSet(outer);
        result.addAll(inner);
        return LongSets.unmodifiable(result);
    }
}

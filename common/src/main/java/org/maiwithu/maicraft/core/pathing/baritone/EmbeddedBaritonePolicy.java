// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

/**
 * 把当前导航的保护要求交给 Baritone：哪些格不能改，哪些格身体不能进入。每次先复制集合，再整体替换当前版本。
 * 身体禁入格同时列为不可修改；只禁止修改的格并不自动禁止身体进入。
 */
public final class EmbeddedBaritonePolicy {
    private static volatile Snapshot current = Snapshot.EMPTY;

    private EmbeddedBaritonePolicy() {}

    public static boolean install(
            LongSet sacred,
            LongSet protectedMutations,
            LongSet forbiddenBodyCells) {
        return installSnapshot(capture(sacred, protectedMutations, forbiddenBodyCells));
    }

    /** Freeze a queued owner's policy without changing the body that is still executing. */
    public static Snapshot capture(
            LongSet sacred, LongSet protectedMutations, LongSet forbiddenBodyCells) {
        LongOpenHashSet protectedCells = new LongOpenHashSet();
        if (sacred != null) protectedCells.addAll(sacred);
        if (protectedMutations != null) protectedCells.addAll(protectedMutations);
        if (forbiddenBodyCells != null) protectedCells.addAll(forbiddenBodyCells);

        LongOpenHashSet forbidden = new LongOpenHashSet();
        if (forbiddenBodyCells != null) forbidden.addAll(forbiddenBodyCells);
        return new Snapshot(
                LongSets.unmodifiable(protectedCells),
                LongSets.unmodifiable(forbidden));
    }

    /** Install a snapshot previously detached by {@link #capture}. */
    static boolean installSnapshot(Snapshot next) {
        boolean changed = !next.equals(current);
        current = next;
        return changed;
    }

    public static Snapshot snapshot() {
        return current;
    }

    /** Live execution guard used after a worker's frozen calculation snapshot has aged. */
    public static boolean protects(BlockPos pos) {
        return pos != null && current.protects(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Live execution guard for first-person stance/movement code. */
    public static boolean forbidsBody(BlockPos pos) {
        return pos != null && current.forbidsBody(pos.getX(), pos.getY(), pos.getZ());
    }

    public static void clear() {
        current = Snapshot.EMPTY;
    }

    public record Snapshot(LongSet protectedCells, LongSet forbiddenBodyCells) {
        private static final Snapshot EMPTY = new Snapshot(
                LongSets.emptySet(), LongSets.emptySet());

        public boolean protects(int x, int y, int z) {
            return protectedCells.contains(BlockPos.asLong(x, y, z));
        }

        public boolean forbidsBody(int x, int y, int z) {
            return forbiddenBodyCells.contains(BlockPos.asLong(x, y, z));
        }
    }
}

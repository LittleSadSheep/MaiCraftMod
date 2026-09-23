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

    public static boolean install(LongSet sacred, LongSet protectedMutations, LongSet forbiddenBodyCells, int minimumFeetY) {
        return installSnapshot(capture(sacred, protectedMutations, forbiddenBodyCells, minimumFeetY));
    }

    /** 冻结排队所有者的策略，但不更改仍在执行中的身体控制。 */
    public static Snapshot capture(
            LongSet sacred, LongSet protectedMutations, LongSet forbiddenBodyCells) {
        return capture(sacred, protectedMutations, forbiddenBodyCells, Integer.MIN_VALUE);
    }

    public static Snapshot capture(LongSet sacred, LongSet protectedMutations, LongSet forbiddenBodyCells, int minimumFeetY) {
        LongOpenHashSet protectedCells = new LongOpenHashSet();
        if (sacred != null) protectedCells.addAll(sacred);
        if (protectedMutations != null) protectedCells.addAll(protectedMutations);
        if (forbiddenBodyCells != null) protectedCells.addAll(forbiddenBodyCells);

        LongOpenHashSet forbidden = new LongOpenHashSet();
        if (forbiddenBodyCells != null) forbidden.addAll(forbiddenBodyCells);
        return new Snapshot(
                LongSets.unmodifiable(protectedCells),
                LongSets.unmodifiable(forbidden), minimumFeetY);
    }

    /** 安装先前由 {@link #capture} 分离出的快照。 */
    static boolean installSnapshot(Snapshot next) {
        boolean changed = !next.equals(current);
        current = next;
        return changed;
    }

    public static Snapshot snapshot() {
        return current;
    }

    /** worker 的冻结计算快照过期后，供实时执行阶段使用的保护检查。 */
    public static boolean protects(BlockPos pos) {
        return pos != null && current.protects(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 第一人称站位和移动代码使用的实时保护检查。 */
    public static boolean forbidsBody(BlockPos pos) {
        return pos != null && current.forbidsBody(pos.getX(), pos.getY(), pos.getZ());
    }

    public static void clear() {
        current = Snapshot.EMPTY;
    }

    public record Snapshot(LongSet protectedCells, LongSet forbiddenBodyCells, int minimumFeetY) {
        public Snapshot(LongSet protectedCells, LongSet forbiddenBodyCells) {
            this(protectedCells, forbiddenBodyCells, Integer.MIN_VALUE);
        }
        private static final Snapshot EMPTY = new Snapshot(
                LongSets.emptySet(), LongSets.emptySet());

        public boolean protects(int x, int y, int z) {
            return protectedCells.contains(BlockPos.asLong(x, y, z));
        }

        public boolean forbidsBody(int x, int y, int z) {
            return y < minimumFeetY || forbiddenBodyCells.contains(BlockPos.asLong(x, y, z));
        }
    }
}

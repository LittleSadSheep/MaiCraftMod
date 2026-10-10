// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;

/**
 * 把当前走到任务的保护要求交给内嵌 Baritone：哪些格不得破坏或掩埋，哪些格身体不得进入。
 * 后台搜索与实际走路都从这里取同一份保护；每次安装先复制集合再整体替换，搜索侧拿到的
 * 快照因此与接单时一致，不会被下一次任务中途改写。
 *
 * <p>除了这一趟的保护要求，还有这个世界的保护判断（别人放的方块、记住的区域、归属拿不准的格子）：
 * 进世界时装进来、离开时摘掉，挖路与垫路都要过它，不挖穿别人的房子。它在后台搜索线程上也会被问，
 * 装进来的判断必须能在任何线程上读。
 *
 * <p>这里用一处静态登记，是因为内嵌 Baritone 的搜索与移动对象都由它自己创建，
 * 无法从构造函数把服务传进去；走到的实现方在接单时把保护要求装进来，结束时清掉。
 */
public final class NavigationProtection {

    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.EMPTY);
    private static final AtomicReference<CellRule> WORLD = new AtomicReference<>(CellRule.NONE);

    /** 这个世界的保护判断：一格方块受不受保护；任何线程都能问。 */
    @FunctionalInterface
    public interface CellRule {
        /** 什么都不保护：没进世界时用它。 */
        CellRule NONE = (x, y, z) -> false;

        boolean protects(int x, int y, int z);
    }

    private NavigationProtection() {}

    /** 安装一份新的保护要求；内容与当前一致时不替换，返回是否发生了变化。 */
    public static boolean install(LongSet sacred, LongSet noEntryCells, int minimumFeetY) {
        Snapshot next = new Snapshot(copy(sacred), copy(noEntryCells), minimumFeetY);
        boolean changed = !next.equals(CURRENT.get());
        CURRENT.set(next);
        return changed;
    }

    /** 取回当前保护要求；搜索开始前调用一次并冻结，供整个计算过程使用。 */
    public static Snapshot snapshot() {
        return CURRENT.get();
    }

    /** 进世界时装上这个世界的保护判断；离开世界时装回 {@link CellRule#NONE}。 */
    public static void installWorldRule(CellRule rule) {
        WORLD.set(rule == null ? CellRule.NONE : rule);
    }

    /** 这一格受不受这个世界的保护（别人放的、记住的区域里、归属拿不准）：挖路与垫路前问它。 */
    public static boolean worldProtects(int x, int y, int z) {
        return WORLD.get().protects(x, y, z);
    }

    /** 走到结束后清空保护，避免上一次任务的保护格挡住下一次无关路线。 */
    public static void clear() {
        CURRENT.set(Snapshot.EMPTY);
    }

    /** 这一刻的实时保护检查：搜索走冻结的快照，实际挖掘与站位走这里。 */
    public static boolean protects(BlockPos pos) {
        return pos != null && (CURRENT.get().protects(pos.getX(), pos.getY(), pos.getZ())
                || worldProtects(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** 这一刻的实时禁入检查：身体是否被允许站进这一格。 */
    public static boolean forbidsEntry(BlockPos pos) {
        return pos != null && CURRENT.get().forbidsEntry(pos.getX(), pos.getY(), pos.getZ());
    }

    private static LongSet copy(LongSet cells) {
        if (cells == null || cells.isEmpty()) return LongSets.emptySet();
        LongOpenHashSet copied = new LongOpenHashSet(cells.size());
        copied.addAll(cells);
        return LongSets.unmodifiable(copied);
    }

    /**
     * 一份不可变的保护要求：受保护格不得破坏或掩埋；禁入格与脚位下界约束身体可站的位置。
     * 只禁修改的格并不自动禁止身体进入，两个集合各管各的。
     */
    public record Snapshot(LongSet protectedCells, LongSet noEntryCells, int minimumFeetY) {
        public static final Snapshot EMPTY = new Snapshot(LongSets.emptySet(), LongSets.emptySet(), Integer.MIN_VALUE);

        public Snapshot {
            protectedCells = LongSets.unmodifiable(new LongOpenHashSet(protectedCells));
            noEntryCells = LongSets.unmodifiable(new LongOpenHashSet(noEntryCells));
        }

        public boolean protects(int x, int y, int z) {
            return protectedCells.contains(BlockPos.asLong(x, y, z));
        }

        public boolean forbidsEntry(int x, int y, int z) {
            return y < minimumFeetY || noEntryCells.contains(BlockPos.asLong(x, y, z));
        }

        /** 是否带身体侧的约束；没有时移动搜索可以跳过整圈站位核对。 */
        public boolean hasEntryConstraints() {
            return minimumFeetY != Integer.MIN_VALUE || !noEntryCells.isEmpty();
        }
    }
}

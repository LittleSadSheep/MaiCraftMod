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
 * <p>这里用一处静态登记，是因为内嵌 Baritone 的搜索与移动对象都由它自己创建，
 * 无法从构造函数把服务传进去；走到的实现方在接单时把保护要求装进来，结束时清掉。
 */
public final class NavigationProtection {

    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.EMPTY);

    private NavigationProtection() {}

    /** 安装一份新的保护要求；内容与当前一致时不替换，返回是否发生了变化。 */
    public static boolean install(LongSet sacred, LongSet forbiddenBodyCells, int minimumFeetY) {
        Snapshot next = new Snapshot(copy(sacred), copy(forbiddenBodyCells), minimumFeetY);
        boolean changed = !next.equals(CURRENT.get());
        CURRENT.set(next);
        return changed;
    }

    /** 取回当前保护要求；搜索开始前调用一次并冻结，供整个计算过程使用。 */
    public static Snapshot snapshot() {
        return CURRENT.get();
    }

    /** 走到结束后清空保护，避免上一次任务的保护格挡住下一次无关路线。 */
    public static void clear() {
        CURRENT.set(Snapshot.EMPTY);
    }

    /** 这一刻的实时保护检查：搜索走冻结的快照，实际挖掘与站位走这里。 */
    public static boolean protects(BlockPos pos) {
        return pos != null && CURRENT.get().protects(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 这一刻的实时禁入检查：身体是否被允许站进这一格。 */
    public static boolean forbidsBody(BlockPos pos) {
        return pos != null && CURRENT.get().forbidsBody(pos.getX(), pos.getY(), pos.getZ());
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
    public record Snapshot(LongSet protectedCells, LongSet forbiddenBodyCells, int minimumFeetY) {
        public static final Snapshot EMPTY = new Snapshot(LongSets.emptySet(), LongSets.emptySet(), Integer.MIN_VALUE);

        public Snapshot {
            protectedCells = LongSets.unmodifiable(new LongOpenHashSet(protectedCells));
            forbiddenBodyCells = LongSets.unmodifiable(new LongOpenHashSet(forbiddenBodyCells));
        }

        public boolean protects(int x, int y, int z) {
            return protectedCells.contains(BlockPos.asLong(x, y, z));
        }

        public boolean forbidsBody(int x, int y, int z) {
            return y < minimumFeetY || forbiddenBodyCells.contains(BlockPos.asLong(x, y, z));
        }

        /** 是否带身体侧的约束；没有时移动搜索可以跳过整圈站位核对。 */
        public boolean hasBodyConstraints() {
            return minimumFeetY != Integer.MIN_VALUE || !forbiddenBodyCells.isEmpty();
        }
    }
}

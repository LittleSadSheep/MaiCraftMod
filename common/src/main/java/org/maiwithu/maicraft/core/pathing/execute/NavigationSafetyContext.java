// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.execute;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * 让一段任务代码及其子调用共用两份名单：“不能改动的格”和“身体不能进入的格”。
 * 例如去取材料时，不能为了抄近路拆掉正在施工的墙，也不能走进保留给机器的空间。
 * 名单只对当前线程有效；异步寻路必须把它复制到自己的计算数据中，不能在工作线程重新读这里。
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
     * 在执行 operation 这段代码期间加上保护格；子任务可以增加保护，不能把父任务的保护拿掉。
     */
    public static <T> T withProtectedArea(
            Iterable<BlockPos> mutationCells,
            Iterable<BlockPos> bodyCells,
            Supplier<T> operation) {
        LongOpenHashSet mutation = packed(mutationCells);
        LongOpenHashSet body = packed(bodyCells);
        return withProtectedArea(mutation, body, operation);
    }

    /** 已经有压缩成 long 的坐标集合时直接使用，避免每刻重新创建 BlockPos。 */
    public static <T> T withProtectedArea(
            LongSet mutationCells,
            LongSet bodyCells,
            Supplier<T> operation) {
        // 先记住外层保护，再把本层新增的格合进去；不改调用方传入的原集合。
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
        // 无论任务正常返回还是抛异常，都恢复进入前的保护范围，避免污染下一个无关任务。
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

    // 只回答这格是否在“不能改动”名单里；不在名单里也不等于已经获得所有破坏权限。
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

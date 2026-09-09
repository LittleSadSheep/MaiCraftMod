// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 记住每个世界中哪些格子刚收到服务器的方块实体更新。安装前记一次编号，安装后编号变大才说明收到了新消息。
 * 它证明的是服务器有新的同步，不是“服务器确认了某个请求编号”；调用者仍要检查部件确实正确。只在游戏线程使用。
 */
public final class ServerBlockEntityReceipts {
    private static final Map<Level, LinkedHashMap<Long, Long>> RECEIVED = new WeakHashMap<>();
    private static final Map<Level, Map<Long, Integer>> WATCHED = new WeakHashMap<>();
    private static long sequence;
    private ServerBlockEntityReceipts() {}

    public static long revision(Level level, BlockPos position) {
        Map<Long, Long> scope = RECEIVED.get(level);
        return scope == null ? 0 : scope.getOrDefault(position.asLong(), 0L);
    }

    public static void received(Level level, BlockPos position) {
        LinkedHashMap<Long, Long> scope = RECEIVED.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        scope.remove(position.asLong());
        scope.put(position.asLong(), ++sequence);
        trim(level, scope);
    }

    /**
     * 开始等待某一格的新同步，并暂时保留它的记录；工厂里其他格子更新再多，也不能挤掉这格正在等待的结果。
     */
    public static Watch watch(Level level, BlockPos position) {
        WATCHED.computeIfAbsent(level, ignored -> new java.util.HashMap<>()).merge(position.asLong(), 1, Integer::sum);
        return new Watch(level, position.immutable(), revision(level, position));
    }

    // 普通记录超过四千零九十六条时从最旧的开始删；正在被任务观察的记录保留，可能暂时超过这个数量。
    private static void trim(Level level, LinkedHashMap<Long, Long> scope) {
        Map<Long, Integer> pins = WATCHED.getOrDefault(level, Map.of());
        var iterator = scope.keySet().iterator();
        while (scope.size() > 4096 && iterator.hasNext()) {
            if (!pins.containsKey(iterator.next())) iterator.remove();
        }
    }

    public static final class Watch implements AutoCloseable {
        private final Level level;
        private final BlockPos position;
        private final long baseline;
        private boolean closed;
        private Watch(Level level, BlockPos position, long baseline) { this.level = level; this.position = position; this.baseline = baseline; }
        public boolean advanced() { return !closed && revision(level, position) > baseline; }
        // 结束一次观察，减少这格的保留次数；多个任务都在等同一格时，要等最后一个结束才允许清理。
        @Override public void close() {
            if (closed) return;
            closed = true;
            Map<Long, Integer> pins = WATCHED.get(level);
            if (pins != null) {
                pins.computeIfPresent(position.asLong(), (ignored, count) -> count == 1 ? null : count - 1);
                if (pins.isEmpty()) WATCHED.remove(level);
            }
            LinkedHashMap<Long, Long> scope = RECEIVED.get(level);
            if (scope != null) trim(level, scope);
        }
    }
}

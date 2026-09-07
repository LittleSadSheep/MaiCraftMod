// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Actual processed server BE packets, distinct from client-predicted world changes. Game-thread only.
 * Revisions certify the resulting observed server state, not a request ID. A task captures its
 * baseline immediately before native use, so earlier processed packets cannot confirm that use.
 * World-instance keys prevent reconnect/dimension reuse; cancelling never resets a baseline.
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

    /** Pending native actions pin their own position so busy factories cannot evict their receipt. */
    public static Watch watch(Level level, BlockPos position) {
        WATCHED.computeIfAbsent(level, ignored -> new java.util.HashMap<>()).merge(position.asLong(), 1, Integer::sum);
        return new Watch(level, position.immutable(), revision(level, position));
    }

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

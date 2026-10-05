// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.scan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * 记录客户端世界最近同步的方块变化位置，供读侧扫描优先核查。
 * 扫描游标单调推进，每个格每轮只采样一次；刚落格的方块若在游标经过时尚未同步，
 * 或落在 count 满足之后的格序里，就会被整轮跳过。这里只存位置，
 * 是否真是目标、能否看见仍由各读侧按现场状态与视线闸裁决。
 */
public final class RecentBlockWrites {
    /** 每个世界最多保留这么多条最近变化；超出后淘汰最旧的。 */
    private static final int CAPACITY = 256;
    /** 只把这么近的变化当作"刚发生"；更早的写入交回常规扫描游标覆盖。 */
    private static final int FRESH_TICKS = 100;

    private record Entry(long gameTime, BlockPos pos) {}

    private static final Map<ClientLevel, ArrayDeque<Entry>> WRITES = new WeakHashMap<>();

    private RecentBlockWrites() {}

    /** 客户端方块状态每次变化时记录；只记位置，不区分放置、破坏或状态改写。 */
    public static void record(ClientLevel level, BlockPos pos) {
        synchronized (WRITES) {
            ArrayDeque<Entry> trail = WRITES.computeIfAbsent(level, key -> new ArrayDeque<>());
            long now = level.getGameTime();
            while (!trail.isEmpty() && now - trail.peekFirst().gameTime() > FRESH_TICKS) trail.pollFirst();
            if (trail.size() >= CAPACITY) trail.pollFirst();
            trail.addLast(new Entry(now, pos.immutable()));
        }
    }

    /** 最新的变化排在前面；同刻多次变化按发生顺序倒排，供读侧从最近事实开始核查。 */
    public static List<BlockPos> recent(ClientLevel level) {
        synchronized (WRITES) {
            ArrayDeque<Entry> trail = WRITES.get(level);
            if (trail == null) return List.of();
            long now = level.getGameTime();
            List<BlockPos> fresh = new ArrayList<>();
            for (Entry entry : trail) if (now - entry.gameTime() <= FRESH_TICKS) fresh.add(entry.pos());
            for (int head = 0, tail = fresh.size() - 1; head < tail; head++, tail--) {
                BlockPos swap = fresh.get(head);
                fresh.set(head, fresh.get(tail));
                fresh.set(tail, swap);
            }
            return List.copyOf(fresh);
        }
    }

    /** 测试隔离用；正常运行不需要清空，世界实例回收后条目随之失效。 */
    public static void clear() {
        synchronized (WRITES) {
            WRITES.clear();
        }
    }
}

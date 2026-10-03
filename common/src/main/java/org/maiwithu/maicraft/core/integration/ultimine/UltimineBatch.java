// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** 原生预览只说明准备挖哪些格；触发块确认后逐格复查，区分已挖、留下和真正未知的副作用。 */
public final class UltimineBatch {
    public record Observation(Map<BlockPos, BlockState> removed, List<Map<String, Object>> cells,
                              int remaining, int unknown) {
        public Observation { removed = Collections.unmodifiableMap(new LinkedHashMap<>(removed)); cells = List.copyOf(cells); }
        public boolean allRemoved() { return cells.size() == removed.size(); }
    }
    private final BlockPos origin;
    private final Map<BlockPos, BlockState> before;

    public UltimineBatch(BlockPos origin, List<BlockPos> selection, Function<BlockPos, BlockState> read) {
        this.origin = origin.immutable();
        var snapshot = new LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos position : selection) {
            BlockState state = read.apply(position);
            if (state == null || state.isAir()) throw new IllegalArgumentException("native selection changed before submission");
            snapshot.put(position.immutable(), state);
        }
        if (!snapshot.containsKey(origin) || snapshot.size() != selection.size())
            throw new IllegalArgumentException("native selection must contain its origin exactly once");
        before = Collections.unmodifiableMap(snapshot);
    }

    /** 未确认触发块时只呈现世界变化；不能把别人的挖掘或客户端预测当成本任务完成了整批。 */
    public Observation observe(Function<BlockPos, BlockState> read, boolean originConfirmed) {
        var removed = new LinkedHashMap<BlockPos, BlockState>();
        var cells = new ArrayList<Map<String, Object>>();
        int remaining = 0, unknown = 0;
        for (var entry : before.entrySet()) {
            BlockPos at = entry.getKey(); BlockState expected = entry.getValue(), actual = read.apply(at);
            String status;
            if (actual == null) { status = "unloaded"; unknown++; }
            else if (originConfirmed && (at.equals(origin) || actual.isAir())) {
                // 原点有自己的原生回执；副目标只有服务端同步成空气后才记为本次连锁的已观察破坏。
                status = at.equals(origin) && !actual.isAir() ? "origin_break_confirmed_then_changed" : "removed";
                removed.put(at, expected);
            } else if (actual == expected) { status = "remaining"; remaining++; }
            else { status = originConfirmed ? "changed" : "changed_without_confirmed_origin"; unknown++; }
            var cell = new LinkedHashMap<String, Object>();
            cell.put("position", position(at));
            cell.put("before_block_id", BuiltInRegistries.BLOCK.getKey(expected.getBlock()).toString());
            cell.put("before_state", expected.toString());
            cell.put("after_state", actual == null ? "unloaded" : actual.toString());
            cell.put("status", status); cells.add(Map.copyOf(cell));
        }
        return new Observation(removed, cells, remaining, unknown);
    }

    public BlockPos origin() { return origin; }
    public int size() { return before.size(); }
    public static List<Integer> position(BlockPos at) { return List.of(at.getX(), at.getY(), at.getZ()); }
}

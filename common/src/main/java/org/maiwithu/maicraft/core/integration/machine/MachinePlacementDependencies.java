// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** 把依赖其他最终方块的安装分层；普通结构和原生带先完成，附件随后按依赖顺序放置。 */
public final class MachinePlacementDependencies {
    private MachinePlacementDependencies() {}
    public static List<List<BlockPos>> layers(Map<BlockPos, List<BlockPos>> dependencies) {
        Map<BlockPos, Integer> pending = new LinkedHashMap<>(); Map<BlockPos, List<BlockPos>> followers = new LinkedHashMap<>();
        dependencies.forEach((at, required) -> {
            int count = 0;
            for (BlockPos dependency : required.stream().distinct().toList()) if (dependencies.containsKey(dependency)) {
                count++; followers.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(at);
            }
            pending.put(at, count);
        });
        List<List<BlockPos>> result = new ArrayList<>(); int settled = 0;
        List<BlockPos> ready = pending.entrySet().stream().filter(entry -> entry.getValue() == 0).map(Map.Entry::getKey).toList();
        while (!ready.isEmpty()) {
            List<BlockPos> layer = ready.stream().sorted(Comparator.comparingInt((BlockPos at) -> at.getY()).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
            result.add(layer); settled += layer.size(); List<BlockPos> next = new ArrayList<>();
            for (BlockPos at : layer) for (BlockPos follower : followers.getOrDefault(at, List.of()))
                if (pending.compute(follower, (key, count) -> count - 1) == 0) next.add(follower);
            ready = next;
        }
        if (settled != dependencies.size()) throw new IllegalArgumentException("native_placement_dependency_cycle");
        return List.copyOf(result);
    }
}

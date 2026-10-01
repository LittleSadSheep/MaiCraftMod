// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;

/** 在原取材半径内分刻观察来源；原木复用采矿的天然树判定，观察结果不计入背包库存。 */
public final class NearbyMaterialSources {
    public record Seen(BlockPos position, double distanceSquared, boolean naturalTree) {}
    private final BlockGetter world;
    private final Predicate<BlockPos> loaded, protectedCell;
    private final BlockPos center;
    private final int radius;
    private final Set<Block> targets;
    private final Iterator<BlockPos> cells;
    private final NaturalTreeSource trees = new NaturalTreeSource();
    private final Map<Block, Seen> observed = new LinkedHashMap<>();
    private BlockPos deferred;
    private boolean complete, unloadedTreeEvidence;
    private int inspectedCells, unloadedCells;

    public NearbyMaterialSources(BlockGetter world, Predicate<BlockPos> loaded,
            Predicate<BlockPos> protectedCell, BlockPos center, int radius, Set<Block> targets) {
        if (radius < 1 || radius > 48) throw new IllegalArgumentException("material observation radius");
        this.world = world; this.loaded = loaded; this.protectedCell = protectedCell;
        this.center = center.immutable(); this.radius = radius; this.targets = Set.copyOf(targets);
        cells = BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius)).iterator();
        complete = targets.isEmpty();
    }

    public boolean advance() {
        if (complete) return true;
        // 每刻保留游标继续扫描；预算耗尽只延后观察，不能把尚未读到的树当作不存在。
        trees.beginQuery();
        long until = System.nanoTime() + 2_000_000L;
        for (int work = 0; work < 4096 && System.nanoTime() < until; work++) {
            if (deferred == null && !cells.hasNext()) { complete = true; break; }
            BlockPos cell = deferred == null ? cells.next() : deferred;
            deferred = null;
            if (center.distSqr(cell) > (double) radius * radius) continue;
            if (!loaded.test(cell)) { unloadedCells++; continue; }
            inspectedCells++;
            if (protectedCell.test(cell)) continue;
            var state = world.getBlockState(cell);
            if (!targets.contains(state.getBlock())) continue;
            boolean timber = state.is(BlockTags.LOGS);
            if (timber && !trees.accepts(cell, world, loaded)) {
                // 天然树检查另有每刻预算；当前候选留到下一刻，缺区块证据则单独报告。
                unloadedTreeEvidence |= trees.unloadedEvidence;
                if (trees.budgetDeferred) { deferred = cell.immutable(); break; }
                continue;
            }
            var seen = new Seen(cell.immutable(), center.distSqr(cell), timber);
            observed.merge(state.getBlock(), seen,
                    (old, next) -> old.distanceSquared() <= next.distanceSquared() ? old : next);
        }
        if (deferred == null && !cells.hasNext()) complete = true;
        return complete;
    }

    public boolean complete() { return complete; }
    public BlockPos center() { return center; }

    public Optional<Seen> nearest(Collection<Block> blocks) {
        // 选路线前复核记录位置仍是原方块，防止旧观察把已经砍掉或被保护的来源继续排到前面。
        return blocks.stream().filter(observed::containsKey).filter(block -> current(block, observed.get(block)))
                .map(observed::get).min(Comparator.comparingDouble(Seen::distanceSquared));
    }

    public boolean changed() {
        return observed.entrySet().stream().anyMatch(entry -> !current(entry.getKey(), entry.getValue()));
    }

    private boolean current(Block block, Seen seen) {
        return loaded.test(seen.position()) && !protectedCell.test(seen.position())
                && world.getBlockState(seen.position()).is(block);
    }

    public Map<String, Object> describe() {
        // 每种方块只需最近一处作为排序依据；不给出未验证的掉落数量或可达承诺。
        List<Map<String, Object>> sources = observed.entrySet().stream()
                .filter(entry -> current(entry.getKey(), entry.getValue()))
                .map(entry -> {
                    Seen seen = entry.getValue();
                    return Map.<String, Object>of("block_id", BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString(),
                            "position", List.of(seen.position().getX(), seen.position().getY(), seen.position().getZ()),
                            "distance_squared", seen.distanceSquared(), "natural_tree", seen.naturalTree());
                }).toList();
        return Map.of("scan_finished", complete, "scope", "loaded_cells_within_acquisition_radius",
                "center", List.of(center.getX(), center.getY(), center.getZ()), "radius", radius,
                "inspected_cell_count", inspectedCells, "unloaded_cell_count", unloadedCells,
                "unloaded_tree_evidence", unloadedTreeEvidence, "sources", sources,
                "inventory_credit", 0);
    }
}

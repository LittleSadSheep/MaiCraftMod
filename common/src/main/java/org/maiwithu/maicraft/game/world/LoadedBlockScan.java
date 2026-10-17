// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

/** 只读查询逐格推进同一轮区块柱扫描；隐藏候选不会要求重新排序或重扫已经检查过的石层。 */
public final class LoadedBlockScan {
    private static final int SECTION_CELLS = 4096;
    private final ClientLevel level;
    private final BlockPos origin;
    private final Set<Block> targets;
    private final long radiusSquared;
    private final List<ChunkPos> columns = new ArrayList<>();
    private final int[] sectionOrder;
    private int layer, column, cell;
    private int finishedSections, unloadedSections, emptySections;
    private long processedCells, examinedStates, candidates;

    public LoadedBlockScan(ClientLevel level, BlockPos origin, Collection<Block> targets, int radius) {
        if (radius < 0 || targets.isEmpty()) throw new IllegalArgumentException("invalid loaded block scan");
        this.level = level; this.origin = origin.immutable(); this.targets = Set.copyOf(targets);
        radiusSquared = (long) radius * radius;
        sectionOrder = SearchGeometry.sectionOrder(level.getMinSection(), level.getMaxSection() - 1,
                SectionPos.blockToSectionCoord(origin.getY()));
        int cx = SectionPos.blockToSectionCoord(origin.getX()), cz = SectionPos.blockToSectionCoord(origin.getZ());
        int rings = (radius + 15) / 16;
        for (int ring = 0; ring <= rings; ring++) for (int at = 0; at < RingSpiral.perimeter(ring); at++) {
            int[] offset = RingSpiral.offset(ring, at);
            ChunkPos candidate = new ChunkPos(cx + offset[0], cz + offset[1]);
            // 圆形范围完全碰不到的区块不属于本次查询，不能把它们记成“未加载的未知部分”。
            long dx = (long) origin.getX() - Math.clamp(origin.getX(), candidate.getMinBlockX(), candidate.getMaxBlockX());
            long dz = (long) origin.getZ() - Math.clamp(origin.getZ(), candidate.getMinBlockZ(), candidate.getMaxBlockZ());
            if (dx * dx + dz * dz <= radiusSquared) columns.add(candidate);
        }
    }

    /** 每刻限制读取和视线核查的工作时间，预算用完只让步；下一刻从段内下一格继续。 */
    public boolean advance(int workBudget, long nanosBudget, Predicate<BlockPos> inspect) {
        if (workBudget < 1 || nanosBudget < 1) throw new IllegalArgumentException("positive scan budget required");
        long started = System.nanoTime();
        int work = 0;
        while (!complete() && work < workBudget && (work == 0 || System.nanoTime() - started < nanosBudget)) {
            ChunkPos at = columns.get(column);
            var chunk = level.getChunkSource().getChunkNow(at.x, at.z);
            if (chunk == null) { unloadedSections++; finishSection(); work++; continue; }
            var section = chunk.getSections()[sectionOrder[layer] - level.getMinSection()];
            if (section == null) { unloadedSections++; finishSection(); work++; continue; }
            // 调色板只用来证明该段没有目标，不能把未加载区块或未检查完的密集段判成空。
            if (cell == 0 && (section.hasOnlyAir()
                    || !section.maybeHas(state -> targets.contains(state.getBlock())))) {
                emptySections++; finishSection(); work++; continue;
            }
            int activeLayer = layer, activeColumn = column;
            while (cell < SECTION_CELLS && work < workBudget
                    && (work == 0 || System.nanoTime() - started < nanosBudget)) {
                int packed = cell++; work++; processedCells++;
                int x = at.getMinBlockX() + (packed & 15), z = at.getMinBlockZ() + (packed >> 4 & 15);
                long dx = (long) x - origin.getX(), dz = (long) z - origin.getZ();
                boolean stop = false;
                if (dx * dx + dz * dz <= radiusSquared) {
                    examinedStates++;
                    if (targets.contains(section.getBlockState(packed & 15, packed >> 8, packed >> 4 & 15).getBlock())) {
                        candidates++;
                        stop = inspect.test(new BlockPos(x, SectionPos.sectionToBlockCoord(sectionOrder[layer]) + (packed >> 8), z));
                    }
                }
                if (cell == SECTION_CELLS) finishSection();
                if (stop) return true;
                if (layer != activeLayer || column != activeColumn) break;
            }
        }
        return false;
    }

    /** 先扫角色高度附近的整圈区块，再向上、向下扩展；完成的区块段不再重复进入。 */
    private void finishSection() {
        processedCells += SECTION_CELLS - cell;
        finishedSections++; cell = 0;
        if (++column == columns.size()) { column = 0; layer++; }
    }

    public boolean complete() { return layer >= sectionOrder.length; }
    public long processedCells() { return processedCells; }
    public long totalCells() { return (long) columns.size() * sectionOrder.length * SECTION_CELLS; }
    public long examinedStates() { return examinedStates; }
    public long candidates() { return candidates; }
    public int finishedSections() { return finishedSections; }
    public int totalSections() { return columns.size() * sectionOrder.length; }
    public int unloadedSections() { return unloadedSections; }
    public int emptySections() { return emptySections; }
}

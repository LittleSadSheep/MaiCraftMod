// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.function.LongSupplier;

/**
 * 整体准备与分区刷新共享每帧约四毫秒，另限准备步数、重建格数和重排分区数。
 * 在每个工作单元之间让出；不能中断一次原版形状查询或显卡操作，不是整帧耗时的硬上限。
 */
final class PreviewFrameBudget {
    private final LongSupplier clock;
    private final long deadline;
    private int preparationSteps, rebuildCells, resortSections;

    PreviewFrameBudget() { this(System::nanoTime, 4_000_000, 512, 8); }
    PreviewFrameBudget(LongSupplier clock, long nanos, int rebuildCells, int resortSections) {
        this(clock, nanos, 16_384, rebuildCells, resortSections);
    }
    PreviewFrameBudget(LongSupplier clock, long nanos, int preparationSteps, int rebuildCells, int resortSections) {
        this.clock = clock; deadline = clock.getAsLong() + nanos;
        this.preparationSteps = preparationSteps;
        this.rebuildCells = rebuildCells; this.resortSections = resortSections;
    }
    boolean hasTime() { return clock.getAsLong() < deadline; }
    boolean claimPreparation() {
        if (!hasTime() || preparationSteps <= 0) return false;
        preparationSteps--; return true;
    }
    // 有剩余额度就允许完整做完这一分区，然后扣它的格数；额度可能变负，不会在分区内部中途停下。
    boolean claim(PreviewSectionRefresh.Work work, int cells) {
        if (!hasTime()) return false;
        if (work == PreviewSectionRefresh.Work.REBUILD && rebuildCells > 0) {
            rebuildCells -= Math.max(1, cells); return true;
        }
        if (work == PreviewSectionRefresh.Work.RESORT && resortSections > 0) {
            resortSections--; return true;
        }
        return false;
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.function.LongSupplier;

/**
 * 每帧给分块刷新约四毫秒，另限重建格数和重新排序的分区数。它不负责首次生成整张蓝图外轮廓的耗时。
 */
final class PreviewFrameBudget {
    private final LongSupplier clock;
    private final long deadline;
    private int rebuildCells, resortSections;

    PreviewFrameBudget() { this(System::nanoTime, 4_000_000, 512, 8); }
    PreviewFrameBudget(LongSupplier clock, long nanos, int rebuildCells, int resortSections) {
        this.clock = clock; deadline = clock.getAsLong() + nanos;
        this.rebuildCells = rebuildCells; this.resortSections = resortSections;
    }
    boolean hasTime() { return clock.getAsLong() < deadline; }
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

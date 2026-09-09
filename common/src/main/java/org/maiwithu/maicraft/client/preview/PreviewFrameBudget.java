// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.function.LongSupplier;

/** Geometry and index-only work share a time limit, with separate volume caps. */
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

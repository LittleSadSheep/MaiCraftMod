// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.function.LongSupplier;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/**
 * 整体准备与分区刷新共享配置的每帧耗时，另按配置限制准备步数、重建格数和重排分区数。
 * 在每个工作单元之间让出；不能中断一次原版形状查询或显卡操作，不是整帧耗时的硬上限。
 */
final class PreviewFrameBudget {
    private final LongSupplier clock;
    private final long started, duration;
    private int preparationSteps, rebuildCells, resortSections;

    PreviewFrameBudget() { this(System::nanoTime); }
    PreviewFrameBudget(LongSupplier clock) {
        // 每帧新建时取一次启动快照，避免预览类早于配置加载而永久使用旧默认值；本帧内不改变额度。
        var limits = BuildingBudgets.current();
        this.clock = clock; started = clock.getAsLong();
        duration = (long) Math.ceil(limits.previewFrameMillis() * 1_000_000.0);
        preparationSteps = limits.previewPreparationSteps(); rebuildCells = limits.previewRebuildCells();
        resortSections = limits.previewResortSections();
    }
    PreviewFrameBudget(LongSupplier clock, long nanos, int rebuildCells, int resortSections) {
        this(clock, nanos, BuildingBudgets.current().previewPreparationSteps(), rebuildCells, resortSections);
    }
    PreviewFrameBudget(LongSupplier clock, long nanos, int preparationSteps, int rebuildCells, int resortSections) {
        this.clock = clock; started = clock.getAsLong(); duration = Math.max(0, nanos);
        this.preparationSteps = preparationSteps;
        this.rebuildCells = rebuildCells; this.resortSections = resortSections;
    }
    // 比较已消耗时间，避免较大的配置预算与nanoTime相加溢出后让整帧误判为超时。
    boolean hasTime() { long elapsed = clock.getAsLong() - started; return elapsed >= 0 && elapsed < duration; }
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

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 找床的只读接缝：扫一片已加载的世界，给出候选床与"扫完没有"。测试给固定值。
 *
 * <p>扫描分几刻走，{@code complete=false} 时找到的部分只是已扫到的，不能当成"附近没有床"。
 */
public interface BedScanner {

    /**
     * 扫到的候选床与是否扫完。
     *
     * @param excluded           试过不行的床，不再报
     * @param protectedLandmarks 这次任务额外不许碰的地标名；落进这些地标的床按受保护筛掉
     */
    BedScan scan(TickContext context, Set<BlockPos> excluded, Set<String> protectedLandmarks);

    /** 一次扫描的结果。 */
    record BedScan(List<BedCandidate> candidates, boolean complete) {
    }
}

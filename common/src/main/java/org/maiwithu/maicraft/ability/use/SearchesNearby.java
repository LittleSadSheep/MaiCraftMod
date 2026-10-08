// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 附近搜索：按方块或实体类型在一片范围里找最近的目标，只查已加载的区域。
 *
 * <p>扫描可能分几刻做完：结果带"是否扫完"，没扫完不等于没有，调用方下一刻再问。
 * 路径代价没接上时按距离就近。 landmark、f#、here 这类"一片地方"的目标用它落实成具体的一格。
 */
public interface SearchesNearby {

    /** 范围内最近的一种方块；还没扫完时 scannedComplete 为假，找到了就在 found 里。 */
    BlockResult nearestBlock(String blockTypeId, BlockPos center, int radius);

    /** 范围内最近的一种实体；找到了连实体对象一起给（瞄准与骑乘要用）。 */
    EntityResult nearestEntity(String entityTypeId, BlockPos center, int radius);

    /**
     * 一次方块搜索的结果。
     *
     * @param found            找到的最近一格；没有或还没扫完时为空
     * @param scannedComplete  范围内的已加载区域是否都扫完了
     */
    record BlockResult(Optional<WorldPosition> found, boolean scannedComplete) {
        public BlockResult {
            if (found == null) throw new IllegalArgumentException("found 不能为 null，用 Optional.empty()");
        }
    }

    /** 一次实体搜索的结果；实体对象与它所在的格一起给。 */
    record EntityResult(Optional<Entity> found, boolean scannedComplete) {
        public EntityResult {
            if (found == null) throw new IllegalArgumentException("found 不能为 null，用 Optional.empty()");
        }
    }

    /** 全部搜索结果（给结果细节分组用）；不悄悄截断。 */
    List<WorldPosition> allOf(String blockTypeId, BlockPos center, int radius);
}

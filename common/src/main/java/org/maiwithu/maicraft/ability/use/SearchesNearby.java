// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/**
 * 附近搜索：按方块（或方块标签）、实体类型在一片范围里找最近的目标，只查已加载的区域。
 *
 * <p>扫描可能分几刻做完：结果带"是否扫完"，没扫完不等于没有，调用方下一刻再问。
 * 路径代价没接上时按直线距离就近。地标、f#、脚边这类"一片地方"的目标用它落实成具体的一格或一只。
 */
public interface SearchesNearby {

    /** 范围内最近的一格给定方块（# 开头是标签），skip 认下的跳过；还没扫完时 scannedComplete 为假。 */
    BlockResult nearestBlock(String blockOrTag, BlockPos center, int radius, Predicate<BlockPos> skip);

    /** 范围内最近的一只给定类型的实体，给游戏实体编号，skip 认下的跳过；不含角色自己。 */
    EntityResult nearestEntity(String entityTypeId, BlockPos center, int radius, IntPredicate skip);

    /**
     * 一次方块搜索的结果。
     *
     * @param found            找到的最近一格；没有或还没扫完时为空
     * @param scannedComplete  范围内的已加载区域是否都扫完了
     */
    record BlockResult(Optional<BlockPos> found, boolean scannedComplete) {
        public BlockResult {
            if (found == null) throw new IllegalArgumentException("found 不能为 null，用 Optional.empty()");
        }
    }

    /**
     * 一次实体搜索的结果。
     *
     * @param found            找到的那只的游戏实体编号；没有时为空
     * @param scannedComplete  是否都看过了（实体就在客户端的实体表里，一次看完）
     */
    record EntityResult(OptionalInt found, boolean scannedComplete) {
        public EntityResult {
            if (found == null) throw new IllegalArgumentException("found 不能为 null，用 OptionalInt.empty()");
        }
    }
}

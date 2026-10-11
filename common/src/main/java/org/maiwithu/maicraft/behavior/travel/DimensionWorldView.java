// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 跨维度行程的现场只读视图：现在的维度、找活门、门还活着没有、身体沾没沾门格、
 * 离门落脚格、读一格方块、扫黑曜石与末地门框架。跨维度任务、建门与末地门房间
 * 只从这里读现场，不碰角色对象；生产实现读当刻的客户端世界，测试用替身摆场景。
 */
public interface DimensionWorldView {

    /** 角色此刻所在的维度（命名空间维度 id）；读不到时为 null。 */
    String currentDimension();

    /** 角色此刻脚下的方块格。 */
    BlockPos feet();

    /** 找一轮活门：这一刻找得到的入口与覆盖情况；没扫完不下"没找到"的结论。 */
    PortalFinder.Round scanPortals(Block portalBlock, int searchDistance, Set<Long> attemptedCells);

    /** 这一格现在还是不是活的门方块（区块加载着、方块对得上）。 */
    boolean portalAlive(Block portalBlock, BlockPos cell);

    /** 身体这一刻有没有沾着门格：脚位或头位还是活的门方块。 */
    boolean touchesPortal(Block portalBlock, Set<Long> portalCells);

    /** 门口附近挑一格离门的干燥落脚格；挑不到（四面是渊或水）给空。 */
    Optional<BlockPos> stepAsideCell(Set<Long> portalCells);

    /** 读一格方块；没加载的格没有证据，给空。 */
    Optional<BlockState> stateAt(BlockPos cell);

    /** 身上带着这件物品吗（主背包与快捷栏）；读不到背包给假。 */
    boolean carries(String itemId);

    /** 扫一轮指定的方块（建门勘察黑曜石、末地门房间扫框架）：这一刻的命中与扫完没有。 */
    Scan scanBlocks(Block target, int searchDistance, int want);

    /** 一轮方块扫描的收成。 */
    record Scan(List<BlockPos> hits, boolean complete) {
    }
}

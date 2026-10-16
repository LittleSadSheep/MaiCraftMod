// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 末地主岛的出口门：原版把它建在龙的战斗原点上（主岛中心，原版是 0,0）。中心一柱四格基岩，
 * 门洞那一层离门心不到 2.5 格的格子是门格，再外一圈是基岩边沿。龙死后门格变成末地传送门方块，
 * 龙被复活时又清掉，所以门洞那一层有没有门方块就是"门亮没亮"。
 * 打龙时龙场读数认门、龙死后出行走出口门回主世界，都用这一份。
 */
public final class EndExitPortal {

    /** 原版主岛中心的水平坐标：龙的战斗原点，出口门的门心就在这一柱上。 */
    public static final int CENTER_X = 0;
    public static final int CENTER_Z = 0;
    /** 门洞那一层上离门心这么远的四格查门方块：都是门格，又不挨着中心那根基岩柱。 */
    static final int RING = 2;

    private EndExitPortal() {}

    /**
     * 一眼出口门。
     *
     * @param center 门心（高度是门洞那一层）；没加载时高度没有意义
     * @param loaded 门心旁那一列的区块加载着：没加载就看不见门，亮没亮都不算数
     * @param lit    门洞那一层有门方块：龙死后门亮了，走进去就回去
     */
    public record Sighting(BlockPos center, boolean loaded, boolean lit) {
    }

    /** 门洞那一层离门心两格的四格：东西南北各一格。 */
    public static List<BlockPos> ringCells(BlockPos center) {
        return List.of(center.offset(RING, 0, 0), center.offset(-RING, 0, 0),
                center.offset(0, 0, RING), center.offset(0, 0, -RING));
    }

    /**
     * 门亮没亮：四格里有一格读得到、是末地传送门方块就亮了。读不到的格（没加载）没有证据，不算亮。纯函数。
     *
     * @param read   按格读方块，没加载的格给空
     * @param center 门心，高度是门洞那一层
     */
    public static boolean lit(Function<BlockPos, Optional<BlockState>> read, BlockPos center) {
        for (BlockPos cell : ringCells(center)) {
            Optional<BlockState> state = read.apply(cell);
            if (state.isPresent() && state.get().is(Blocks.END_PORTAL)) return true;
        }
        return false;
    }

    /**
     * 从世界里看一眼门心在 (x, z) 的出口门。门洞那一层取门心旁两格那一列的地表高度：
     * 门格不挡路，门洞里亮没亮，那一列的地表都落在门洞那一层上（下面是基岩，上面是空的）。
     */
    public static Sighting look(Level level, int x, int z) {
        BlockPos column = new BlockPos(x + RING, level.getMinBuildHeight(), z);
        // 没加载的区块读出来是空气、地表落在世界最底层：先认加载没有，不把"看不见"当成"没亮"。
        if (!level.isLoaded(column)) {
            return new Sighting(new BlockPos(x, level.getMinBuildHeight(), z), false, false);
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + RING, z);
        BlockPos center = new BlockPos(x, y, z);
        boolean lit = lit(cell -> level.isLoaded(cell) ? Optional.of(level.getBlockState(cell)) : Optional.empty(),
                center);
        return new Sighting(center, true, lit);
    }
}

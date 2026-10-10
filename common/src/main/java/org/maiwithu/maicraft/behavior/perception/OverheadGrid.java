// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.navigation.cache.LoadedOnlyView;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;

/**
 * 俯视网格：以角色为中心、北向朝上、每格对应一个方块的可通行分类字符图。
 *
 * <p>每个格子按移动类型和角色当前高度带的垂直通行能力归类：平地、上台阶、下台阶、
 * 落差、墙、水、岩浆。把高度合并成一个符号后，看一眼图就能理解地形、障碍、缺口
 * 和可跳过的台阶，不用逐格探测。未加载的区块如实标出，不冒充可通行。
 *
 * <p>把多个高度切片合并为一个移动符号，以及危险方块的"谨慎"缓冲区，参考自动驾驶
 * 导航中的占据栅格与分层代价地图做法；此表示方式参考自我中心语义网格的研究
 * （arXiv:2410.08500）。可通行判断只此一份，复用寻路共用的方块通行判断。
 */
public final class OverheadGrid {

    /** 默认半径：视野边长 17 格，够看清近处地形又不至于刷屏。 */
    public static final int DEFAULT_RADIUS = 8;
    public static final int MIN_RADIUS = 4;
    public static final int MAX_RADIUS = 16;
    /** 地面低于脚位多少格后，该格才显示为落差。 */
    public static final int DROP_DEPTH = 3;

    // 地图格子符号。
    /** 角色自己所在的中心格。 */
    public static final char YOU = '@';
    /** 同高度可行走。 */
    public static final char FLAT = '.';
    /** 可通过向上跳一格抵达。 */
    public static final char STEP_UP = '^';
    /** 可步行到达，且比当前位置低 1 至 2 格。 */
    public static final char STEP_DOWN = ',';
    /** 下落高度达到落差深度或以上。 */
    public static final char DROP = 'v';
    /** 通道被阻挡，或需要上升至少 2 格。 */
    public static final char WALL = '#';
    public static final char WATER = '~';
    /** 熔岩或火焰：危险格，不能当作普通通道。 */
    public static final char HAZARD = '!';
    /** 危险方块旁的膨胀缓冲区。 */
    public static final char CAUTION = 'x';
    public static final char TREE = 'T';
    /** 区块未加载，看不清。 */
    public static final char UNLOADED = '?';

    private OverheadGrid() {}

    /** 一次俯视网格的结果：中心、边长与字符格；第 0 行在北，第 0 列在西。 */
    public record View(BlockPos center, int radius, char[][] cells) {

        /** 图例：读图时按这行解释每个符号。 */
        public String legend() {
            return "1 格 = 1 方块，@ 是你，北朝上（-Z），东朝右（+X） | "
                    + "." + " 平地 ^ 上台阶1 , 下台阶1-2 v 落差>=" + DROP_DEPTH
                    + " # 墙/挡住 ~ 水 ! 岩浆/危险 x 谨慎(危险旁) T 树 ? 未加载";
        }
    }

    /**
     * 以给定中心画一张俯视网格：只读客户端此刻已加载的区块，未加载处标问号，
     * 画完给岩浆与火焰四周补一圈谨慎缓冲。
     */
    public static View render(Level level, BlockPos center, int radius) {
        // 只在已加载的区块上取方块：不为看一眼地形把远处区块强行加载进来。
        BlockGetter view = LoadedOnlyView.of(level);
        LoadedOnlyView loaded = view instanceof LoadedOnlyView wrapped ? wrapped : null;
        int clamped = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        int size = 2 * clamped + 1;
        char[][] grid = new char[size][size];
        for (int row = 0; row < size; row++) {
            int dz = row - clamped;            // 第 0 行位于北侧，也就是图示顶部。
            for (int col = 0; col < size; col++) {
                int dx = col - clamped;        // 第 0 列位于西侧，也就是图示左侧。
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (loaded != null && !loaded.isLoaded(x, z)) {
                    grid[row][col] = UNLOADED;
                } else {
                    grid[row][col] = classify(view, x, center.getY(), z);
                }
            }
        }
        inflateHazards(grid);
        return new View(center.immutable(), clamped, grid);
    }

    /** 将 (x,z) 柱列汇总为一个位于角色当前 Y 高度带的语义移动符号；包内可见，测试用替身世界直接驱动。 */
    static char classify(BlockGetter level, int x, int feetY, int z) {
        BlockPos pos = new BlockPos(x, feetY, z);
        BlockState feetState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());

        if (isLava(feetState) || isLava(headState)) {
            return HAZARD;
        }
        if (feetState.getBlock() instanceof LiquidBlock || headState.getBlock() instanceof LiquidBlock) {
            return WATER;
        }

        // 在可跳上或短距离下落范围内，选择角色能够站立的最高表面。
        Integer standY = null;
        for (int y = feetY + 1; y >= feetY - DROP_DEPTH; y--) {
            if (canStandAt(level, x, y, z)) {
                standY = y;
                break;
            }
        }
        if (standY == null) {
            boolean bodyClear = BlockPassability.canWalkThrough(level, pos)
                    && BlockPassability.canWalkThrough(level, pos.above());
            if (!bodyClear) {
                return (isTree(feetState) || isTree(headState)) ? TREE : WALL;
            }
            return DROP; // 身体可通过但附近没有地面支撑：深坑或虚空。
        }
        int delta = standY - feetY;
        if (delta >= 2) {
            return WALL;
        }
        if (delta == 1) {
            return STEP_UP;
        }
        if (delta == 0) {
            return FLAT;
        }
        if (delta >= -2) {
            return STEP_DOWN;
        }
        return DROP;
    }

    private static boolean canStandAt(BlockGetter level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        return BlockPassability.canWalkOn(level, feet.below())
                && BlockPassability.canWalkThrough(level, feet)
                && BlockPassability.canWalkThrough(level, feet.above());
    }

    /** 按分层代价地图的方式，在岩浆和火焰四周补一圈谨慎缓冲，让角色与危险边缘保持距离。 */
    private static void inflateHazards(char[][] grid) {
        int size = grid.length;
        boolean[][] near = new boolean[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (grid[row][col] == HAZARD) {
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int nr = row + dr;
                            int nc = col + dc;
                            if (nr >= 0 && nr < size && nc >= 0 && nc < size) {
                                near[nr][nc] = true;
                            }
                        }
                    }
                }
            }
        }
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (near[row][col] && isWalkable(grid[row][col])) {
                    grid[row][col] = CAUTION;
                }
            }
        }
    }

    private static boolean isWalkable(char cell) {
        return cell == FLAT || cell == STEP_UP || cell == STEP_DOWN;
    }

    private static boolean isTree(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }

    private static boolean isLava(BlockState state) {
        return state.is(Blocks.LAVA);
    }
}

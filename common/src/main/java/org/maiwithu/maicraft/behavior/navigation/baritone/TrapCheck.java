// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayDeque;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

/**
 * 卡坑判断：只走不挖不垫，从脚下这格能走到的地方是不是一个封死的坑。
 * 在水平 16 格、上下 12 格的盒子里按玩家走路的样子铺开——平走、跳上一格台阶、往下跳三格以内、
 * 水里上下游（从水流游上去也算走得出去）——铺到盒子边上就是出得去；铺不到边，
 * 又只有很小一片（不到 24 格站得住的地方），或整片都见不到天，就是困住了。
 *
 * <p>围起来的大院子、见得到天的大圈不算坑：多半是有人围的，任务许可说不动土就不动。
 * 区块没加载完、铺得太大，都按出得去处理，不为说不清的情况放宽许可。纯计算，世界从接缝读，可离线测试。
 */
final class TrapCheck {

    /** 铺开的范围：水平半径与上下高差。 */
    static final int RADIUS = 16;
    static final int HEIGHT = 12;
    /** 封死的一片站得住的格子不到这么多，就算小坑，见不见得到天都算困住。 */
    static final int SMALL_POCKET = 24;
    /** 往下跳最多几格：再高会摔伤，只走的路线也不跳。 */
    static final int MAX_DROP = 3;
    /** 铺到这么多格还没铺完，说明是一大片，按出得去处理。 */
    private static final int MAX_VISITED = 6000;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 世界里一格的样子：铺开只问这几件事。 */
    interface Cells {
        /** 身体能占这一格：没有碰撞（空气、草、花）、徒手能推开的门与栅栏门、水；岩浆、火、仙人掌这类不算。 */
        boolean open(int x, int y, int z);

        /** 能站在这一格上面：顶面能踩，或者是水（踩着水）。 */
        boolean floor(int x, int y, int z);

        boolean water(int x, int y, int z);

        /** 这一格能见到天。 */
        boolean sky(int x, int y, int z);

        /** 这一列的区块加载着。 */
        boolean loaded(int x, int z);
    }

    private TrapCheck() {}

    /** 脚下这格是不是困在封死的坑里。 */
    static boolean trapped(Cells cells, BlockPos feet) {
        int fx = feet.getX();
        int fy = feet.getY();
        int fz = feet.getZ();
        LongSet seen = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(feet.asLong());
        queue.add(feet);
        boolean sawSky = false;
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst();
            sawSky |= cells.sky(at.getX(), at.getY() + 1, at.getZ());
            for (BlockPos next : moves(cells, at)) {
                if (next == null) return false;
                if (Math.abs(next.getX() - fx) > RADIUS || Math.abs(next.getZ() - fz) > RADIUS
                        || Math.abs(next.getY() - fy) > HEIGHT) {
                    return false;
                }
                if (seen.add(next.asLong())) {
                    if (seen.size() > MAX_VISITED) return false;
                    queue.addLast(next);
                }
            }
        }
        return seen.size() < SMALL_POCKET || !sawSky;
    }

    // 从这一格只走能到的相邻格：平走、跳上一格、往下跳三格以内、水里上下游。区块没加载的方向给 null（说不清，按出得去）。
    private static BlockPos[] moves(Cells cells, BlockPos at) {
        int x = at.getX();
        int y = at.getY();
        int z = at.getZ();
        BlockPos[] found = new BlockPos[SIDES.length + 2];
        int count = 0;
        for (int[] side : SIDES) {
            int nx = x + side[0];
            int nz = z + side[1];
            if (!cells.loaded(nx, nz)) return new BlockPos[] {null};
            BlockPos step = step(cells, x, y, z, nx, nz);
            if (step != null) found[count++] = step;
        }
        // 水里：往上游、往下潜都算走。
        if (cells.water(x, y, z)) {
            if (stand(cells, x, y + 1, z)) found[count++] = new BlockPos(x, y + 1, z);
            if (cells.water(x, y - 1, z) && stand(cells, x, y - 1, z)) found[count++] = new BlockPos(x, y - 1, z);
        }
        BlockPos[] result = new BlockPos[count];
        System.arraycopy(found, 0, result, 0, count);
        return result;
    }

    // 往一个方向走一步：同一层能站就平走；头顶有空、前面高一格能站就跳上去；前面是空的就往下找三格内能站的地方。
    private static BlockPos step(Cells cells, int x, int y, int z, int nx, int nz) {
        if (stand(cells, nx, y, nz)) return new BlockPos(nx, y, nz);
        if (cells.open(x, y + 2, z) && stand(cells, nx, y + 1, nz)) return new BlockPos(nx, y + 1, nz);
        if (!cells.open(nx, y, nz) || !cells.open(nx, y + 1, nz)) return null;
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            if (!cells.open(nx, y - drop, nz)) return null;
            if (stand(cells, nx, y - drop, nz)) return new BlockPos(nx, y - drop, nz);
        }
        return null;
    }

    // 身体站得进这一格：脚、头两格都能占，脚下能踩（或泡在水里）。
    private static boolean stand(Cells cells, int x, int y, int z) {
        return cells.open(x, y, z) && cells.open(x, y + 1, z)
                && (cells.floor(x, y - 1, z) || cells.water(x, y, z));
    }
}

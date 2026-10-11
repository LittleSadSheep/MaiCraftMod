// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * 路线前方被正在动的东西挡着（转着的风车、开过来的火车、推着的活塞）：先原地等它过去，最多 5 秒；
 * 还挡着就不等了，交回引擎按新的障碍绕。每 10 刻看一次路线前方 16 格；停着的东西挡路不等，引擎自己会绕。
 */
final class MovingObstacleWait {

    /** 每隔多少刻看一次前方。 */
    static final int CHECK_EVERY_TICKS = 10;
    /** 看前方多少格。 */
    static final int AHEAD_BLOCKS = 16;
    /** 最多等多少刻（5 秒）。 */
    static final int MAX_WAIT_TICKS = 100;

    private long nextCheck = Long.MIN_VALUE;
    private long waitingSince = -1;
    private boolean blocked;
    /** 这次等满了还挡着：交回引擎绕，同一处不再等。 */
    private boolean gaveUp;

    /**
     * 这一刻要不要原地等。
     *
     * @param tick   当刻的客户端刻
     * @param ahead  路线上从脚下往前的落脚格（按顺序）
     * @param moving 正在动的结构此刻挡着的地方
     */
    boolean holds(long tick, List<BlockPos> ahead, List<AABB> moving) {
        if (tick >= nextCheck) {
            nextCheck = tick + CHECK_EVERY_TICKS;
            blocked = !moving.isEmpty() && blocks(ahead, moving);
            if (!blocked) {
                // 挡着的东西过去了：下一次被挡重新计时。
                waitingSince = -1;
                gaveUp = false;
            }
        }
        if (!blocked || gaveUp) return false;
        if (waitingSince < 0) waitingSince = tick;
        if (tick - waitingSince >= MAX_WAIT_TICKS) {
            gaveUp = true;
            return false;
        }
        return true;
    }

    /** 在等：给日志与面板说一句。 */
    boolean waiting() {
        return blocked && !gaveUp && waitingSince >= 0;
    }

    // 路线前方 16 格里有一格站人的身位碰到正在动的东西；脚下这格已经在它要扫过的地方时不等，接着走开。
    static boolean blocks(List<BlockPos> ahead, List<AABB> moving) {
        if (ahead.isEmpty() || touches(ahead.get(0), moving)) return false;
        BlockPos first = ahead.get(0);
        for (int i = 1; i < ahead.size(); i++) {
            BlockPos cell = ahead.get(i);
            if (first.distManhattan(cell) > AHEAD_BLOCKS) break;
            if (touches(cell, moving)) return true;
        }
        return false;
    }

    private static boolean touches(BlockPos cell, List<AABB> moving) {
        AABB body = new AABB(cell.getX() + 0.2, cell.getY(), cell.getZ() + 0.2,
                cell.getX() + 0.8, cell.getY() + 1.8, cell.getZ() + 0.8);
        for (AABB box : moving) {
            if (box.intersects(body)) return true;
        }
        return false;
    }
}

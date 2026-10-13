// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 往下挖楼梯找东西：附近看不见石头时，像玩家一样从脚下斜着往下挖一段楼梯，
 * 每下一级先从上往下挖开前面三格、再走下去，挖出来的土和石头都捡起来；挖够了就停在楼梯底。
 * 长程挖矿也用它：往下挖台阶到矿多的那一层，再横着挖两格高的通道，看见要的矿就去挖。
 * 实现接在启动时创建并登记上，测试用替身。
 */
public interface DigsStairsDown {

    /**
     * @param yields      哪种方块挖了会掉想要的东西（按方块注册 ID 判断，例如石头掉圆石）
     * @param wantedCells 挖够几格这样的方块就停
     * @param permissions 这次任务的许可：楼梯上每一格动之前都过许可检查
     */
    Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions);

    /**
     * 长程挖矿：在 targetY 之上就往下挖三格高的台阶，到了这一层横着挖两格高的通道；每走一步看一眼，
     * 看见要的东西就停下交给 mineInSight 去挖。装了连锁挖时每一行按住连锁键一下挖出一长条
     * （往下用斜着的、横着用直着的），线上每一格都要挖得、许可准挖、四周不挨水和岩浆才连。
     * 实现还不支持时返回空，来源如实说给不了。
     */
    default Optional<Action> tunnelToward(Tunnel tunnel, Permissions permissions) {
        return Optional.empty();
    }

    /**
     * 一次长程挖矿要往哪挖、挖到什么时候。
     *
     * @param targetY     在哪一层横着挖：在它上面先挖台阶下去
     * @param floorY      往下连锁时斜线最低挖到哪层：还在这种矿的生成范围里就行
     * @param yields      挖了会掉想要的东西的方块（按方块注册 ID）：通道正好穿过矿时也算挖到了
     * @param wantedCells 通道里直接挖到这么多格就停
     * @param inSight     站在这一格能不能看见要的矿
     * @param mineInSight 看见以后从这一格去把看见的挖了、捡起来；看见的都不许挖时为空
     */
    record Tunnel(int targetY, int floorY, Predicate<String> yields, int wantedCells,
                  Predicate<BlockPos> inSight, Function<BlockPos, Optional<Action>> mineInSight) {}
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.spi;

import java.util.Optional;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 能把人直接送上高处的随身装备（穿着、背罐有气的喷气背包）。寻路算路时问它这一趟最多能飞上几格、
 * 每格多久，拿来和垫方块往上搭比哪个省事；走到"飞上去"那一步时先让它开好（拨开关、读回），
 * 然后按着跳往上飞、到了高度挪到落脚处。落地后由"走路前收起来"把它关上。由联动模组登记。
 */
public interface FlyUpGear {

    /** 此刻能飞多高、多快；身上没有、没穿着、气不够为空。只读，规划路线时问。 */
    Optional<Reach> reach(PlayerContext context);

    /** 此刻已经开好、按着跳就能往上飞。 */
    boolean ready(PlayerContext context);

    /** 开到能往上飞的样子的做法（开飞行、开悬停，读回一致才算好）。 */
    Action prepare(PlayerContext context);

    /**
     * 一次能飞上去多少。
     *
     * @param maxBlocks     一次最多往上飞几格（按背罐里的气留了余量）
     * @param ticksPerBlock 往上飞一格大约几刻
     */
    record Reach(int maxBlocks, double ticksPerBlock) {}
}

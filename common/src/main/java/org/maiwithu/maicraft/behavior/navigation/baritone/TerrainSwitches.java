// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 把地形许可的四档翻译成内嵌 Baritone 的挖／放开关取值：不改就四个动土开关全关，路线只走现成的路；
 * 只垫不挖就放开垫块与跑酷垫、关掉挖与向下挖；能挖天然与一切不受保护的都全开。
 * 跑酷跳单独由许可决定，许可说不跑酷就关掉，免得在脚手架、墙头上几格也跳；
 * 落地办法单独由许可的第二项决定，不随动土开关放宽。纯值对象，可在无游戏环境下测试。
 *
 * @param allowBreak 允许挖开挡路的方块
 * @param allowPlace 允许垫上垫块开路
 * @param allowParkourPlace 允许在跑酷中途垫块
 * @param allowDownward 允许向下挖台阶
 * @param allowLandingAids 会摔伤的一跳允许放东西接住自己
 * @param allowParkour 允许跑酷跳
 */
record TerrainSwitches(boolean allowBreak, boolean allowPlace, boolean allowParkourPlace,
                       boolean allowDownward, boolean allowLandingAids, boolean allowParkour) {

    static TerrainSwitches of(TerrainPermit permit) {
        // 只垫不挖：垫块与跑酷垫放行，挖开与向下挖都关掉——temporary 档的路线只往上搭，不拆。
        boolean mayDig = permit.changes() == Permissions.BlockChanges.NATURAL
                || permit.changes() == Permissions.BlockChanges.ANY;
        boolean mayPlace = permit.mayChangeTerrain();
        // 不许跑酷时跑酷垫也一起关：没有跑酷跳就没有跑酷中途垫块。
        boolean mayParkourPlace = mayPlace && permit.mayParkour();
        return new TerrainSwitches(mayDig, mayPlace, mayParkourPlace, mayDig, permit.mayUseLandingAids(), permit.mayParkour());
    }
}

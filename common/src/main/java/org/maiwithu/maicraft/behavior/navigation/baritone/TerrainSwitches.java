// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;

/**
 * 把地形许可翻译成内嵌 Baritone 的挖／放开关取值：不许动地形时四个动土开关全关，
 * 路线只能走现成的路；落地水单独由许可的第二项决定，不随动土开关放宽。
 * 纯值对象，可在无游戏环境下测试。
 *
 * @param allowBreak 允许挖开挡路的方块
 * @param allowPlace 允许垫上垫块开路
 * @param allowParkourPlace 允许在跑酷中途垫块
 * @param allowDownward 允许向下挖台阶
 * @param allowWaterBucketFall 摔落时允许用手里的水桶放水缓冲
 */
record TerrainSwitches(boolean allowBreak, boolean allowPlace, boolean allowParkourPlace,
                       boolean allowDownward, boolean allowWaterBucketFall) {

    static TerrainSwitches of(TerrainPermit permit) {
        boolean terrain = permit.mayChangeTerrain();
        return new TerrainSwitches(terrain, terrain, terrain, terrain, permit.mayUseWaterBucket());
    }
}

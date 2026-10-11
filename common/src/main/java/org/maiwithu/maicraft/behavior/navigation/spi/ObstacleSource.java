// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.spi;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 障碍来源：一个区域里会动的结构此刻在世界里的碰撞盒与运动。只读，只在主线程、每次算路前与沿路检查时被问；
 * 读不全的结构照样报上来，标成没读全（寻路按整体范围绕开），不能漏报成"没有障碍"。
 */
public interface ObstacleSource {

    /**
     * 把和这个区域（世界坐标）沾边的结构逐个交给回答口。
     *
     * @param viewer 走路的角色：它正站在、坐在的结构要标出来，脚下的甲板不当墙
     */
    void obstacles(Level level, Player viewer, AABB region, ObstacleSink sink);
}

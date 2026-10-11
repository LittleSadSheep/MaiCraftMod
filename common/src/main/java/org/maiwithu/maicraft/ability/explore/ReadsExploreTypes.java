// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.explore;

/**
 * 读游戏类型目录的只读接缝：探索的目标与留意清单里写的东西在这个游戏里认不认得出。
 * 数据来自游戏自己的注册表（含模组注册）；参数校验用它，认不出的开局一次报全，不进游戏才碰壁。
 * 实现在本包的读端，测试用替身。
 */
public interface ReadsExploreTypes {

    /** 群系 ID 或 # 标签认得出（标签下至少挂着一个注册群系）。 */
    boolean biomeExists(String selector);

    /** 地形要素（lava_pool 这类探索自带的留意词）。 */
    boolean terrainExists(String selector);

    /** 方块 ID 或 # 方块标签认得出。 */
    boolean blockExists(String selector);

    /** 实体类型 ID 认得出（含模组）。 */
    boolean entityTypeExists(String selector);

    /** 结构 ID 认得出（含模组与数据包注册的）。 */
    boolean structureExists(String selector);
}

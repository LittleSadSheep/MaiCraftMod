// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;
import java.util.Set;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 附近方块视线接缝：按方块类型读角色附近的可见方块，供设施（箱子、工作台、熔炉、床）
 * 的观察整理用。实现留给游戏接口层（在已登记的方块扫描上做），测试用替身。
 */
public interface NearbyBlocksSight {

    /** 附近属于给定类型、且看得见的方块 sighting，每个方块一条。 */
    List<BlockSighting> nearby(Set<String> blockTypes);

    /** 一个看得见的方块：位置与类型注册 ID。 */
    record BlockSighting(WorldPosition position, String blockType) {}
}

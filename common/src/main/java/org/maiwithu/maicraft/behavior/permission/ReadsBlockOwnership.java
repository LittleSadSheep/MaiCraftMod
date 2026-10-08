// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Optional;

/**
 * 读方块归属的只读接缝：查一格是谁放的。归属以服务端的记录为准，实现经服务端的只读查询接过来。
 *
 * <p>查不到（本模组装上之前放的、记录被淘汰了）返回空，调用方再靠区域与玩家放置推断兜底，
 * 拿不准按受保护处理。实现留给游戏接口层轨。
 */
public interface ReadsBlockOwnership {

    /** 谁放了这一格；没记到返回空。 */
    Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z);

    /**
     * 一条归属：放它的人。
     *
     * @param playerId 放置者的玩家编号
     */
    record PlacedBy(String playerId) {
        public PlacedBy {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("放置者编号不能为空");
            }
        }
    }
}

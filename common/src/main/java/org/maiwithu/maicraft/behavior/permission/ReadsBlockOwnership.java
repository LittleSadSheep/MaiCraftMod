// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Optional;

/**
 * 读方块归属的只读接缝：查一格是谁放的。归属以服务端的记录为准，实现按区块经服务端的只读查询接过来。
 *
 * <p>先问知不知道：这一格所在的区块问过了才算知道，没问过就是拿不准，调用方按受保护处理。
 * 知道了才看是谁放的：没记录（本模组装上之前放的、记录被淘汰了）返回空，调用方再靠区域与玩家放置推断兜底。
 */
public interface ReadsBlockOwnership {

    /** 谁放了这一格；没记到、或还不知道返回空。 */
    Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z);

    /** 这一格的归属问过了没有；没问过就是拿不准。实现可以在这时记下要问。替身默认都问过了。 */
    default boolean known(String dimension, int x, int y, int z) {
        return true;
    }

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

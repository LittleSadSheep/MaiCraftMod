// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 玩家放置推断的只读接缝：服务端的归属记录查不到时，按方块类别和周围环境猜"这格是不是玩家放的"。
 *
 * <p>客户端本身分不出天然与放置（散落在野外的箱子可能是村庄的，也可能是玩家落的），
 * 所以这是个猜测：拿不准就猜成是玩家放的，宁可少动一格。启发写在游戏接口层，
 * 因为要读世界细节；接线之前使用方传 {@link #NOTHING}，行为照常，只是推断帮不上忙。
 */
public interface GuessesPlayerMade {

    /** 什么都不猜：接缝还没接上时的占位，行为层照常工作，只是少了这条保护来源。 */
    GuessesPlayerMade NOTHING = (position, blockType) -> false;

    /** 这一格像不像玩家放的；像，就按受保护处理。 */
    boolean likelyPlayerMade(WorldPosition position, String blockType);
}

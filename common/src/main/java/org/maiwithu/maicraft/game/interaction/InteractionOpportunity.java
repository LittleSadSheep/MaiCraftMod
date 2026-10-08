// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 本刻的交互机会：每刻只准向游戏提交一次交互，世界动作、菜单点击和模组协议共用同一份计数。
 * 由启动时创建一份并交给交互提交与界面操作两个入口，保证它们不会在同一刻各点一次。
 */
public final class InteractionOpportunity {
    private long claimedTick = Long.MIN_VALUE;

    /** 核对上下文仍是本刻后占用这一刻的交互机会；本刻已经出过手时返回假。 */
    public boolean tryClaim(PlayerContext context) {
        if (!available(context)) return false;
        claimedTick = context.clientTick();
        return true;
    }

    /** 只读判断这一刻还有没有交互机会，不占用它。 */
    public boolean available(PlayerContext context) {
        return context.isCurrent() && context.canInteractThisTick()
                && claimedTick != context.clientTick();
    }
}

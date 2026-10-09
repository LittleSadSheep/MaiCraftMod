// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.menu.ClientQuickMoves;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 界面整堆搬运的接缝实现：把存东西能力的快速移动请求转给玩家行为层的整堆搬运读端。
 * 当刻没有角色上下文（角色不在场）时调用会失败：存东西的流程只在界面开着时走到这里，
 * 界面开着而上下文不在说明接线不完整，如实报错而不是悄悄什么都不做。
 */
final class MenuQuickMoves implements DepositSeams.QuickMoves {

    private final ClientQuickMoves quickMoves;
    private final Supplier<PlayerContext> contexts;

    MenuQuickMoves(ClientQuickMoves quickMoves, Supplier<PlayerContext> contexts) {
        this.quickMoves = Objects.requireNonNull(quickMoves);
        this.contexts = Objects.requireNonNull(contexts);
    }

    @Override
    public void quickMove(int playerSlotId) {
        PlayerContext context = contexts.get();
        if (context == null) {
            throw new IllegalStateException("界面开着但拿不到当刻的角色上下文，整堆搬运做不了");
        }
        quickMoves.quickMove(context, playerSlotId);
    }
}

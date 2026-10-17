// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 持续使用期间对"按住使用键"投影的窄接缝：逐刻续期一次已提交的持用，结束时松开。
 * 只有任务自己已经开始的那一次持用可以续；归属核对不通过时投影自动还回真实键值。
 * 真实实现包装游戏层的按住使用键投影；测试用替身记录调用。
 */
public interface UseKeyProjection {

    /**
     * 把这次持用的按住投影续到下一个期限。投影被别的任务占用、上下文失效或
     * 归属核对不通过时返回假，此时原版看到真实键值，按住可能自然松开。
     *
     * @param owner   发起这次持用的动作；只有同一个所有者能续同一笔提交
     * @param context 本刻的角色上下文
     * @param pending 这笔持续使用的确认记录
     * @param hand    持用的那只手
     * @param before  提交时手上的东西；换了东西投影不再替它按住
     */
    boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                  InteractionHand hand, ItemStack before);

    /** 结束或改判时交还投影；只交还自己占用的那次，不动别的任务的持用。 */
    void release(Object owner);
}

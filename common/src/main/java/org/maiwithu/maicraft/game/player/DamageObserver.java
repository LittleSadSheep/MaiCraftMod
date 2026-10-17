// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * 本地玩家挨打的事实出口：服务器的伤害包说清了是谁、什么伤（摔落、溺水、仙人掌、哪只怪），
 * 由网络包的 Mixin 在客户端线程喂进来。听的一方据此记仇、给提醒，不用再猜"血掉了是谁打的"。
 */
public interface DamageObserver {

    /**
     * 本玩家受到一次伤害。
     *
     * @param source   原版的伤害来源：类型（msgId）与造成伤害的实体（可能为空）
     * @param gameTick 这一刻的游戏刻
     */
    void hurt(LocalPlayer player, DamageSource source, long gameTick);
}

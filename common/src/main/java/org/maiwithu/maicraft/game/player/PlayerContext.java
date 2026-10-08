// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/**
 * 本刻的角色上下文：这一刻 Mod 控制的本地玩家、所在世界，以及本刻还能不能向游戏提交交互。
 *
 * <p>每刻由游戏接口层重新取得，只在本刻有效，不能留到下一刻使用：重生、换世界之后玩家对象会换掉。
 * 移动输入、交互提交与容器界面三个入口之后也挂在这里。
 */
public interface PlayerContext {

    /** 本刻的本地玩家，也就是 Mod 控制的角色。 */
    LocalPlayer localPlayer();

    /** 本刻角色所在的客户端世界。 */
    ClientLevel level();

    /** 本刻角色使用的网络连接；交互确认按连接核对，防止换服后还用旧连接发操作。 */
    ClientPacketListener connection();

    /** 本刻角色的移动与视角输入入口；只有自动化拥有控制权时指令才会被接受。 */
    PlayerInput input();

    /** 客户端刻号；同一刻内重复读取得到相同的值，用来判断手里的上下文是不是已经过期。 */
    long clientTick();

    /** 这份上下文是否仍属于本刻；过期的上下文不能再用来操作角色。 */
    boolean isCurrent();

    /** 本刻是否还能向游戏提交一次交互。每刻只准提交一次，所以所有任务都是"每刻做一点"。 */
    boolean canInteractThisTick();
}

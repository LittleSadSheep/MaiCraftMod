// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;

/**
 * 本刻的角色上下文：这一刻 Mod 控制的本地玩家、所在世界，以及本刻还能不能向游戏提交交互。
 *
 * <p>每刻由游戏接口层重新取得，只在本刻有效，不能留到下一刻使用：重生、换世界之后玩家对象会换掉。
 * 移动输入、交互提交与容器界面三个入口都挂在这里。
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

    /**
     * 本刻角色的交互提交入口：向游戏提交一次原生交互并逐刻等确认。
     * 世界动作与容器界面点击共享同一份每刻一次的交互机会；启动时接上之前为 {@code null}。
     */
    InteractionSender interactionSender();

    /** 本刻角色的容器界面操作入口：点击、搬运与关闭都经它提交；启动时接上之前为 {@code null}。 */
    MenuActions menuActions();

    /**
     * 本刻角色的背包视图：只读地看背包主格里有什么、还空几格。
     * 每刻随上下文新建，只包住当刻的玩家对象；测试替身没有背包时为 {@code null}。
     */
    default BackpackView backpack() {
        return null;
    }

    /** 客户端刻号；同一刻内重复读取得到相同的值，用来判断手里的上下文是不是已经过期。 */
    long clientTick();

    /** 这份上下文是否仍属于本刻；过期的上下文不能再用来操作角色。 */
    boolean isCurrent();

    /**
     * 本刻是否还能向游戏提交一次交互：上下文属于本刻、角色仍归自动化控制（人按 F8 接回后一律不行）、
     * 本刻还没出过手。每刻只准提交一次，所以所有任务都是"每刻做一点"。只读，不占用机会。
     */
    boolean canInteractThisTick();

    /**
     * 占用本刻唯一的一次交互机会；条件同 {@link #canInteractThisTick}，不满足时返回假且不占用。
     * 世界动作、容器界面点击与模组协议共用这一份机会，由交互提交与界面操作在真正发包前调用。
     */
    boolean tryClaimInteraction();

    /**
     * 本刻的角色是否已经死了或在死亡流程里（血量见底、死亡界面）。
     * 控制循环每刻推进前先看这里：死了就不再插生存需求的临时任务、不推进任务，等重生。
     */
    default boolean isDeadOrDying() {
        LocalPlayer player = localPlayer();
        return player != null && player.isDeadOrDying();
    }
}

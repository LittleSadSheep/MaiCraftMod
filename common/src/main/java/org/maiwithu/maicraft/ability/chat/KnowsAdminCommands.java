// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

/**
 * 服务器的命令树说不说某条命令要管理员权限：单人或局域网主机时问内置服务器的命令树，
 * 普通玩家（零级权限）用不了的根命令就算管理员命令。接缝接在游戏接口层；拿不到命令树
 * （连远程服务器、命令树正在重载）就答"不是"，由名单兜底，不因此拦下普通玩家命令。
 */
@FunctionalInterface
public interface KnowsAdminCommands {
    /** 没有命令树可问：一律答不是，只按名单认。 */
    KnowsAdminCommands NONE = commandName -> false;

    /** 这条根命令（不带 /，小写）普通玩家用不了吗。 */
    boolean adminOnly(String commandName);
}

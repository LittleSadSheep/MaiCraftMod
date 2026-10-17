// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * 内置服务器的命令树读端：单人或局域网主机时，一条根命令普通玩家（零级权限）用不用得了。
 * 连远程服务器拿不到命令树，答"不知道"（false）；命令树正在重载等读不出的时候也一样，
 * 不因此把普通玩家命令拦下。只读，不执行命令、不改权限。
 */
public final class ClientCommandTree {
    /** 这条根命令（不带 /，小写）普通玩家用不了：只有内置服务器在、命令树里有它、零级权限用不了才算。 */
    public boolean adminOnly(String commandName) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            MinecraftServer server = minecraft == null ? null : minecraft.getSingleplayerServer();
            if (server == null) return false;
            var node = server.getCommands().getDispatcher().getRoot().getChild(commandName);
            return node != null && !node.canUse(server.createCommandSourceStack().withPermission(0));
        } catch (RuntimeException unavailable) {
            // 命令树正在重载、服务器正在关：读不出就不算管理员命令，交给名单兜底。
            return false;
        }
    }
}

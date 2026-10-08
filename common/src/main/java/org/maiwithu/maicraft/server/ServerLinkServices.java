// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * 按服务器实例登记服务端服务的登记表。Mixin 注入的钩子没有任何构造路径能把服务传进来，
 * 只能到这里按当前服务器取；启动在服务器的第一个刻结束时按需创建并登记，停服时移除，
 * 因此每个服务器实例（包括单人游戏的每次开局）都有自己的归属记录，不跨服残留。
 */
public final class ServerLinkServices {
    private record Services(BlockOwnershipRecord ownership, ServerConfirmations confirmations) {}

    private static final Map<MinecraftServer, Services> ATTACHED =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private ServerLinkServices() {}

    /** 尚未登记时用给定的工厂创建并登记；同一服务器重复调用不重建。 */
    public static void attachIfAbsent(MinecraftServer server,
                                      Supplier<BlockOwnershipRecord> ownership,
                                      Supplier<ServerConfirmations> confirmations) {
        ATTACHED.computeIfAbsent(server, ignored -> new Services(ownership.get(), confirmations.get()));
    }

    public static void detach(MinecraftServer server) {
        ATTACHED.remove(server);
    }

    /** 该服务器的方块归属记录；尚未登记（第一个刻之前）返回 null，钩子直接放过。 */
    public static BlockOwnershipRecord ownership(MinecraftServer server) {
        var services = ATTACHED.get(server);
        return services == null ? null : services.ownership();
    }

    /** 该服务器的交互确认通道；尚未登记时返回 null。 */
    public static ServerConfirmations confirmations(MinecraftServer server) {
        var services = ATTACHED.get(server);
        return services == null ? null : services.confirmations();
    }
}

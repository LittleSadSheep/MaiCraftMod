// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ServerLifecycle;
import org.maiwithu.maicraft.game.ModIdentity;

/**
 * NeoForge 的通用入口，独立服务器和客户端都会加载。
 * 只把服务端事件转给公共代码，不写业务，也不引用任何仅客户端的类。
 */
@Mod(ModIdentity.MOD_ID)
public final class MaiCraftNeoForge {
    private final ServerLifecycle server;

    public MaiCraftNeoForge(IEventBus modBus) {
        server = Bootstrap.startCommon(new NeoForgeLoaderEnvironment());
        // 服务端每刻结束推进归属记账与模组数据观察；停服后结束会话。
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        server.tickEnd(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        server.stopped(event.getServer());
    }
}

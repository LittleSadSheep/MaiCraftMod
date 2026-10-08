// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ClientLifecycle;
import org.maiwithu.maicraft.game.ModIdentity;

/**
 * NeoForge 的客户端入口：dist=CLIENT 限定它只在客户端加载，只把客户端事件转给公共代码，不写业务。
 * 新增客户端事件时，先在 {@link ClientLifecycle} 加方法，再在这里和 Fabric 入口各接一行。
 */
@Mod(value = ModIdentity.MOD_ID, dist = Dist.CLIENT)
public final class MaiCraftNeoForgeClient {
    private final ClientLifecycle client;

    public MaiCraftNeoForgeClient(IEventBus modBus) {
        client = Bootstrap.startClient(new NeoForgeLoaderEnvironment());
        // 客户端准备完成后创建内核与能力 -> 每刻结束推进角色当前的任务 -> 退出前收尾。
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
    }

    // 创建工作排到客户端主线程的工作队列，保证访问游戏对象时在正确的线程上。
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(client::started);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        client.tickEnd();
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        client.stopping();
    }
}

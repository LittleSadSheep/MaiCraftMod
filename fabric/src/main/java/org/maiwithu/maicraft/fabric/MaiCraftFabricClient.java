// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ClientLifecycle;

/**
 * Fabric 的客户端入口：只把客户端事件转给公共运行时，不写业务。
 * 新增客户端事件时，先在 {@link ClientLifecycle} 加方法，再在这里和 NeoForge 入口各接一行。
 */
public final class MaiCraftFabricClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientLifecycle client = Bootstrap.startClient(new FabricLoaderEnvironment());
        // 客户端启动完成后装配运行时 -> 每刻结束推进心跳 -> 退出前收尾。
        ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> client.started());
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> client.tickEnd());
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> client.stopping());
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ServerLifecycle;

/**
 * Fabric 的通用入口，独立服务器和客户端都会加载。
 * 只把服务端事件转给公共运行时，不写业务，也不引用任何仅客户端的类。
 */
public final class MaiCraftFabric implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycle server = Bootstrap.startCommon(new FabricLoaderEnvironment());
        // 服务端每刻结束推进归属记账与模组数据观察；停服后结束会话。
        ServerTickEvents.END_SERVER_TICK.register(server::tickEnd);
        ServerLifecycleEvents.SERVER_STOPPED.register(server::stopped);
    }
}

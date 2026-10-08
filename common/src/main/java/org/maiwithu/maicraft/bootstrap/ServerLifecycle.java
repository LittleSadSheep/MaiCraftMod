// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.network.MaiCraftPayload;

/**
 * 加载器把服务端事件转交给公共代码的接收端。服务端只提供确认信息，不替角色动手：
 * 记录方块是谁放的、确认交互结果、读取模组数据。
 * 独立服务器和单人游戏的内置服务器都会走这里，因此不能引用任何仅客户端的类。
 */
public interface ServerLifecycle {

    /** 每个服务端刻结束时调用：推进方块归属记录与模组数据读取。 */
    void tickEnd(MinecraftServer server);

    /** 服务器停止后调用：结束与客户端的会话并清理服务端状态。 */
    void stopped(MinecraftServer server);

    /** 客户端 MaiCraft 发来一个信封；应答通过 reply 发回同一个客户端。 */
    void clientEnvelope(ServerPlayer player, MaiCraftPayload payload, Consumer<JsonObject> reply);

    /** 玩家断开连接：结束这个玩家的会话。 */
    void clientDisconnected(ServerPlayer player);

    /** 玩家切换维度或重生：旧维度上的会话作用域作废。 */
    void clientWorldChanged(ServerPlayer player);

    /** 玩家破坏了方块：给该玩家的客户端推送一次破坏确认。 */
    void playerBrokeBlock(ServerPlayer player, BlockPos position, BlockState state);
}

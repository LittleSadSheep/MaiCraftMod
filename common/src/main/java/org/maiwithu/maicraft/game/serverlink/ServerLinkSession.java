// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import com.google.gson.JsonObject;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.network.ProtocolJson;

/**
 * 和服务端 MaiCraft 通信的客户端一侧运行时：绑定当前连接、推进握手与请求、接收服务端确认。
 * 由启动的客户端部分创建；加载器把入服、网络信封与断线事件转交给这里，自己不写业务。
 * 当前连接完成服务端确认之前不开放游戏能力；换服务器后旧确认不算数，必须重新握手。
 */
public final class ServerLinkSession {
    private final RequestRouter router;
    private final ReceivedConfirmations confirmations = new ReceivedConfirmations();
    private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private long tick;
    private long connectionRevision = -1;
    private long bindingRevision = -1;
    private ClientPacketListener connection;
    private String boundDimension;

    public ServerLinkSession(LinkTransport transport) {
        this.router = new RequestRouter(transport::available,
                envelope -> { transport.send(envelope); return true; },
                () -> requireClientThread(Minecraft.getInstance()), this::enqueue, (request, send) -> true);
    }

    /** 客户端要登记的操作清单必须在入服前完成；启动时在这里登记。 */
    public RequestRouter router() { return router; }

    /** 收到的服务端确认，供许可与保护、交互确认读取。 */
    public ReceivedConfirmations confirmations() { return confirmations; }

    /** 当前连接完成服务端握手后才算数；旧连接的确认不能带到另一台服务器。 */
    public boolean serverConfirmed() { return router.serverConfirmed(); }

    /** 服务端迟迟没有完成握手；为真时客户端应当断开并提示服主安装 MaiCraft。 */
    public boolean confirmationExpired() { return router.serverConfirmationExpired(); }

    /** 和服务端 MaiCraft 握手到哪一步、没握手好的原因；只读，给调试面板说清"服务端那边怎么了"。 */
    public ServerCapabilityState capabilities() { return router.session().capabilities; }

    /** 每个客户端刻结束调用：跟随当前连接与世界绑定会话，推进握手、分发与核对。 */
    public void tick(Minecraft minecraft) {
        requireClientThread(minecraft);
        tick++;
        var current = minecraft.getConnection();
        if (minecraft.player == null || minecraft.level == null || current == null
                || !current.getConnection().isConnected()) {
            connection = null;
            connectionRevision = -1;
            bindingRevision = -1;
            router.disconnect();
        } else {
            // 换了连接就换一个会话身份，旧服务器的确认作废；同一连接换维度只更新绑定，握手沿用。
            // 重绑只发生在连接或维度真正变化时——每刻重绑会重置握手随机数，welcome 永远对不上。
            var dimension = minecraft.level.dimension().location().toString();
            if (connection != current || !dimension.equals(boundDimension)) {
                // 只有真换了连接才换会话身份；同一连接换维度只换绑定，已经确认的握手照旧有效。
                if (connection != current) {
                    connection = current;
                    connectionRevision++;
                }
                boundDimension = dimension;
                bindingRevision++;
                router.bind(connectionRevision, bindingRevision, dimension, 0, true, tick);
            }
        }
        for (int i = 0; i < 256; i++) {
            Runnable callback = callbacks.poll();
            if (callback == null) break;
            callback.run();
        }
        router.observe(tick);
        // 通道声明可能晚于入服事件，因此等待真实欢迎包；迟迟未确认就断开，普通玩家客户端不受影响。
        if (connection != null && router.serverConfirmationExpired()) {
            connection.getConnection().disconnect(Component.literal(
                    "未能确认服务器的 MaiCraft 支持，已断开连接。\n请让服主安装兼容的 MaiCraft；普通玩家客户端无需安装。"));
        }
        router.dispatch(true);
    }

    /** 加载器在收到服务端信封时调用；解析后转交客户端线程处理。 */
    public void received(Minecraft minecraft, String json) {
        requireClientThread(minecraft);
        JsonObject envelope;
        try { envelope = ProtocolJson.decode(json); }
        catch (RuntimeException malformed) { return; }
        enqueue(() -> {
            if (ServerCapabilityState.text(envelope, "kind").equals("confirmation")) {
                confirmations.receive(envelope);
                return;
            }
            router.receive(envelope, connectionRevision);
        });
    }

    /** 加载器在玩家离开世界时调用：清掉这次服务器已确认的证据，下一台服务器重新握手。 */
    public void disconnected(Minecraft minecraft) {
        requireClientThread(minecraft);
        router.disconnect();
        connection = null;
        connectionRevision = -1;
        bindingRevision = -1;
    }

    private void enqueue(Runnable callback) { callbacks.add(callback); }

    private static void requireClientThread(Minecraft minecraft) {
        if (minecraft == null || !minecraft.isSameThread())
            throw new IllegalStateException("server link is client-thread only");
    }
}

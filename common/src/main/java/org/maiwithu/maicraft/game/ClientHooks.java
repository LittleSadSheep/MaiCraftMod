// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.concurrent.atomic.AtomicReference;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.player.UseKeyHold;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.menu.RenderedScreens;

/**
 * Mixin 进入游戏接口层的静态登记点。
 *
 * <p>Mixin 由字节码织入，无法用构造函数拿到服务，所以启动时把当刻创建的实例登记到这里；
 * 没登记时所有钩子安静地不做事。只有这里的几个字段是例外，其余服务照旧从构造函数传入。
 */
public final class ClientHooks {
    // 静态 final 持有可变引用：登记点只能启动时写一次语义，但保留停止（置 null）的能力。
    private static final AtomicReference<PlayerControlBoundary> PLAYER_CONTROL = new AtomicReference<>();
    private static final AtomicReference<BlockScanService> BLOCK_SCANS = new AtomicReference<>();
    private static final AtomicReference<UseKeyHold> USE_KEY_HOLD = new AtomicReference<>();
    private static final AtomicReference<SubtitleFeed> SUBTITLE_FEED = new AtomicReference<>();
    private static final AtomicReference<ChatLog> CHAT_LOG = new AtomicReference<>();
    private static final AtomicReference<RenderedScreens> RENDERED_SCREENS = new AtomicReference<>();

    private ClientHooks() {}

    /** 启动时登记玩家控制权边界；传 null 表示停止（例如客户端退出）。 */
    public static void registerPlayerControl(PlayerControlBoundary boundary) {
        PLAYER_CONTROL.set(boundary);
    }

    /** 启动时登记方块扫描服务；传 null 表示停止。 */
    public static void registerBlockScans(BlockScanService service) {
        BLOCK_SCANS.set(service);
    }

    /** 启动时登记按住使用键的投影；传 null 表示停止。 */
    public static void registerUseKeyHold(UseKeyHold useKeyHold) {
        USE_KEY_HOLD.set(useKeyHold);
    }

    /** 启动时登记字幕事件接收端；进世界与退世界的挂接由 Mixin 按 world 变化推进。 */
    public static void registerSubtitleFeed(SubtitleFeed feed) {
        SUBTITLE_FEED.set(feed);
    }

    /** 启动时登记本地聊天栏的记录端；聊天栏每加一条消息由 Mixin 转过来。 */
    public static void registerChatLog(ChatLog log) {
        CHAT_LOG.set(log);
    }

    /** 启动时登记界面真正画出来的记录；界面渲染的 Mixin 往里记帧。传 null 表示停止。 */
    public static void registerRenderedScreens(RenderedScreens screens) {
        RENDERED_SCREENS.set(screens);
    }

    /** Mixin 类跨包读取：界面这一帧画完往哪里记；没登记时为 null。 */
    public static RenderedScreens renderedScreens() {
        return RENDERED_SCREENS.get();
    }

    /** Mixin 类跨包读取；这里是与 Mixin 之间唯一允许的静态通道。 */
    public static PlayerControlBoundary playerControl() {
        return PLAYER_CONTROL.get();
    }

    public static BlockScanService blockScans() {
        return BLOCK_SCANS.get();
    }

    public static UseKeyHold useKeyHold() {
        return USE_KEY_HOLD.get();
    }

    public static SubtitleFeed subtitleFeed() {
        return SUBTITLE_FEED.get();
    }

    public static ChatLog chatLog() {
        return CHAT_LOG.get();
    }
}

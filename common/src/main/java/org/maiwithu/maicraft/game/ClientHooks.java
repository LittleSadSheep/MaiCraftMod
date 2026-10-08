// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.concurrent.atomic.AtomicReference;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.world.BlockScanService;

/**
 * Mixin 进入游戏接口层的静态登记点。
 *
 * <p>Mixin 由字节码织入，无法用构造函数拿到服务，所以启动时把当刻创建的实例登记到这里；
 * 没登记时所有钩子安静地不做事。只有这里的两个字段是例外，其余服务照旧从构造函数传入。
 */
public final class ClientHooks {
    // 静态 final 持有可变引用：登记点只能启动时写一次语义，但保留停止（置 null）的能力。
    private static final AtomicReference<PlayerControlBoundary> PLAYER_CONTROL = new AtomicReference<>();
    private static final AtomicReference<BlockScanService> BLOCK_SCANS = new AtomicReference<>();

    private ClientHooks() {}

    /** 启动时登记玩家控制权边界；传 null 表示停止（例如客户端退出）。 */
    public static void registerPlayerControl(PlayerControlBoundary boundary) {
        PLAYER_CONTROL.set(boundary);
    }

    /** 启动时登记方块扫描服务；传 null 表示停止。 */
    public static void registerBlockScans(BlockScanService service) {
        BLOCK_SCANS.set(service);
    }

    /** Mixin 类跨包读取；这里是与 Mixin 之间唯一允许的静态通道。 */
    public static PlayerControlBoundary playerControl() {
        return PLAYER_CONTROL.get();
    }

    public static BlockScanService blockScans() {
        return BLOCK_SCANS.get();
    }
}

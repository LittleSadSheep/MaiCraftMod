// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.world.BlockScanService;

/**
 * Mixin 进入游戏接口层的静态登记点。
 *
 * <p>Mixin 由字节码织入，无法用构造函数拿到服务，所以启动时把当刻创建的实例登记到这里；
 * 没登记时所有钩子安静地不做事。只有这里的两个字段是例外，其余服务照旧从构造函数传入。
 */
public final class ClientHooks {
    private static volatile PlayerControlBoundary playerControl;
    private static volatile BlockScanService blockScans;

    private ClientHooks() {}

    /** 启动时登记玩家控制权边界；传 null 表示停止（例如客户端退出）。 */
    public static void registerPlayerControl(PlayerControlBoundary boundary) {
        playerControl = boundary;
    }

    /** 启动时登记方块扫描服务；传 null 表示停止。 */
    public static void registerBlockScans(BlockScanService service) {
        blockScans = service;
    }

    static PlayerControlBoundary playerControl() {
        return playerControl;
    }

    static BlockScanService blockScans() {
        return blockScans;
    }
}

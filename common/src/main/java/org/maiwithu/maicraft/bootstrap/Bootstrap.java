// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.MinecraftServer;

/**
 * 公共启动入口。两个加载器的入口类只调用这里，再把加载器事件转给返回的接收端。
 *
 * <p>启动顺序：通用部分（独立服务器与客户端都会执行）→ 客户端部分（只在客户端执行）。
 * 能力与联动模组在这里按一份明确的清单创建和登记，不做类路径扫描，新增时在清单里加一行。
 *
 * <p>目前只接好了加载器事件：每刻推进任务、创建服务、登记能力还没有接入。
 */
public final class Bootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    private Bootstrap() {}

    /** 通用部分：在两端都执行；只创建服务端一侧的东西，不引用任何仅客户端的类。 */
    public static ServerLifecycle startCommon(LoaderEnvironment loader) {
        LOG.info("{} 通用部分启动（加载器：{}）", ModIdentity.NAME, loader.loaderName());
        return new ServerLifecycle() {
            @Override public void tickEnd(MinecraftServer server) {
                // 服务端一侧（记录方块是谁放的、确认交互结果）还没有接入，暂时没有要推进的事。
            }

            @Override public void stopped(MinecraftServer server) {
                LOG.info("{} 服务端已停止", ModIdentity.NAME);
            }
        };
    }

    /** 客户端部分：只在客户端执行，创建内核、能力与 MCP 入口。 */
    public static ClientLifecycle startClient(LoaderEnvironment loader) {
        LOG.info("{} 客户端部分启动（加载器：{}，开发环境：{}）",
                ModIdentity.NAME, loader.loaderName(), loader.isDevelopment());
        return new ClientLifecycle() {
            @Override public void started() {
                LOG.info("{} 客户端启动完成；内核与能力还没有接入", ModIdentity.NAME);
            }

            @Override public void tickEnd() {
                // 内核还没有接入，暂时没有任务要推进。
            }

            @Override public void stopping() {
                LOG.info("{} 客户端即将退出", ModIdentity.NAME);
            }
        };
    }
}

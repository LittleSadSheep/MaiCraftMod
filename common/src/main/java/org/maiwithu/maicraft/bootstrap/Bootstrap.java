// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import org.maiwithu.maicraft.platform.ModIdentity;
import org.maiwithu.maicraft.platform.loader.LoaderEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.MinecraftServer;

/**
 * 公共启动入口。两个加载器的入口类只调用这里，再把加载器事件转给返回的接收端。
 *
 * <p>启动顺序：通用部分（独立服务器与客户端都会执行）→ 客户端部分（只在客户端执行）。
 * 能力模块与联动模块在这里用显式清单装配，不做类路径扫描，新增模块时在清单里加一行。
 *
 * <p>当前是骨架阶段：只建立接线，内核心跳、服务注入与能力清单按 docs/design/04 的工作包接入。
 */
public final class Bootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

    private Bootstrap() {}

    /** 通用部分：在两端都执行；只装配服务端职责，不引用任何仅客户端的类。 */
    public static ServerLifecycle startCommon(LoaderEnvironment loader) {
        LOG.info("{} 通用部分启动（加载器：{}）", ModIdentity.NAME, loader.loaderName());
        return new ServerLifecycle() {
            @Override public void tickEnd(MinecraftServer server) {
                // 骨架阶段没有服务端职责可推进；归属记账与动作旁证由 WP-1.8 接入。
            }

            @Override public void stopped(MinecraftServer server) {
                LOG.info("{} 服务端已停止", ModIdentity.NAME);
            }
        };
    }

    /** 客户端部分：只在客户端执行，装配角色运行时与 MCP 入口。 */
    public static ClientLifecycle startClient(LoaderEnvironment loader) {
        LOG.info("{} 客户端部分启动（加载器：{}，开发环境：{}）",
                ModIdentity.NAME, loader.loaderName(), loader.isDevelopment());
        return new ClientLifecycle() {
            @Override public void started() {
                LOG.info("{} 客户端启动完成；骨架阶段尚未装配内核与能力", ModIdentity.NAME);
            }

            @Override public void tickEnd() {
                // 骨架阶段没有身体可驱动；内核心跳由 WP-2.1 与 WP-2.2 接入。
            }

            @Override public void stopping() {
                LOG.info("{} 客户端即将退出", ModIdentity.NAME);
            }
        };
    }
}

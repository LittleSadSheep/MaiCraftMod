// SPDX-License-Identifier: GPL-3.0-only
/**
 * 启动装配（L5）。
 *
 * <p>两个加载器的入口类只调用 {@link org.maiwithu.maicraft.bootstrap.Bootstrap}，再把加载器事件转给返回的接收端。
 * 能力模块与联动模块在这里用显式清单装配，不做类路径扫描；服务从构造函数注入，不提供全局单例。
 *
 * <p>可以依赖：全部层。不可以被依赖：任何层都不得引用本包。
 */
package org.maiwithu.maicraft.bootstrap;

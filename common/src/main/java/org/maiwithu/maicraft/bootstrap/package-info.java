// SPDX-License-Identifier: GPL-3.0-only
/**
 * 启动（L5）：创建服务，登记能力与联动模组，把加载器事件接到公共代码。
 *
 * <p>两个加载器的入口类只调用 {@link Bootstrap}，再把加载器事件转给返回的接收端。
 * 能力与联动模组在这里按一份明确的清单登记，不做类路径扫描；服务从构造函数传入，不提供全局单例。
 *
 * <p>可以依赖：全部层。不可以被依赖：任何层都不得引用本包。
 */
package org.maiwithu.maicraft.bootstrap;

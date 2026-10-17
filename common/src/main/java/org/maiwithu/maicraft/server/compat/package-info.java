// SPDX-License-Identifier: GPL-3.0-only
/**
 * 服务端的联动：客户端读不到、只能在服务端读的模组数据（ME 网络摘要、机器的权威读数）经这里登记成只读操作。
 *
 * <p>包根是公共部分：服务端联动入口 ServerCompatModule、服务端联动登记表 ServerCompatRegistry。
 * 每个模组一个子包 server.compat.&lt;模组&gt;，放这个模组在服务端要读什么的接缝与入口，子包之间互不依赖；
 * 直接调用模组类的读写端放在 NeoForge 模块的 neoforge.compat.&lt;模组&gt;。
 */
package org.maiwithu.maicraft.server.compat;

// SPDX-License-Identifier: GPL-3.0-only
/**
 * 精妙背包（SophisticatedBackpacks）的联动。现在只认出角色身上哪几格放的是精妙背包；
 * 从背包里拿东西的来源、腾地方往背包里塞的随身背包接缝都还没有接。
 *
 * <p>读写接缝 BackpackItems 只用 Java 类型描述要读什么；直接调用模组类的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.compat.backpack;

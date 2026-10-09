// SPDX-License-Identifier: GPL-3.0-only
/**
 * 应用能源2（AE2）的联动：角色从附近的 ME 终端取网络里已有的东西。
 * 现在接好了联动入口与两个读写接缝（找终端 Ae2Terminals、终端界面 Ae2TerminalMenu），物品来源还没有交给登记表。
 *
 * <p>读写接缝只用 Minecraft 与 Java 的类型描述要读什么、点什么；直接调用 AE2 类的读写端在 NeoForge 模块里。
 * AE 网络本身（控制器、频道、能量、合成 CPU）的读数与部件、线缆的施工不在这里，归机器。
 */
package org.maiwithu.maicraft.compat.ae2;

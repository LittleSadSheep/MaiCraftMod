// SPDX-License-Identifier: GPL-3.0-only
/**
 * 应用能源2（AE2）的联动：角色从附近的 ME 终端取网络里已有的东西。
 * 联动入口 Ae2Compat 把 ME 终端来源 Ae2TerminalSource（途径 ae2）与终端界面的布局证明交给登记表；
 * 找终端经读写接缝 Ae2Terminals，终端界面经 Ae2TerminalMenu。挑哪台终端、怎么取、取没取到、上次看到的网络存货都是纯函数判断。
 *
 * <p>读写接缝只用 Minecraft 与 Java 的类型描述要读什么、点什么；直接调用 AE2 类的读写端在 NeoForge 模块里。
 * AE 网络本身（控制器、频道、能量、合成 CPU）的读数与部件、线缆的施工不在这里，归机器。
 */
package org.maiwithu.maicraft.compat.ae2;

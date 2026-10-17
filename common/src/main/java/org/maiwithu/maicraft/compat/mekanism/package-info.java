// SPDX-License-Identifier: GPL-3.0-only
/**
 * 通用机械（Mekanism）的联动：认得出机器与管道、读侧面配置与管道连接、用配置器改侧面、用线缆管道把机器接进管网。
 * 联动入口 MekanismCompat 把目录 MekanismMachineCatalog 里的每种、兜底的"其他 Mekanism 机器"、四种管网的网络读取器交给登记表。
 *
 * <p>读写接缝 MekanismReads 只用 Minecraft 与 Java 的类型；直接调用 Mekanism 类的读写端在 NeoForge 模块里。
 * 侧面配置的键与值、管道连成哪一片都是纯函数判断。化学品只读不接。
 */
package org.maiwithu.maicraft.compat.mekanism;

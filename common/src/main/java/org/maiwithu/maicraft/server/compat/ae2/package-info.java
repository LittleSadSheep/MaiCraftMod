// SPDX-License-Identifier: GPL-3.0-only
/**
 * 应用能源2（AE2）在服务端的联动：只读操作 ae2.network 回答某一格所在 ME 网络的汇总
 * （控制器、频道、能量、存储总量、合成 CPU）。读写接缝 Ae2GridReads 只用 Minecraft 与 Java 的类型，
 * 直接调网格服务的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.server.compat.ae2;

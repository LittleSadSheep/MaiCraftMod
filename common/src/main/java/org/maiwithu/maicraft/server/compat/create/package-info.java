// SPDX-License-Identifier: GPL-3.0-only
/**
 * 机械动力（Create）在服务端的联动：只读操作 create.trains 回答各趟列车时刻表上的站序。
 * 读写接缝 CreateTrainReads 只用 Minecraft 与 Java 的类型，直接调 Create 铁路管理器的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.server.compat.create;

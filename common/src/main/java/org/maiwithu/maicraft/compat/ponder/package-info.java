// SPDX-License-Identifier: GPL-3.0-only
/**
 * 思索（Ponder）的联动：Create 及其附属模组按 W 看到的演示，当作 LLM 能查的资料。
 *
 * <p>一个思索场景读成一篇：旁白与操作提示按出现顺序、在关键帧处分段，另附演示结构（作者的结构文件全部格）
 * 与每个关键帧相对上一帧改了哪些格。场景要在一个独立的演示世界里从头放到尾才知道这些，放的时候不碰玩家的世界。
 *
 * <p>读写接缝 PonderReads 只用 Minecraft 与 Java 的类型描述要读什么；直接调用 Ponder 类的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.compat.ponder;

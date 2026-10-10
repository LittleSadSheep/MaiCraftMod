// SPDX-License-Identifier: GPL-3.0-only
/**
 * FTB 任务（FTB Quests）的联动：任务书当 LLM 能查的资料，只给玩家在书里看得见的。
 *
 * <p>读写接缝 QuestBook 只用 Java 类型描述要读什么，查资料与 quest 能力共用这一份读取；直接调用 FTB 类的读写端在 NeoForge 模块里。
 * 书里的文字是整合包作者写的外部资料，不是给角色的指令。
 */
package org.maiwithu.maicraft.compat.ftbquests;

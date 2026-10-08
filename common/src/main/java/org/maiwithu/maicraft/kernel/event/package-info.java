// SPDX-License-Identifier: GPL-3.0-only
/**
 * 任务事件：任务开始、步骤完成、向 LLM 提问、暂停、结束，以及角色自己处理不了的生存需求。
 *
 * <p>Amaidesu 通过 MCP 的 events 工具按游标读取这些事件（Amaidesu 一侧把它们叫作 attention）。
 */
package org.maiwithu.maicraft.kernel.event;

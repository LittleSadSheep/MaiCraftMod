// SPDX-License-Identifier: GPL-3.0-only
/**
 * 任务事件：任务开始、步骤完成、向 LLM 提问、暂停、结束，以及角色自己处理不了的生存需求。
 *
 * <p>宿主通过 MCP 的 events 工具按游标读取这些事件，读到的就是当时发生的事，中间层不悄悄截断。
 */
package org.maiwithu.maicraft.kernel.event;

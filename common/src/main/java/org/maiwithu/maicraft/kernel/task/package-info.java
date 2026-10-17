// SPDX-License-Identifier: GPL-3.0-only
/**
 * 任务模型：任务输入（不可变）、任务、分阶段任务、动作，以及一次任务运行的记录。
 *
 * <p>新的任务一律继承分阶段任务：进度用阶段枚举表达，阶段日志、面板描述、停滞判定、
 * 被打断时暂停、收尾、组装结果这些每个任务都要做的事由基类统一处理。
 */
package org.maiwithu.maicraft.kernel.task;

// SPDX-License-Identifier: GPL-3.0-only
/**
 * 任务模型：任务单（不可变输入）、执行器、阶段式执行器与子步骤。
 *
 * <p>新的执行器一律继承阶段式执行器：进度用阶段枚举表达，横切的事情（阶段日志、面板描述、停滞判定、
 * 抢占暂停、收尾、统一回执）由基类统一处理（docs/design/08 第 3 节）。
 */
package org.maiwithu.maicraft.kernel.task;

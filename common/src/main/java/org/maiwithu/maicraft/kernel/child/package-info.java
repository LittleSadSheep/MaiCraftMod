// SPDX-License-Identifier: GPL-3.0-only
/**
 * 子任务监护：子任务的创建、启动、逐刻推进、截止检查、异常捕获、停止转发、收结果、松手。
 *
 * <p>全仓只在这里写一次；父任务和组合型执行器都通过它驱动子任务。
 */
package org.maiwithu.maicraft.kernel.child;

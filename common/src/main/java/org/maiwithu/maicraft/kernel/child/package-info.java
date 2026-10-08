// SPDX-License-Identifier: GPL-3.0-only
/**
 * 子任务运行：一个步骤或一个组合任务要用到另一个任务时，统一在这里创建、启动、逐刻推进、检查时限、
 * 把异常转成 INTERNAL_ERROR 问题、转发停止、收结果、交还控制。
 *
 * <p>全仓只在这里写一次；目标推进和组合型任务都通过它运行子任务。
 */
package org.maiwithu.maicraft.kernel.child;

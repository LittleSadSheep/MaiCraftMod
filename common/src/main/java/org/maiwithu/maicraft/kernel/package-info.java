// SPDX-License-Identifier: GPL-3.0-only
/**
 * 内核（L1）：调度、任务模型、子任务监护、进度、目标推进、能力注册框架、事件与持久化框架。
 *
 * <p>内核不认识任何具体能力：不出现能力 ID 字面量，也不对具体任务单类型做 instanceof。
 * 能力的特殊需要通过任务单特征接口和能力钩子挂进来（docs/design/02 第 5.4 节）。
 *
 * <p>可以依赖：{@code platform}。不可以依赖：{@code behavior}、{@code ability}、{@code integration}、{@code gateway}。
 */
package org.maiwithu.maicraft.kernel;

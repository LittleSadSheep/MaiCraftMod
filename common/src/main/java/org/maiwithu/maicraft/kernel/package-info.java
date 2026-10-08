// SPDX-License-Identifier: GPL-3.0-only
/**
 * 内核（L1）：任务模型、打断规则、子任务运行、进度跟踪、目标推进、能力框架、任务事件与存储。
 *
 * <p>内核不认识任何具体能力：不出现能力 ID 字面量，也不对具体的任务输入类型做 instanceof。
 * 能力的特殊需要通过任务输入上的特征接口和能力钩子挂进来。
 *
 * <p>可以依赖：{@code game}。不可以依赖：{@code behavior}、{@code ability}、{@code compat}、{@code mcp}。
 */
package org.maiwithu.maicraft.kernel;

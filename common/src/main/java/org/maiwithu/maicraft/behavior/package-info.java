// SPDX-License-Identifier: GPL-3.0-only
/**
 * 玩家行为（L2）：一个正常玩家会怎么做，写一次，所有能力共用。
 *
 * <p>每个子包是一个行为模型。判断部分写成纯函数（读只读接口，给出结论），可以离线测试；
 * 动手的部分以动作（Action）的形式提供给能力的任务组合使用。
 *
 * <p>可以依赖：{@code kernel}、{@code game}。不可以依赖：{@code ability}、{@code compat}、{@code mcp}。
 */
package org.maiwithu.maicraft.behavior;

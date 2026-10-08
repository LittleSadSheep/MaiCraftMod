// SPDX-License-Identifier: GPL-3.0-only
/**
 * 玩家行为模型（L2）：一个讲道理的真人玩家会怎么做，写一次，所有能力共用。
 *
 * <p>每个子包对应 docs/design/03 的一个模型。决策部分写成纯函数（读只读接口，出结论），可以离线测试；
 * 执行部分以子步骤（Step）的形式提供给能力的执行器组合使用。
 *
 * <p>可以依赖：{@code kernel}、{@code platform}。不可以依赖：{@code ability}、{@code integration}、{@code gateway}。
 */
package org.maiwithu.maicraft.behavior;

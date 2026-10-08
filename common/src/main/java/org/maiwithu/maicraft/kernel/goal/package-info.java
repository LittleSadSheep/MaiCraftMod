// SPDX-License-Identifier: GPL-3.0-only
/**
 * 目标推进：目标、统一目标模型、翻译结果、逐步推进目标的 GoalRunner，以及内核扩展点。
 *
 * <p>每个步骤：已满足就直接完成 → 翻译出下一个动作 → 缺的前置在步骤内部补齐 → 执行 → 以真实世界为准核验
 * （docs/design/03 的 M1）。只在三种情况下向 LLM 提问（M8）。
 */
package org.maiwithu.maicraft.kernel.goal;

// SPDX-License-Identifier: GPL-3.0-only
/**
 * 目标推进：LLM 给的目标、目标对象、能力对每一步的决定，以及逐步推进目标的过程和能力钩子。
 *
 * <p>每一步：已经满足就直接完成 → 能力决定下一件做什么 → 缺的东西在这一步里自己补齐 → 做 → 以真实世界为准核对。
 * 只在三种情况下向 LLM 提问（见 {@link org.maiwithu.maicraft.kernel.goal.Question}）。
 */
package org.maiwithu.maicraft.kernel.goal;

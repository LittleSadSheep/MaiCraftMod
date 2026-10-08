// SPDX-License-Identifier: GPL-3.0-only
/**
 * 统一回执：结果（Outcome）、卡点（Blocker）、能力特有事实（Facts）。
 *
 * <p>所有能力用同一个回执结构：做成了什么、还剩什么、卡在哪及怎样解除、哪些操作已提交但未确认
 * （docs/design/03 的 M8）。动作成功与目标达成分开记录。
 */
package org.maiwithu.maicraft.kernel.outcome;

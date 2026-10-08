// SPDX-License-Identifier: GPL-3.0-only
/**
 * 等待能力：在游戏里等一个条件——等一段时间、等天黑或天亮、等生命回满、等不再饥饿。
 *
 * <p>等待是常驻观察：只看不动手，不替角色达成条件，也没有超时；要停下由 LLM 取消任务。
 * 时间按世界时刻计算，被生存需求打断的暂停同样承认已经过去的时间。
 */
package org.maiwithu.maicraft.ability.wait;

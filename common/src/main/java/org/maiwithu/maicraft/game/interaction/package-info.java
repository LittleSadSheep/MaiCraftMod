// SPDX-License-Identifier: GPL-3.0-only
/**
 * 原生交互：像真人玩家一样通过游戏的正常交互（右键、攻击、挖掘、换手持）做事，先提交，再逐刻确认结果。
 *
 * <p>"提交了不等于成功"：结果要连续几刻稳定才算确认；角色或手持在确认前变了，就记为"无法确认"，
 * 既不谎报成功，也不盲目重发。瞄准、换站位重试这类组合动作在 {@code behavior.interaction}。
 */
package org.maiwithu.maicraft.game.interaction;

// SPDX-License-Identifier: GPL-3.0-only
/**
 * 读世界：只读的世界查询、分几刻做完的扫描、交互距离与时间规则。
 *
 * <p>交互距离一律读玩家属性（方块交互距离与实体交互距离），床另按服务端的床距离判定，全仓只在这里实现一次。
 * 费时的扫描必须分到多刻做完，结果带"是否扫完"；没扫完永远不能当成"没有"。
 */
package org.maiwithu.maicraft.game.world;

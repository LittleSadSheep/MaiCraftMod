// SPDX-License-Identifier: GPL-3.0-only
/**
 * 世界读取：只读的世界查询接口、分帧扫描服务、伸手距离与时间规则。
 *
 * <p>伸手距离一律读玩家属性（方块与实体交互距离），床另按服务端的床距离盒判定，全仓只在这里实现一次。
 * 昂贵扫描必须分帧进行，结果带"是否扫完"；没扫完永远不能当成"没有"。
 */
package org.maiwithu.maicraft.platform.world;

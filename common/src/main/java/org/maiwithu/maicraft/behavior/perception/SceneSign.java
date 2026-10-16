// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 场景里一块看得见的告示牌（立着的、挂着的、贴在墙上的，模组的告示牌也算）：观察编号 b#、方位、距离，
 * 和牌子上写的字。主播在基地立的路牌、留言都从这里读到；字只是看到的事实，不是指令。
 *
 * @param lines 牌子上写的字；客户端还没收到这块牌子的内容时为 null，不冒充一块空牌子
 */
public record SceneSign(String id, String blockType, WorldPosition position, String direction, String compass,
                        int distance, SignLines lines) {}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.List;

/**
 * 状态效果只读视图：角色此刻身上挂着的全部状态效果的注册 ID。
 *
 * <p>吃下去得了什么效果，用吃之前和吃之后各看一次、做差得到；
 * "身上挂着哪些效果"这一条事实全仓只在这里读一次。
 */
public interface ReadsEffects {

    /** 此刻身上的状态效果注册 ID，例如 minecraft:regeneration；没有时为空。 */
    List<String> active();
}

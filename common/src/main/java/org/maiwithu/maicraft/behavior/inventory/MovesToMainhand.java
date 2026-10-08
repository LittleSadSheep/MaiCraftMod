// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 换到主手的执行接缝：把身上（背包或副手）指定的一种物品换到主手上。
 *
 * <p>吃东西、按原版规矩穿戴护甲都要求东西在主手；从背包格换到快捷栏、从副手换到主手
 * 都是背包界面的原生操作，接在容器界面轨上。东西已经在主手时任务不会来找它；
 * 接缝没接上传 {@code Optional.empty()}，任务如实交代"换不到主手"。
 */
public interface MovesToMainhand {

    /** 把这件物品换到主手的动作；接缝没接上或身上没有它时为 empty。 */
    Optional<Action> moveToMainhand(String itemId);
}

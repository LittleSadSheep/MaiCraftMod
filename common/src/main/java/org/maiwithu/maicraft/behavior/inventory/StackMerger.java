// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 合并接缝：把主背包里同一种物品的散堆并成整堆。合并不丢东西，只是把格子腾出来。
 *
 * <p>实现在背包界面里做原生点击搬运（读端 {@link ClientStackMerger}）；
 * 接缝没接上（传 {@code Optional.empty()}）时腾地方跳过这一步。
 */
public interface StackMerger {

    /**
     * 并一对散堆的动作：挑对、拿起、并进去、关上界面。并进去并确认了算做完，
     * 没有可合并的散堆、游戏不接受、等不到确认按问题失败；确认的变化与没能确认的交互记进 records。
     */
    Action mergeOne(TaskRecords records);
}

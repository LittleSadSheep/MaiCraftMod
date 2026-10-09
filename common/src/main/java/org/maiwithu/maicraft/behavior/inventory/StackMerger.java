// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;



/**
 * 合并接缝：把主背包里同一种物品的散堆并成整堆。合并不丢东西，只是把格子腾出来。
 *
 * <p>要在背包界面里点击搬运，容器界面轨之后才有实现；现在没有实现，腾地方跳过这一步。
 */
public interface StackMerger {

    /**
     * 做一步合并（一次点击搬运一堆）：合并完成并确认了是做成，点击还在等确认是还在做，
     * 没有可合并的散堆是做不了。
     */
    SpaceStepResult mergeOne();
}

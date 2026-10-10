// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.spi;

import java.util.Optional;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 随身背包接缝：便携模组给的随身容器（背包、潜影盒之类），腾地方时先把东西塞进去。
 *
 * <p>实现由联动模组提供，启动时按模组是否安装登记；还没有实现方时，腾地方跳过这一步。
 */
public interface CarriedBackpack {

    /** 随身背包还空着几格。 */
    int freeSlots();

    /**
     * 把主背包里的一堆放进随身背包的动作：放进去并确认了算做完，放不下、点了没回音按问题失败；
     * 确认放进去的（记成存进了随身背包）与没能确认的交互由动作记进 records。这种东西放不进随身背包时给空。
     */
    Optional<Action> store(BackpackStack stack, TaskRecords records);
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.spi;

import org.maiwithu.maicraft.behavior.inventory.SpaceStepResult;
import org.maiwithu.maicraft.game.player.BackpackStack;

/**
 * 随身背包接缝：便携模组给的随身容器（背包、潜影盒之类），腾地方时先把东西塞进去。
 *
 * <p>实现由联动模组提供，启动时按模组是否安装登记；还没有实现方时，腾地方跳过这一步。
 */
public interface CarriedBackpack {

    /** 随身背包还空着几格。 */
    int freeSlots();

    /** 把主背包里的一堆放进随身背包：放进去并确认了是做成，还在等确认是还在做，放不下是做不了。 */
    SpaceStepResult store(BackpackStack stack);
}

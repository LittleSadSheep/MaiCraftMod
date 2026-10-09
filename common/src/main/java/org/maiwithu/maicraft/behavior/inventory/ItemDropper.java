// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;



/**
 * 丢弃接缝：把主背包里的东西丢到地上。丢出去是真实的世界变化，确认了才算数。
 *
 * <p>容器界面与交互轨之后才有实现；现在没有实现，腾地方只走合并和随身背包。
 */
public interface ItemDropper {

    /** 丢掉多少件某种物品（通常是一整格）：丢出去并确认了是做成，还在等确认是还在做，东西已经不在了是做不了。 */
    SpaceStepResult drop(String itemId, int count);
}

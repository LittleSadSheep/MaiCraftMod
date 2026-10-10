// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;



import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 丢弃接缝：把主背包里的东西丢到地上。丢出去是真实的世界变化，确认了才算数。
 *
 * <p>实现把要丢的换到主手、原生投掷整份丢出、登记落点并走开防捡回（读端 {@link ClientItemDropper}）；
 * 接缝没接上（传 {@code Optional.empty()}）时腾地方不丢东西，腾不出的如实说。
 */
public interface ItemDropper {

    /** 丢掉多少件某种物品（通常是一整格）：丢出去并确认了是做成，还在等确认是还在做，东西已经不在了是做不了。 */
    SpaceStepResult drop(String itemId, int count, TickContext context);
}

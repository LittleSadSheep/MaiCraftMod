// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 身上一件物品自己的设置（Create 的过滤器、属性过滤器写规则）：认不认这件、现在是什么规则、怎么改。
 * 改的是物品自己的界面：拿到主手、右键打开、改、关上；关上界面后物品数据才落定，由调用方再读回核对。
 * machine_configure / machine_inspect 给 item 时用它，和 target 指的机器方块是两回事。
 */
public interface ItemSettings {

    /** 这件物品归不归这份设置管。 */
    boolean covers(ItemStack stack);

    /** 这件物品现在的设置项与值（从物品数据读）；值的写法和改的时候一样。 */
    List<MachineSetting> settings(ItemStack stack);

    /**
     * 这件的 key 这一项是不是已经是 wanted：默认就是读到的值和写的一样；写法和读法不一样的项
     * （属性过滤器按"样本物品 文字片段"写、按属性全文读）由实现自己比。
     */
    default boolean holds(ItemStack stack, String key, String wanted) {
        return settings(stack).stream().anyMatch(setting -> setting.key().equals(key) && setting.value().equals(wanted));
    }

    /**
     * 把背包第 inventorySlot 格（0~35）那件改成 wanted：一整段动作（拿到主手、打开它的界面、逐项改、关上）。
     * 有认不了的项或这件不归它管时给空。改完那件在主手。
     */
    Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions);
}

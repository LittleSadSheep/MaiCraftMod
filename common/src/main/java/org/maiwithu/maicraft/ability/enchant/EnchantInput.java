// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 附魔的任务输入：附魔哪件、选第几档、去哪张台子。
 *
 * @param item   附魔哪件：物品 ID 或 # 标签（身上第一件匹配且台子肯收的）
 * @param tier   三档报价里选第几档（1..3）：第 n 档消耗 n 级和 n 个青金石
 * @param radius 不给目标台子时找台子的范围（格）
 * @param target 点名用哪张台子（观察编号、坐标或记得的地点）；不给就在范围内找最近的
 * @param permissions 这次目标的许可：走去台子、腾背包、拿附魔料都按它，不悄悄换成默认
 */
record EnchantInput(String item, int tier, int radius, Target target, Permissions permissions) implements TaskInput {

    @Override public String describe() {
        return "给 " + item + " 附魔（第 " + tier + " 档）";
    }
}

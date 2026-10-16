// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.EnumSet;
import java.util.Set;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 寻死的任务输入。
 *
 * @param via           指定的死法；不指定（按档自己挑）为 null
 * @param radius        找现成死法的范围（格）
 * @param maxSeconds    最多花多少秒去死；死亡停摆、暂停、穿回护甲都不算在里面
 * @param permissions   这次目标的许可：走过去能动多少地形；随身点火、倒岩浆是寻死的手段，不看它的 change_blocks
 * @param keepInventory 怎么知道开着死亡不掉落的：game_rule 读到真实规则 / caller_confirmed 调用方确认
 */
record SuicideInput(WayToDie via, int radius, long maxSeconds, Permissions permissions, String keepInventory)
        implements TaskInput {

    SuicideInput {
        permissions = permissions == null ? Permissions.DEFAULT : permissions;
    }

    /** 这次能用的死法：指定了只有那一种，不指定六种都在；随身点火、倒岩浆不因 change_blocks=none 去掉。 */
    Set<WayToDie> allowedWays() {
        return via == null ? EnumSet.allOf(WayToDie.class) : EnumSet.of(via);
    }

    @Override public String describe() {
        return via == null ? "寻死" : "寻死（" + via.label + "）";
    }
}

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
 * @param permissions   这次目标的许可：走过去能动多少地形、能不能放火倒岩浆
 * @param keepInventory 怎么知道开着死亡不掉落的：game_rule 读到真实规则 / caller_confirmed 调用方确认
 */
record SuicideInput(WayToDie via, int radius, long maxSeconds, Permissions permissions, String keepInventory)
        implements TaskInput {

    SuicideInput {
        permissions = permissions == null ? Permissions.DEFAULT : permissions;
    }

    /** 这次能用的死法：指定了只有那一种；许可不让动方块时没有随身点火、倒岩浆。 */
    Set<WayToDie> allowedWays() {
        Set<WayToDie> ways = via == null ? EnumSet.allOf(WayToDie.class) : EnumSet.of(via);
        if (!placesBlocks()) ways.removeIf(WayToDie::carried);
        return ways;
    }

    /** 这次许可让不让放方块（火、岩浆）。 */
    boolean placesBlocks() {
        return permissions.changeBlocks() != Permissions.BlockChanges.NONE;
    }

    @Override public String describe() {
        return via == null ? "寻死" : "寻死（" + via.label + "）";
    }
}

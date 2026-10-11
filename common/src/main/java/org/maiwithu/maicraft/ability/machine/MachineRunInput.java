// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 用机器做东西的任务输入：用哪台机器、做什么、做几件、做完拿不拿、最多等多久；
 * 蓝图桌、蓝图加农炮另带蓝图文件、原点落在哪格、转几度。
 *
 * @param target     目标对象：一格机器方块的写法
 * @param item       要做出什么；不给就只开机
 * @param count      要做几件
 * @param collect    做完要不要把出口的东西拿进背包
 * @param maxSeconds 最多等多久（秒）
 * @param permissions 这次任务的许可
 * @param file       蓝图文件名（游戏目录 schematics 下）；不用蓝图为 null
 * @param anchor     蓝图原点落在哪一格；不给为 null
 * @param rotation   蓝图绕原点转几度：0 / 90 / 180 / 270
 */
record MachineRunInput(Target target, String item, int count, boolean collect, int maxSeconds,
                       Permissions permissions, String file, BlockPos anchor, int rotation) implements TaskInput {

    /** 不带蓝图的一次：按配方做东西或只开机。 */
    MachineRunInput(Target target, String item, int count, boolean collect, int maxSeconds, Permissions permissions) {
        this(target, item, count, collect, maxSeconds, permissions, null, null, 0);
    }

    MachineRunInput {
        Objects.requireNonNull(permissions, "permissions");
        if (count < 1) throw new IllegalArgumentException("要做几件至少是 1：" + count);
        if (maxSeconds < 1) throw new IllegalArgumentException("最多等多少秒至少是 1：" + maxSeconds);
        anchor = anchor == null ? null : anchor.immutable();
    }

    @Override public String describe() {
        return item == null ? "开机：" + target : "用机器做 " + count + " 件 " + item;
    }
}

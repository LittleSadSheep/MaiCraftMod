// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 接网络的任务输入：把 target 那台机器接进 source 所在的那种网。来源没给时到现场扫 radius 找。
 * 目标是档案时带着档案，接上了把这张网记进档案。
 *
 * @param kind        网络种类，取值来自已登记的网络读取器（kinetic / me / energy……）
 * @param target      要接的机器：一格机器方块的写法（观察编号、坐标、档案名、记过的地点）
 * @param source      接到哪：同样的写法；不给就到现场找
 * @param radius      找来源的范围，单位格
 * @param archive     目标对上的机器档案；目标不是档案为 null
 * @param permissions 这次任务的许可
 */
record MachineConnectInput(String kind, Target target, Optional<Target> source, int radius,
                           MachineArchive archive, Permissions permissions) implements TaskInput {

    MachineConnectInput {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        source = source == null ? Optional.empty() : source;
        Objects.requireNonNull(permissions, "permissions");
    }

    @Override public String describe() {
        return "把机器接进 " + kind + " 网络";
    }
}

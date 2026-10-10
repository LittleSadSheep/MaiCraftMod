// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 改机器设置的任务输入：哪一格的机器、每项设置要改成什么。
 *
 * @param target      目标对象：一格机器方块的写法（观察编号、坐标、记过的地点）
 * @param settings    要改的设置：键是这台机器认的设置项，值统一转成字符串
 * @param permissions 这次任务的许可
 */
record MachineConfigureInput(Target target, Map<String, String> settings,
                             Permissions permissions) implements TaskInput {

    MachineConfigureInput {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(permissions, "permissions");
        // 逐项按 LLM 给的顺序改：LinkedHashMap 包一层不可改，顺序不丢。
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        if (settings.isEmpty()) throw new IllegalArgumentException("至少要给一项设置");
    }

    @Override public String describe() {
        return "改机器设置（" + settings.size() + " 项）";
    }
}

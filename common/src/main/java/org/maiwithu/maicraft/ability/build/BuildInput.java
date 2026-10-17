// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 施工的任务输入：已经落到锚点的蓝图连同许可，再加一句给人看的说明（按哪张图、几格）。
 *
 * @param construction 交给施工引擎的输入
 * @param what         一句话：按什么施工
 */
record BuildInput(ConstructionInput construction, String what) implements TaskInput {

    BuildInput {
        Objects.requireNonNull(construction, "construction");
        Objects.requireNonNull(what, "what");
    }

    @Override public String describe() {
        return "施工：" + what + "，" + construction.blueprint().cells().size() + " 格";
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 用东西的任务输入：解析并核对过的参数，不可变。
 *
 * @param target     目标对象；只给 item 时为 null（不对着任何东西用）
 * @param item       手上拿什么；空手为 null
 * @param block      按方块类型找目标或核对目标；没给为 null
 * @param entity     按实体类型找目标或核对目标；没给为 null
 * @param count      做几次
 * @param radius     按类型找目标的范围（格）
 * @param textLines  要写到告示牌上的字，逐行；不写字为空列表
 * @param permissions 这次任务的许可
 */
record UseInput(Target target, String item, String block, String entity,
        long count, long radius, List<String> textLines, Permissions permissions) implements TaskInput {

    UseInput {
        Objects.requireNonNull(textLines, "textLines");
        Objects.requireNonNull(permissions, "permissions");
        textLines = List.copyOf(textLines);
        if (count < 1) throw new IllegalArgumentException("做几次至少是 1：" + count);
    }

    /** 是否在写告示牌。 */
    boolean writesText() {
        return !textLines.isEmpty();
    }

    @Override public String describe() {
        String what = block != null ? block : entity != null ? entity : target != null ? "指定的目标" : "手上的东西";
        String times = count > 1 ? "，做 " + count + " 次" : "";
        String writing = writesText() ? "，写「" + String.join(" / ", textLines) + "」" : "";
        return "用一下" + what + times + writing;
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.List;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次寻找的任务输入：找什么、要几个、在多大范围里找。不可变；扫描进度都在任务里。
 *
 * @param kind      找方块、找实体还是找结构线索，三者一次只找一种
 * @param selectors 方块 ID 或标签、实体类型 ID，或结构 ID，按种类解释
 * @param count     要找到几个；找满即收工
 * @param radius    扫描半径，单位格，以角色为圆心；只扫已加载区
 */
record FindInput(FindKind kind, List<String> selectors, int count, int radius) implements TaskInput {

    FindInput {
        selectors = List.copyOf(selectors);
    }

    /** 找什么的三个种类；群系不在其中，群系发现归勘察。 */
    enum FindKind {
        /** 方块：按 ID 或标签找看得见的方块。 */
        BLOCK,
        /** 实体：按类型找看得见的生物等实体。 */
        ENTITY,
        /** 结构：查记过的产地线索，到附近再亲眼确认。 */
        STRUCTURE
    }

    @Override
    public String describe() {
        return "在附近找 " + String.join("、", selectors);
    }
}

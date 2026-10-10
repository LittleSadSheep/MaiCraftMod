// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 靠近的接缝：给一个交互目标，返回把角色带到能动手位置的靠近动作。
 *
 * <p>站位判断、走向站位、补救这些依赖由实现方在启动时接好（游戏接口层的现场、出行轨的移动、许可）；
 * 能力只说"带我靠近这个目标"，不逐个传递底层服务。实现接在启动时创建并登记上，测试用替身。
 */
public interface BringsPlayerClose {

    /** 生成一个靠近动作：走到够得着、看得见这个目标的位置。 */
    Action toward(ApproachTarget target, Permissions permissions);

    /**
     * 同上，但这些格不许当站位：放方块时脚下或头顶不能是要放的那一格，放下去会把自己卡在里面。
     * 默认实现不理会这些格；生产实现把它们并进受保护格判断。
     */
    default Action toward(ApproachTarget target, Permissions permissions, ProtectedCells avoid) {
        return toward(target, permissions);
    }
}

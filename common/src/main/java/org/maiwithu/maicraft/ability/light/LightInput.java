// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 区域补光的任务输入：圆心（或连通空间的起点）、半径（不给就是连通可行走空间）、
 * 光源物品（不给默认火把）、验收范围档与验收亮度、这次任务的许可。
 *
 * @param center       目标对象落实成的那一格
 * @param radius       圆形半径（格）；null 表示连通可行走空间
 * @param item         光源物品 ID；null 默认火把
 * @param coverage     哪些样本要达标、达标比率多少
 * @param minimumLight 验收亮度（1..15）
 * @param permissions  能动哪些方块由许可管
 */
public record LightInput(WorldPosition center, Integer radius, String item,
                         Coverage coverage, int minimumLight, Permissions permissions) implements TaskInput {

    /** 验收范围档：全部达标 / 九成达标 / 只对作物与耕地验收（全部达标）。 */
    public enum Coverage { ALL, MOST, CROPS }

    @Override public String describe() {
        return "把 (" + center.x() + ", " + center.y() + ", " + center.z() + ") 附近照到方块光 "
                + minimumLight + "（" + (radius == null ? "连通范围" : "半径 " + radius + " 格") + "）";
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次出行的任务输入：去哪、到达容差、最多走多久、这次被允许动多少地形。
 * 不可变；运行中会变的进度都在出行任务里。
 *
 * @param target     去哪：坐标（y 可省略）、叫得出名字的地点、看得见的东西、脚下或往某方向走多远
 * @param radius     到达容差，单位格；0 表示要站进那一格
 * @param maxSeconds 最多走多久（秒）；不超过 0 表示不设时限
 * @param permit     这次走到被允许动多少地形（能不能挖路垫路）
 */
record TravelInput(Target target, double radius, int maxSeconds, TerrainPermit permit) implements TaskInput {

    TravelInput {
        if (target == null) throw new IllegalArgumentException("出行必须有目的地");
        if (radius < 0) throw new IllegalArgumentException("到达容差不能为负：" + radius);
    }

    @Override
    public String describe() {
        return "去 " + describeTarget(target);
    }

    /** 给日志与任务面板用的一句话目的地。 */
    private static String describeTarget(Target target) {
        if (target instanceof Target.Position position) {
            Integer y = position.y();
            return "坐标 (" + position.x() + ", " + (y == null ? "?" : y) + ", " + position.z() + ")";
        }
        if (target instanceof Target.Landmark landmark) {
            return "地点「" + landmark.name() + "」";
        }
        if (target instanceof Target.Seen seen) {
            return "看到的东西 " + seen.id();
        }
        if (target instanceof Target.Direction direction) {
            return "往" + chineseToward(direction.toward()) + " " + direction.distance() + " 格";
        }
        if (target instanceof Target.Here) {
            return "脚下";
        }
        return target.kind().name();
    }

    /** 朝向的中文说法，用于日志；方向判断本身在目的地解析里做。 */
    private static String chineseToward(Target.Toward toward) {
        return switch (toward) {
            case FORWARD -> "前";
            case BACKWARD -> "后";
            case LEFT -> "左";
            case RIGHT -> "右";
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
        };
    }
}

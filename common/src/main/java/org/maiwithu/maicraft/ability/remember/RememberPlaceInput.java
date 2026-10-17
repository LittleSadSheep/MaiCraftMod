// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次记（或忘）地点、圈地盘的任务输入：名字、做哪种操作、要记下的位置与半径、动手前这个名字记成什么。
 * 不可变；名字在能力的决定阶段就去过首尾空白，位置带维度。
 *
 * @param name           地点或地盘的名字，原样比较
 * @param operation      记地点、圈地盘还是忘掉
 * @param position       记地点时是那个位置，圈地盘时是地盘中心；忘掉时为 null
 * @param radius         圈地盘的半径（格）；别的操作为 0
 * @param previous       动手前这个名字记成的地点；没记过为 null，写进结果的 previous_position
 * @param previousRegion 动手前同名的地盘；没圈过为 null。圈地盘时写进结果的 previous_position、previous_radius，
 *                       忘掉时写进 forgotten_region
 */
record RememberPlaceInput(String name, Operation operation, WorldPosition position, int radius,
        WorldPosition previous, RememberedRegion previousRegion) implements TaskInput {

    /** 记一个地点、圈一块地盘、忘掉同名的地点与地盘。 */
    enum Operation { REMEMBER, REGION, FORGET }

    RememberPlaceInput {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("地点名不能为空");
        if (operation == null) throw new IllegalArgumentException("要写清是记、圈还是忘");
        if (operation != Operation.FORGET && position == null) {
            throw new IllegalArgumentException("记地点、圈地盘都要有位置");
        }
        if (operation == Operation.REGION && radius <= 0) throw new IllegalArgumentException("地盘半径必须为正：" + radius);
        if (operation == Operation.FORGET && previous == null && previousRegion == null) {
            throw new IllegalArgumentException("忘掉要有记过的地点或地盘");
        }
    }

    /** 记一个地点：同名覆盖，previous 是覆盖前记在哪。 */
    static RememberPlaceInput place(String name, WorldPosition position, WorldPosition previous) {
        return new RememberPlaceInput(name, Operation.REMEMBER, position, 0, previous, null);
    }

    /** 圈一块地盘：同名覆盖，previousRegion 是覆盖前的那块。 */
    static RememberPlaceInput region(String name, WorldPosition center, int radius, RememberedRegion previousRegion) {
        return new RememberPlaceInput(name, Operation.REGION, center, radius, null, previousRegion);
    }

    /** 忘掉同名的地点与地盘：哪样记过就忘哪样。 */
    static RememberPlaceInput forget(String name, WorldPosition previous, RememberedRegion previousRegion) {
        return new RememberPlaceInput(name, Operation.FORGET, null, 0, previous, previousRegion);
    }

    @Override public String describe() {
        return switch (operation) {
            case REMEMBER -> "把「" + name + "」记在 " + position;
            case REGION -> "把 " + position + " 周围 " + radius + " 格圈成地盘「" + name + "」";
            case FORGET -> "忘掉「" + name + "」";
        };
    }
}

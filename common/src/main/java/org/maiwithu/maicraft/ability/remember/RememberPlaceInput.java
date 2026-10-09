// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次记（或忘）地点的任务输入：地点名、要记下的位置、动手前记在哪里。
 * 不可变；名字在能力的决定阶段就去过首尾空白，位置带维度。
 *
 * @param name      地点名，原样比较
 * @param position  要记下的位置；忘掉时为 null
 * @param previous  动手前这个名字记在哪；之前没记过为 null，写进结果的 previous_position
 * @param forget    true 是忘掉这个名字，false 是记住（同名覆盖）
 */
record RememberPlaceInput(String name, WorldPosition position, WorldPosition previous, boolean forget)
        implements TaskInput {

    RememberPlaceInput {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("地点名不能为空");
        if (!forget && position == null) throw new IllegalArgumentException("记住一个地点要有位置");
    }

    @Override public String describe() {
        return forget ? "忘掉地点「" + name + "」" : "把「" + name + "」记在 " + position;
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import com.google.gson.JsonObject;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 场景里的一样模组的东西（电梯、车站、列车、物理结构）：领到的观察编号、种类、方位与距离，
 * 加上联动模组给的场景里那几项与详情。
 *
 * @param summary 场景里列的那几项
 * @param detail  observe(detail) 时给全的
 */
public record SceneThing(String id, String category, WorldPosition position, String direction, String compass,
                         int distance, JsonObject summary, JsonObject detail) {

    public SceneThing {
        summary = summary.deepCopy();
        detail = detail.deepCopy();
    }

    @Override public JsonObject summary() {
        return summary.deepCopy();
    }

    @Override public JsonObject detail() {
        return detail.deepCopy();
    }
}

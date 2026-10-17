// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import org.maiwithu.maicraft.behavior.travel.TravelDestination;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次跨维度出行的任务输入：目标维度、到了之后接着走的目的地（可选）、找门范围、时限与许可。
 * 不可变；运行中会变的进度都在跨维度任务里。任务工厂按它建跨维度任务，服务经闭包交给任务。
 *
 * @param targetDimension 要去的维度（命名空间维度 id）
 * @param destination     到了目标维度接着走的目的地；"就是去那个维度"时为 null
 * @param searchDistance  找活门的范围（格）：查了这么远的已加载区没有门才轮到建门
 * @param maxSeconds      最多走多久（秒）；不超过 0 表示不设时限
 * @param permissions     这次任务的许可：动地形管建门；插末影之眼是去末地本身要用的，不看 use_rare_items
 */
record DimensionTravelInput(String targetDimension, TravelDestination destination, int searchDistance,
        int maxSeconds, Permissions permissions) implements TaskInput {

    DimensionTravelInput {
        if (targetDimension == null || targetDimension.isBlank()) {
            throw new IllegalArgumentException("跨维度出行必须有目标维度");
        }
    }

    @Override
    public String describe() {
        return "去" + shortDimension() + (destination == null ? "" : "的 " + destination.describe());
    }

    private String shortDimension() {
        return targetDimension.replace("minecraft:", "");
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import org.maiwithu.maicraft.behavior.travel.TravelDestination;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;

import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次出行的任务输入：已经解析好的目的地、最多走多久、这次被允许动多少地形。
 * 不可变；运行中会变的进度都在出行任务里。
 *
 * @param destination 解析好的目的地（去哪、高度核实过没有、容差多少格）
 * @param maxSeconds  最多走多久（秒）；不超过 0 表示不设时限
 * @param permit      这次走到被允许动多少地形（能不能挖路垫路）
 */
record TravelInput(TravelDestination destination, int maxSeconds, TerrainPermit permit) implements TaskInput {

    TravelInput {
        if (destination == null) throw new IllegalArgumentException("出行必须有目的地");
    }

    @Override
    public String describe() {
        return "去" + destination.describe();
    }
}

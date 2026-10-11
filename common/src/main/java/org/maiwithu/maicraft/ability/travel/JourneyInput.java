// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.travel.JourneyTask;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 可以换交通的一次出行的任务输入：这趟行程（目的地、指定的方式、许可、时限），加上走路那几段被允许动多少地形。
 *
 * @param journey 目的地、via、给方式的话、许可与时限
 * @param permit  走路的几段被允许动多少地形（能不能挖路垫路）
 */
record JourneyInput(JourneyTask.Journey journey, TerrainPermit permit) implements TaskInput {

    JourneyInput {
        if (journey == null) throw new IllegalArgumentException("出行必须有目的地");
    }

    @Override
    public String describe() {
        return "去" + journey.destination().describe() + (journey.via() == null ? "" : "（via " + journey.via() + "）");
    }
}

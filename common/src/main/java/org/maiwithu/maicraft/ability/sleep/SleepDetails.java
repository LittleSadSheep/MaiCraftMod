// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 睡觉的结果细节：床从哪来、睡没睡上、是不是一觉睡到了天亮。
 *
 * @param enteredSleep             确认躺下了
 * @param bedSource                床的来源：world 世界里原有的床；carried 放下了自带的床；crafted 现做的床
 * @param bedReadyForTonight       白天被叫去睡时，今晚的床是否已备好；不是白天场景为 null
 * @param nightSkippedBySleep      确认期间日期前进，一觉睡到了天亮
 * @param sleepCompletedExternally 夜休去床途中夜晚被别人先睡过，视为已经度过这一夜
 */
record SleepDetails(boolean enteredSleep, BedSource bedSource, Boolean bedReadyForTonight,
        boolean nightSkippedBySleep, boolean sleepCompletedExternally) implements ResultDetails {

    /** 床的来源。 */
    enum BedSource {
        WORLD,
        CARRIED,
        CRAFTED
    }
}

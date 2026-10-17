// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 记住看见的东西的接缝：感知把亲眼确认的设施与产地写进世界记忆，来源标"亲眼看到"。
 *
 * <p>只记亲眼确认过的：看见箱子只记"这里有只箱子"，不猜里面有什么；
 * 听见的和推断出来的不从这里写。世界记忆实现它，测试用替身。
 */
public interface RemembersSightings {

    /** 记下亲眼看到的容器：看见这里有只这个，没打开过，里面不知道。 */
    void containerSeen(WorldPosition position, String blockType, Instant when);

    /** 记下亲眼看到的工作站：工作台、熔炉这类，看见就知道它在这里。 */
    void workstationSeen(WorldPosition position, String blockType, Instant when);

    /** 记一条亲眼看到的产地线索：这一片见过什么，例如路过时看见一片树林。 */
    void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when);
}

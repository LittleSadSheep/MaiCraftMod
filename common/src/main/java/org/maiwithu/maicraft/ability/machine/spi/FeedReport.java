// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;

/**
 * 投料做完后的实情。把料扔进工作盆、扔到石磨顶上这类投法不一定件件都进：
 * 投料动作说得清进去几件、没进去的在哪时实现它，machine_run 按这里的件数记投料、把说明写进结果；
 * 不实现的（手持右键放上置物台这类一下就整份放好的）按要投的件数整份记。
 */
public interface FeedReport {

    /** 确认进了机器的件数。 */
    int delivered();

    /** 没进去的那些在哪：落在机器上还没被收走的、落到别处的、没丢完的原因。 */
    List<String> notes();
}

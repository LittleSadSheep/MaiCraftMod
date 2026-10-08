// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.task.TickContext;

/** 等待条件，例如"天黑了"、"熔炉烧完了"。每刻检查一次，只读，不做动作。 */
public interface Condition {

    /** 条件此刻是否成立。 */
    boolean satisfied(TickContext context);

    /** 给回执和面板的一句话，例如"等到天黑（约 3 分钟后）"。 */
    String describe();
}

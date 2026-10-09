// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

/** 详细档里的页：此刻（各段现状），或最近的目标（这段时间都干了什么）。按 F9+H 来回切换。 */
public enum PanelPage {
    /** 连接、目标、任务、生存需求、结果、最近发生的。 */
    NOW,
    /** 最近下达的顶层目标，每个一行。 */
    RECENT_GOALS;

    /** 按 F9+H 后的另一页。 */
    public PanelPage other() {
        return this == NOW ? RECENT_GOALS : NOW;
    }
}

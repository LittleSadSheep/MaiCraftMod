// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

/**
 * 面板观测的 MCP 活动痕迹：最近一次感知的视图，以及自上次行动（execute）以来的感知次数。
 * 只记录最近一次，不参与任何决策；LLM 反复感知而不提交目标，计数值就是效率问题的直接信号。
 */
public final class McpActivityTrace {
    private static volatile String lastPerceiveViews;
    private static volatile int perceivesSinceAction;
    private McpActivityTrace() {}

    /** view 为调用方省略时的 null 原样保留，由读取方决定如何显示。 */
    public static void notePerceive(String view) {
        lastPerceiveViews = view;
        perceivesSinceAction++;
    }

    public static void noteAction() { perceivesSinceAction = 0; }

    public static String lastPerceiveViews() { return lastPerceiveViews; }
    public static int perceivesSinceAction() { return perceivesSinceAction; }
}

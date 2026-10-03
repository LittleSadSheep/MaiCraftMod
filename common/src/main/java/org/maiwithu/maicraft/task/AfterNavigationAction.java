// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** 主任务先续订路线，再在帧末尝试自己的随行点击；租约不跨刻，取消也不能留下迟到放置。 */
public final class AfterNavigationAction {
    private static Object owner;
    private static long tick;
    private static Runnable action;

    private AfterNavigationAction() {}

    public static void request(Object task, LocalPlayerContext context, Runnable operation) {
        owner = task; tick = context.tickRevision(); action = operation;
    }

    public static void cancel(Object task) { if (owner == task) { owner = null; action = null; } }

    public static void run(LocalPlayerContext context) {
        var pending = action;
        action = null; owner = null;
        // 界面、自救和精确动作是否允许借资源，仍由点击端口检查；这里绝不延后到下一刻偷偷执行。
        if (pending != null && tick == context.tickRevision() && context.permitsNativeActions()) pending.run();
    }
}

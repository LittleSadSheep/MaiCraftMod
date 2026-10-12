// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

/**
 * 夜里自动去睡的会话设置：默认开（像正常玩家一样，天黑了有床就找空当去睡），LLM 能用 sleep 能力关掉——
 * 多人游戏里有玩家说"晚上别睡"时照办。关着时夜晚这项生存需求不再去找床，
 * 露天又打不过照旧挖坑自保。进世界重置为开。只在客户端线程读写。
 */
public final class AutoSleep {

    private boolean enabled = true;

    /** 进世界重置为默认：开。 */
    public void reset() {
        enabled = true;
    }

    /** 开：天黑了有床就找空当去睡。 */
    public void enable() {
        enabled = true;
    }

    /** 关：夜里不自己去睡，等 LLM 叫睡或再打开。 */
    public void disable() {
        enabled = false;
    }

    public boolean enabled() {
        return enabled;
    }
}

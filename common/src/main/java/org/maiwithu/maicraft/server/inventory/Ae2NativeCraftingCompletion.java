// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import java.util.IdentityHashMap;
import java.util.Map;

/** 观察原生链接生命周期判定，不修改 AE2 链接、作业或材料状态。 */
public final class Ae2NativeCraftingCompletion {
    public enum State { RUNNING, COMPLETED, CANCELLED }
    private static final int MAX_LINKS = 128;
    private static final Map<Object, State> OWNED = new IdentityHashMap<>();
    private Ae2NativeCraftingCompletion() {}

    static synchronized boolean watch(Object link) {
        if (link == null) return false;
        if (OWNED.containsKey(link)) return true;
        if (OWNED.size() >= MAX_LINKS) return false;
        OWNED.put(link, State.RUNNING); return true;
    }

    static synchronized State state(Object link) { return OWNED.get(link); }
    static synchronized void forget(Object link) { OWNED.remove(link); }

    /** 仅由真实原生 markDone/cancel 钩子调用；未被拥有的链接无法获得记录。 */
    public static synchronized void finished(Object link, boolean completed) {
        if (OWNED.get(link) == State.RUNNING) OWNED.put(link, completed ? State.COMPLETED : State.CANCELLED);
    }
}

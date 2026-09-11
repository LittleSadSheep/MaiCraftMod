// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import java.util.IdentityHashMap;
import java.util.Map;

/** Observes native link lifecycle decisions without changing AE2 links, jobs or material state. */
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

    /** Called only by the actual native markDone/cancel hooks; unowned links cannot acquire a record. */
    public static synchronized void finished(Object link, boolean completed) {
        if (OWNED.get(link) == State.RUNNING) OWNED.put(link, completed ? State.COMPLETED : State.CANCELLED);
    }
}

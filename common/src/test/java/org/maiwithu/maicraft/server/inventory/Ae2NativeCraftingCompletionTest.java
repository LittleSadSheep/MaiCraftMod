// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import java.util.ArrayList;
import java.util.List;

/** Native lifecycle decisions are bound to the exact owned link object, finite, and terminal. */
public final class Ae2NativeCraftingCompletionTest {
    public static void main(String[] args) {
        Object owned = new Object(), other = new Object();
        Ae2NativeCraftingCompletion.finished(other, true);
        check(Ae2NativeCraftingCompletion.state(other) == null, "An unowned native callback acquired a completion record");
        check(Ae2NativeCraftingCompletion.watch(owned), "Owned link could not be watched");
        Ae2NativeCraftingCompletion.finished(other, true);
        check(Ae2NativeCraftingCompletion.state(owned) == Ae2NativeCraftingCompletion.State.RUNNING, "Another job completed the owned link");
        Ae2NativeCraftingCompletion.finished(owned, true);
        Ae2NativeCraftingCompletion.finished(owned, false);
        check(Ae2NativeCraftingCompletion.state(owned) == Ae2NativeCraftingCompletion.State.COMPLETED, "An old-link cancel downgraded a completed job");
        Ae2NativeCraftingCompletion.forget(owned);
        check(Ae2NativeCraftingCompletion.state(owned) == null, "Retiring a job retained its native link");
        List<Object> links = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            Object link = new Object(); links.add(link); check(Ae2NativeCraftingCompletion.watch(link), "Tracking exhausted before its declared budget");
        }
        check(!Ae2NativeCraftingCompletion.watch(new Object()), "Native lifecycle tracking grew without a bound");
        Object cancelled = links.getFirst(); Ae2NativeCraftingCompletion.finished(cancelled, false);
        Ae2NativeCraftingCompletion.finished(cancelled, true);
        check(Ae2NativeCraftingCompletion.state(cancelled) == Ae2NativeCraftingCompletion.State.CANCELLED, "Completion revived a cancelled job");
        links.forEach(Ae2NativeCraftingCompletion::forget);
        System.out.println("AE2 native completion ownership, terminal-state and bounded-retention tests passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

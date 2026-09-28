// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import org.maiwithu.maicraft.core.task.container.ContainerSplitPlanner.Side;
import org.maiwithu.maicraft.core.task.container.ContainerSplitPlanner.State;

/** 真实存量可以超过普通叠数；每次鼠标携带量、精确交付量和退回容量仍按原生点击逐笔守恒。 */
public final class BackpackSplitPlannerTest {
    public static void main(String[] args) {
        for (int source : new int[]{1, 17, 64, 65, 512, Integer.MAX_VALUE}) for (int cap : new int[]{1, 16, 64})
            for (int amount = 1; amount <= Math.min(source, cap); amount++) verify(source, amount, cap);
        check(BackpackSplitPlanner.plan(512, 64, 512, 64).size() == 2, "one full carried stack uses two clicks");
        check(BackpackSplitPlanner.plan(512, 32, -1, 64).size() == 2, "exact half needs no return permission");
        try { BackpackSplitPlanner.plan(512, 15, -1, 64); throw new AssertionError("illegal return was accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("BackpackSplitPlannerTest: passed");
    }
    private static void verify(int source, int amount, int cap) {
        var state = new State(source, 0, 0);
        var steps = BackpackSplitPlanner.plan(source, amount, source, cap);
        for (var step : steps) {
            check(step.before().equals(state), "click uses the settled preceding state");
            int s = state.source(), c = state.cursor(), d = state.deposited();
            if (step.side() == Side.SOURCE) {
                if (c == 0) { int take = Math.min(s, cap); if (step.button() == 1) take = (take + 1) / 2; s -= take; c += take; }
                else { int put = step.button() == 0 ? c : 1; s += put; c -= put; }
            } else { int put = step.button() == 0 ? c : 1; c -= put; d += put; }
            state = new State(s, c, d);
            check(state.equals(step.after()) && c <= cap && d <= amount && (long) s + c + d == source,
                    "native pickup cap and exact delivery hold even for a very large source");
        }
        check(state.equals(new State(source - amount, 0, amount)) && steps.size() <= cap + 2,
                "bounded clicks finish with an empty cursor and the untouched surplus");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

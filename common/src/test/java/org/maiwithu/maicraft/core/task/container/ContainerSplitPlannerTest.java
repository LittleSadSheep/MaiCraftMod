package org.maiwithu.maicraft.core.task.container;

/** 覆盖普通堆的全部取量；每一步守恒、目的格不超额，最后鼠标为空且净搬运数准确。 */
public final class ContainerSplitPlannerTest {
    public static void main(String[] args) {
        check(ContainerSplitPlanner.plan(64, 49, 64).size() == 7, "49 items should take seven ordinary clicks");
        check(ContainerSplitPlanner.plan(64, 33, 64).size() == 5, "33 items should take five ordinary clicks");
        check(ContainerSplitPlanner.plan(64, 29, 64).size() == 5, "29 items should take half, return three and deposit");
        check(ContainerSplitPlanner.plan(63, 32, 64).size() == 2, "odd source half is rounded upwards");
        check(ContainerSplitPlanner.plan(64, 63, 64).size() == 3, "one returned item is cheaper than many halves");
        for (int source = 1; source <= 64; source++) for (int count = 1; count <= source; count++) verify(source, count, 64);
        for (int count : new int[]{1, 17, 127, 255, 256, 257, 511}) verify(512, count, 512);
        check(ContainerSplitPlanner.plan(64, 32, -1).size() == 2, "a locked output slot permits a half with no return");
        boolean rejected = false;
        try { ContainerSplitPlanner.plan(64, 29, -1); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "an output slot must not receive the three excess items");
        System.out.println("ContainerSplitPlannerTest: bounded exact quantities, click counts and return permissions passed");
    }
    private static void verify(int source, int amount, int capacity) {
        var steps = ContainerSplitPlanner.plan(source, amount, capacity);
        var state = new ContainerSplitPlanner.State(source, 0, 0);
        check(steps.size() <= amount + 2, "optimized plan must not exceed the old pickup, repeated deposit and return path");
        for (var step : steps) {
            check(step.before().equals(state), "every click must use the preceding settled counts");
            int s = state.source(), c = state.cursor(), d = state.deposited();
            if (step.side() == ContainerSplitPlanner.Side.SOURCE) {
                if (c == 0) { int take = step.button() == 0 ? s : (s + 1) / 2; s -= take; c += take; }
                else { int give = step.button() == 0 ? c : 1; s += give; c -= give; }
            } else { int give = step.button() == 0 ? c : 1; c -= give; d += give; }
            state = new ContainerSplitPlanner.State(s, c, d);
            check(state.equals(step.after()) && s >= 0 && c >= 0 && d >= 0 && d <= amount && s + c + d == source,
                    "ordinary click arithmetic must conserve the stack without transient over-delivery");
        }
        check(state.source() == source - amount && state.cursor() == 0 && state.deposited() == amount,
                "the exact request must finish with its unrequested source remainder intact");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

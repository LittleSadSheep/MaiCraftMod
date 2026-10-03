package org.maiwithu.maicraft.core.pathing.calc;

/** 相同地形上的相同计算重放不算进展；真正展开更多候选或换到新的问题后才增长。 */
public final class PlanningWorkProgressTest {
    public static void main(String[] args) {
        var progress = new PlanningWorkProgress();
        check(progress.observe(100) == 100, "completed planning work is visible");
        check(progress.observe(0) == 100 && progress.observe(100) == 100, "same-scope replay cannot refresh liveness");
        check(progress.observe(140) == 140, "new work beyond the previous high-water counts");
        progress.newScope();
        check(progress.revision() == 140 && progress.observe(5) == 145, "changing scope alone is not work");
        System.out.println("PlanningWorkProgressTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

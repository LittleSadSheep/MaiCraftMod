package org.maiwithu.maicraft.task;

/** 连续前进可以超过多轮初始预算；暂停不扣时，重复计数和父子轮询不能伪造工作进展。 */
public final class ProgressBudgetTest {
    public static void main(String[] args) {
        var budget = new ProgressBudget(600);
        check(!budget.observe(0, false) && !budget.observe(599, false), "first idle window remains available");
        check(!budget.observe(599, true) && budget.remaining(599) == 600, "real progress immediately refills the budget");
        check(!budget.observe(1198, true) && !budget.observe(1797, true), "productive work outlives its initial total duration");
        check(budget.observe(2397, false), "a full idle interval still stops unproductive work");
        var counter = new ProgressBudget(30);
        counter.observeCounter(0, 8);
        check(!counter.observeCounter(29, 0) && counter.observeCounter(30, 8), "retries and duplicate counters do not refill");
        check(!counter.observeCounter(31, 9) && counter.remaining(31) == 30, "new verified units refill after an idle check");

        var root = new Record(1000); var rootBudget = root.progressBudget(600);
        Record child;
        try (var scope = root.deadlineScope()) { child = new Record(1000); }
        var childBudget = child.progressBudget(600);
        var independent = new Record(1000).progressBudget(600);
        independent.observe(100, false);
        rootBudget.observe(100, false); childBudget.observe(100, false);
        for (int i = 0; i < 1500; i++) root.freezeDeadline();
        check(!rootBudget.observe(1800, false) && rootBudget.remaining(1800) == 400, "shared pause is excluded from idle consumption");
        childBudget.observeCounter(1800, 1);
        check(!rootBudget.observe(1800, false) && rootBudget.remaining(1800) == 600, "child progress refills the parent budget");
        check(independent.observe(1800, false), "unrelated task scopes do not receive another task's progress");
        check(!childBudget.observeCounter(1801, 1) && childBudget.remaining(1801) == 599,
                "parent receiving progress must not echo it back as new work");
        for (int i = 1802; i < 2400; i++) { rootBudget.observe(i, false); childBudget.observeCounter(i, 1); }
        check(rootBudget.observe(2400, false) && childBudget.observeCounter(2400, 1), "parent and child both expire after real progress stops");
        childBudget.observeCounter(2500, 2);
        check(child.getDeadlineGameTime() >= 3100, "progress renews the execution deadline too");
        ProgressBudget helper;
        try (var scope = root.deadlineScope()) { helper = ProgressBudget.currentTask(600); }
        helper.observe(2600, true);
        check(!rootBudget.observe(2600, false) && rootBudget.remaining(2600) == 600, "an internal helper can report progress without its own task record");
        System.out.println("ProgressBudgetTest: passed");
    }
    private static final class Record extends TaskRecord {
        Record(long deadline) { super("progress-test", "", deadline); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

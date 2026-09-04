package baritone.pathing.path;

/** Regression for SUCCESS -> backward relocation loops on the client tick. */
public final class PathTickBudgetTest {
    public static void main(String[] args) {
        PathTickBudget budget = new PathTickBudget();
        expect(budget.enter(5), PathTickBudget.Decision.RUN);
        expect(budget.enter(6), PathTickBudget.Decision.RUN);
        expect(budget.enter(5), PathTickBudget.Decision.CYCLE);
        budget.reset();
        for (int i = 0; i < 16; i++) expect(budget.enter(i), PathTickBudget.Decision.RUN);
        expect(budget.enter(16), PathTickBudget.Decision.YIELD);
        budget.reset();
        expect(budget.enter(16), PathTickBudget.Decision.RUN);
        expect(budget.enter(14), PathTickBudget.Decision.RUN);
        expect(budget.enter(15), PathTickBudget.Decision.RUN);
        expect(budget.enter(16), PathTickBudget.Decision.CYCLE);
        System.out.println("PathTickBudgetTest: passed");
    }

    private static void expect(PathTickBudget.Decision actual, PathTickBudget.Decision expected) {
        if (actual != expected) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}

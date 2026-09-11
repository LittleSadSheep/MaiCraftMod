// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

public final class ProductionSupplyPacerTest {
    public static void main(String[] args) {
        threeItemsHaveThreeQuotedBatches();
        largeBatchSurvivesPartialAndFullBuffers();
        existingBulkStockIsNotPretendedToBePaced();
        independentConsumersAreNotDeduplicatedByTick();
        System.out.println("ProductionSupplyPacerTest: passed");
    }

    private static void threeItemsHaveThreeQuotedBatches() {
        var budget = budget(3, 0);
        var pacer = new ProductionSupplyPacer(budget, 1, Map.of("first_press", 3L), 0, Map.of());
        var work = new Quotes();
        deposit(pacer, budget, work, 0, 1);
        check(!pacer.ready(20), "Twenty ticks and a drained source do not release a new batch");
        pacer.observe(Map.of("final_target", 1L), 40);
        check(!pacer.ready(40), "Final-target events cannot substitute for this source's first consumer");
        pacer.observe(Map.of("first_press", 1L, "final_target", 0L), 100);
        deposit(pacer, budget, work, 100, 1);
        pacer.observe(Map.of("first_press", 1L), 120);
        check(!pacer.ready(120), "Rereading the same event cannot open another batch");
        pacer.observe(Map.of("first_press", 2L), 200);
        deposit(pacer, budget, work, 200, 1);
        check(work.amounts.equals(List.of(1, 1, 1)), "Actual inventory.quote calls must each request one item");
        check(budget.remaining() == 0 && budget.injected() == 3, "Pacing keeps the full-window total cap");
        pacer.observe(Map.of("first_press", 3L), 300);
        check(!pacer.ready(500), "Finished budgets never regain allowance");
    }

    private static void largeBatchSurvivesPartialAndFullBuffers() {
        var budget = budget(260, 0);
        var pacer = new ProductionSupplyPacer(budget, 130, Map.of("factory", 2L), 0, Map.of());
        var work = new Quotes();
        deposit(pacer, budget, work, 0, 40);
        check(!pacer.ready(19) && pacer.ready(20), "Each real source has its own twenty-tick minimum interval");
        deposit(pacer, budget, work, 20, 64);
        deposit(pacer, budget, work, 40, 26);
        check(work.amounts.equals(List.of(64, 64, 26)), "First batch above 64 is completed across bounded transactions");
        check(budget.injected() == 130 && !pacer.ready(60), "Full first batch waits for actual processing");
        pacer.observe(Map.of("factory", 1L), 100);
        work.full = true;
        JsonObject quote = pacer.quote(work, body(pacer.quoteAmount()));
        check(quote.get("status").getAsString().equals("full"), "Exercise the actual quote path with a full buffer");
        pacer.settled(100, 0);
        check(budget.injected() == 130 && pacer.quoteAmount() == 64, "A full quote consumes neither batch nor window budget");
        work.full = false;
        budget.confirm(receipt("no_change", 0), 64, "opaque"); pacer.settled(120, 0);
        check(budget.remaining() == 130, "A settled no-change transfer also preserves the released batch");
        deposit(pacer, budget, work, 140, 64);
        deposit(pacer, budget, work, 160, 64);
        deposit(pacer, budget, work, 180, 2);
        check(budget.remaining() == 0, "Partials and full buffers never overfeed the allocation");
    }

    private static void existingBulkStockIsNotPretendedToBePaced() {
        var exhausted = budget(3, 3);
        var pacer = new ProductionSupplyPacer(exhausted, 1, Map.of("press", 3L), 3, Map.of());
        check(pacer.report().get("initial_unpaced_stock").equals(3L), "Pre-existing three-stack remains an unpaced fact");
        check(!pacer.ready(0) && pacer.unverifiable(0, 2, 40) == null, "Let native processing run before judging evidence");
        pacer.observe(Map.of("press", 1L), 10);
        check(pacer.unverifiable(50, 2, 40) == null, "Honor the declared idle observation allowance");
        String problem = pacer.unverifiable(51, 2, 40);
        check(problem != null && problem.contains("budget_exhausted") && problem.contains("initial_unpaced_stock=3"),
                "One bulk event with exhausted budget must fail explicitly rather than wait for a long task deadline");
        var remaining = budget(5, 3);
        var recovery = new ProductionSupplyPacer(remaining, 1, Map.of("press", 5L), 3, Map.of());
        recovery.observe(Map.of("press", 1L), 20);
        check(recovery.quoteAmount() == 1, "A real event after pre-existing bulk stock can release a new paced item");
    }

    private static void independentConsumersAreNotDeduplicatedByTick() {
        var budget = budget(4, 0);
        var pacer = new ProductionSupplyPacer(budget, 2, Map.of("factory_a", 2L, "factory_b", 2L), 0, Map.of());
        deposit(pacer, budget, new Quotes(), 0, 2);
        pacer.observe(Map.of("factory_a", 1L, "factory_b", 0L), 100);
        check(pacer.quoteAmount() == 0, "An aggregate source batch waits for each first consumer's progress");
        pacer.observe(Map.of("factory_a", 1L, "factory_b", 1L), 100);
        check(pacer.quoteAmount() == 2, "Two factories completing in the same tick retain independent native event counts");
        pacer.observe(Map.of("factory_a", 1L, "factory_b", 1L), 101);
        check(pacer.quoteAmount() == 2, "Repeated observations cannot multiply the released amount");
        reject(() -> pacer.observe(Map.of("factory_a", 0L, "factory_b", 1L), 102), "A changed processing stream cannot reset pacing");
    }

    private static ProductionSupplyBudget budget(long total, long initial) {
        var budget = new ProductionSupplyBudget(total); budget.initialize(initial); return budget;
    }
    private static void deposit(ProductionSupplyPacer pacer, ProductionSupplyBudget budget, Quotes work, long tick, int actual) {
        check(pacer.ready(tick), "Native batch must be released before a quote is submitted");
        int request = pacer.quoteAmount();
        JsonObject quoted = pacer.quote(work, body(request));
        check(quoted.get("amount").getAsInt() == request, "Observe the same amount on the actual quote invocation");
        int moved = budget.confirm(receipt(actual == request ? "applied" : "partial", actual), request, "opaque");
        pacer.settled(tick, moved);
    }
    private static JsonObject body(int amount) { var body = new JsonObject(); body.addProperty("amount", amount); return body; }
    private static JsonObject receipt(String status, int amount) {
        var result = new JsonObject(); result.addProperty("status", status); result.addProperty("transferred", amount);
        result.addProperty("resource_id", "opaque"); return result;
    }
    private static final class Quotes implements ProductionWork {
        final List<Integer> amounts = new ArrayList<>(); boolean full;
        @Override public JsonObject request(String operation, JsonObject body, boolean mutating) {
            check(operation.equals("inventory.quote") && !mutating, "Pacing must use the real read-only quote operation");
            amounts.add(body.get("amount").getAsInt());
            var result = body.deepCopy(); result.addProperty("status", full ? "full" : "ready"); return result;
        }
        @Override public boolean approach(BlockPos position) { return true; }
        @Override public TaskState advanceChild(Task task) { return null; }
        @Override public void extendDeadlineTo(long tick) { }
    }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; } throw new AssertionError(message);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/**
 * 检查电梯提示使用实际候选楼层，逃离目标与不限高度目标不被中心点误导；途中改目标、加保护或交通已产生影响时要保留相应限制和失败原因。
 */
public final class TransportIntentTest {
    public static void main(String[] args) {
        BlockPos lower = new BlockPos(-81, 103, -4), upper = new BlockPos(-87, 116, -10);
        var goal = NavGoal.composite(List.of(NavGoal.exact(lower), NavGoal.exact(upper)));
        var hints = TransportPlan.elevatorHints(goal, lower);
        check(hints.contains(lower) && hints.contains(upper) && hints.size() == 2,
                "use actual member floors, not their centroid");
        check(!hints.contains(goal.center()), "the composite centroid is not an elevator destination");
        check(TransportPlan.elevatorHints(NavGoal.runAway(lower, 103), lower).isEmpty(),
                "escaping must not transport toward the danger origin");
        check(TransportPlan.elevatorHints(NavGoal.column(-20, 30), lower).isEmpty(),
                "a column's diagnostic Y=0 is not a floor request");
        check(TransportPlan.elevatorHints(NavGoal.yLevel(116), lower).equals(List.of(new BlockPos(-81, 116, -4))),
                "an explicit Y plane keeps its requested elevation");
        var empty = LongSets.emptySet();
        var original = new GoalCompiler.Compiled(goal, empty);
        check(TransportNavigator.compatibleGoal(original, upper, original.semanticFingerprint(), empty, empty), "original member remains usable");
        check(!TransportNavigator.compatibleGoal(GoalCompiler.standOn(lower), upper, original.semanticFingerprint(), empty, empty),
                "removed member cannot complete the replacement goal");
        check(!TransportNavigator.compatibleGoal(original, upper, original.semanticFingerprint(), empty, LongSets.singleton(upper.asLong())),
                "a new body prohibition must stop the old leg at a safe boundary");
        check(!TransportNavigator.compatibleGoal(null, upper, original.semanticFingerprint(), empty, empty), "lost target cannot authorize continuation");
        var workstation = GoalCompiler.standAdjacent(upper);
        check(!workstation.goal().isAt(upper), "fixture must be an occupied goal cell");
        check(TransportNavigator.compatibleGoal(workstation, upper, workstation.semanticFingerprint(), empty, empty),
                "an intermediate elevator floor must not cancel an unchanged workstation approach");
        String failure = TransportNavigator.exhaustedReason(List.of(java.util.Map.of("mode", "elevator", "success", false,
                "code", "no_proven_elevator_route", "detail", "no confirmed boarding doorway")), List.of());
        check(failure.contains("no_proven_elevator_route") && failure.contains("no confirmed boarding doorway"),
                "an exhausted transport plan must preserve the actual session failure in the task result");
        check(TransportNavigator.needsInspectionAfterFailure(TransportSession.Result.failed("corridor_changed", "landed", true, false)),
                "a failed flight that moved the body must not automatically launch another candidate");
        check(!TransportNavigator.needsInspectionAfterFailure(TransportSession.Result.failed("no_corridor", "no effect", false, false)),
                "pure route rejection may still consider other observed candidates");
        System.out.println("TransportIntentTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

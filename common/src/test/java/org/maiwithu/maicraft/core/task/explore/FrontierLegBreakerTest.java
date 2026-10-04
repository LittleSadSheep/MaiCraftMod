package org.maiwithu.maicraft.core.task.explore;

import java.util.List;

/** 探索腿连续失败先轮换方位重试，八个方位耗尽才宣布受阻；一次到达就重新计数，全向搜索无可轮换。 */
public final class FrontierLegBreakerTest {
    public static void main(String[] args) {
        burstsThroughAllBearings();
        sporadicFailuresKeepBearing();
        successRestartsFailureBudget();
        allDirectionSearchHasNoBearing();
        fullCircleSectorCannotRotate();
        blockedMessageCarriesDecisionFacts();
        System.out.println("FrontierLegBreakerTest: passed");
    }

    // 每个方位连续失败五次轮换一次；七次轮换后八个方位全部试过，第八轮达阈值宣布受阻。
    private static void burstsThroughAllBearings() {
        var current = ExplorationSector.of("north", 20, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int rotation = 1; rotation <= FrontierLegBreaker.MAX_ROTATIONS; rotation++) {
            for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION; i++) {
                boolean threshold = i == FrontierLegBreaker.FAILURES_BEFORE_ROTATION - 1;
                var decision = breaker.onLegFailure(current);
                check(decision == (threshold
                        ? FrontierLegBreaker.Decision.ROTATED
                        : FrontierLegBreaker.Decision.KEEP_GOING),
                        "rotation " + rotation + " burst leg " + i);
                check(breaker.consecutiveFailures() == (threshold ? 0 : i + 1),
                        "failure budget restarts on rotation");
                if (decision == FrontierLegBreaker.Decision.ROTATED) {
                    current = breaker.rotated(current);
                }
            }
        }
        check(breaker.rotations() == FrontierLegBreaker.MAX_ROTATIONS,
                "seven rotations cover all eight bearings");
        check(breaker.rotatedBearings().equals(List.of(45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)),
                "rotated bearings recorded clockwise");
        for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION - 1; i++) {
            check(breaker.onLegFailure(current) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "final bearing gets its own failure budget");
        }
        check(breaker.onLegFailure(current) == FrontierLegBreaker.Decision.EXHAUSTED,
                "all bearings exhausted, report blocked direction");
        String blocked = FrontierLegBreaker.blockedMessage(
                "frontier leg", 40, breaker, current);
        check(blocked.contains("315 degrees"), "message lists every rotated bearing");
        check(blocked.contains("may_alter_terrain"), "message offers the dig consent option");
        var rotated = breaker.rotated(current);
        check(rotated.bearing() == current.bearing() + 45.0, "rotation shifts bearing by 45");
        check(rotated.request() == current.request(), "rotation keeps width and min distance");
        var narrow = ExplorationSector.of("north", 20, 0, 256).at(0, 0, 0);
        check(narrow.contains(0, -64) && !narrow.contains(64, -64), "original cone points north");
        check(breaker.rotated(narrow).contains(64, -64)
                && !breaker.rotated(narrow).contains(0, -64), "rotated cone points northeast");
    }

    private static void sporadicFailuresKeepBearing() {
        var sector = ExplorationSector.of("north", 90, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION - 1; i++) {
            check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "sporadic failures stay on the current bearing");
        }
        check(breaker.rotations() == 0 && breaker.rotatedBearings().isEmpty(),
                "no rotation below threshold");
    }

    private static void successRestartsFailureBudget() {
        var sector = ExplorationSector.of("north", 90, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION - 1; i++) {
            breaker.onLegFailure(sector);
        }
        breaker.onLegSuccess();
        check(breaker.consecutiveFailures() == 0, "arrival clears consecutive failures");
        for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION - 1; i++) {
            check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "budget must refill after a success");
        }
        check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.ROTATED,
                "full budget after success rotates again");
    }

    private static void allDirectionSearchHasNoBearing() {
        var sector = ExplorationSector.of(null, null, null, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION - 1; i++) {
            check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "all-direction search still tolerates sporadic failures");
        }
        check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.EXHAUSTED,
                "all-direction search reports blocked at threshold");
    }

    private static void fullCircleSectorCannotRotate() {
        var sector = ExplorationSector.of("north", 360, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION - 1; i++) {
            check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "full-circle sector still tolerates sporadic failures");
        }
        check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.EXHAUSTED,
                "full-circle sector has no new bearing to rotate to");
    }

    private static void blockedMessageCarriesDecisionFacts() {
        var sector = ExplorationSector.of(null, null, null, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.FAILURES_BEFORE_ROTATION; i++) {
            breaker.onLegFailure(sector);
        }
        String message = FrontierLegBreaker.blockedMessage(
                "frontier leg", 37, breaker, sector);
        check(message.contains("frontier legs"), "message names the leg kind");
        check(message.contains("no rotatable bearing"),
                "all-direction block says rotation was impossible");
        check(message.contains("may_alter_terrain"), "message offers the dig consent option");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

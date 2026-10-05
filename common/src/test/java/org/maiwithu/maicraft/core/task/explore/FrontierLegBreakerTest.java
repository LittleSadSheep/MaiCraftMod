package org.maiwithu.maicraft.core.task.explore;

import java.util.List;

/** 探索腿失败先小半径换锚再轮换方位，都耗尽才宣布受阻；触发看观察窗失败占比，不要求失败严格连续。 */
public final class FrontierLegBreakerTest {
    public static void main(String[] args) {
        escalationLadderRelocatesBeforeRotating();
        windowRatioTriggersEvenWhenOneLegArrives();
        sporadicFailuresKeepBearing();
        consecutiveSuccessStillResetsFailureBudget();
        exhaustedAfterRelocationsAndAllBearings();
        allDirectionSearchRelocatesThenBlocks();
        fullCircleSectorRelocatesThenBlocks();
        blockedMessageCarriesDecisionFacts();
        System.out.println("FrontierLegBreakerTest: passed");
    }

    // 升级链顺序：头两次触发换锚（先右后左 90°），之后每次触发轮换 45°，七次后耗尽。
    private static void escalationLadderRelocatesBeforeRotating() {
        var current = ExplorationSector.of("north", 20, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int relocation = 1; relocation <= FrontierLegBreaker.MAX_RELOCATIONS; relocation++) {
            var decision = burst(breaker, current);
            check(decision == FrontierLegBreaker.Decision.RELOCATED,
                    "escalation " + relocation + " should relocate first");
            check(Math.round(breaker.relocationBearing(current)) == (relocation == 1 ? 90 : 270),
                    "relocation bearing alternates perpendicular sides");
        }
        for (int rotation = 1; rotation <= FrontierLegBreaker.MAX_ROTATIONS; rotation++) {
            var decision = burst(breaker, current);
            check(decision == FrontierLegBreaker.Decision.ROTATED,
                    "escalation after relocations should rotate, rotation " + rotation);
            current = breaker.rotated(current);
        }
        check(breaker.rotatedBearings().equals(List.of(45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)),
                "rotated bearings recorded clockwise");
        check(breaker.anchorRelocationBearings().equals(List.of(90.0, 270.0)),
                "relocation bearings recorded as tried");
        var rotated = breaker.rotated(current);
        check(rotated.bearing() == current.bearing() + 45.0, "rotation shifts bearing by 45");
        check(rotated.request() == current.request(), "rotation keeps width and min distance");
        var narrow = ExplorationSector.of("north", 20, 0, 256).at(0, 0, 0);
        check(narrow.contains(0, -64) && !narrow.contains(64, -64), "original cone points north");
        check(breaker.rotated(narrow).contains(64, -64)
                && !breaker.rotated(narrow).contains(0, -64), "rotated cone points northeast");
    }

    // 093 活锁形态：五腿四败（其中一腿到达）在第五腿触发升级，不再因偶发到达永远清零。
    private static void windowRatioTriggersEvenWhenOneLegArrives() {
        var sector = ExplorationSector.of("north", 90, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < 3; i++) {
            check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "early failures stay below the window trigger");
        }
        breaker.onLegSuccess();
        check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.RELOCATED,
                "four failures in the last five attempts relocate the anchor");
        check(breaker.relocations() == 1, "relocation is counted");
        check(breaker.consecutiveFailures() == 0, "escalation restarts the failure budget");
    }

    private static void sporadicFailuresKeepBearing() {
        var sector = ExplorationSector.of("north", 90, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.ESCALATION_WINDOW - 1; i++) {
            check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "sporadic failures stay on the current bearing");
        }
        check(breaker.rotations() == 0 && breaker.relocations() == 0,
                "no escalation below the trigger ratio");
    }

    // 到达仍清零连续计数；但观察窗占比语义下，紧跟到达的四连败已满足五腿四败。
    private static void consecutiveSuccessStillResetsFailureBudget() {
        var sector = ExplorationSector.of("north", 90, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.ESCALATION_WINDOW; i++) {
            breaker.onLegFailure(sector);
        }
        breaker.onLegSuccess();
        check(breaker.consecutiveFailures() == 0, "arrival clears consecutive failures");
        for (int i = 0; i < FrontierLegBreaker.ESCALATION_FAILURES - 1; i++) {
            check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.KEEP_GOING,
                    "failures right after an arrival keep the ratio below the trigger");
        }
        check(breaker.onLegFailure(sector) == FrontierLegBreaker.Decision.RELOCATED,
                "four consecutive failures after an arrival fill the ratio trigger");
    }

    // 换锚耗尽后每个方位仍保有整窗腿的预算；七次轮换后第八窗失败宣布受阻。
    private static void exhaustedAfterRelocationsAndAllBearings() {
        var current = ExplorationSector.of("north", 20, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.MAX_RELOCATIONS + FrontierLegBreaker.MAX_ROTATIONS; i++) {
            burst(breaker, current);
            if (breaker.relocations() > 0 && breaker.relocations() <= FrontierLegBreaker.MAX_RELOCATIONS
                    && breaker.rotations() == 0) {
                continue; // 换锚阶段不换方位，扇区保持不变。
            }
            if (breaker.rotations() > 0) current = breaker.rotated(current);
        }
        check(breaker.rotations() == FrontierLegBreaker.MAX_ROTATIONS,
                "seven rotations cover all eight bearings");
        check(burst(breaker, current) == FrontierLegBreaker.Decision.EXHAUSTED,
                "all bearings exhausted, report blocked direction");
        String blocked = FrontierLegBreaker.blockedMessage(
                "frontier leg", 46, breaker, current);
        check(blocked.contains("315 degrees"), "message lists every rotated bearing");
        check(blocked.contains("Anchor relocations"), "message records the relocation history");
        check(blocked.contains("travel to open terrain"),
                "message advises travelling elsewhere when the launch terrain is the blocker");
        check(blocked.contains("may_alter_terrain"), "message offers the dig consent option");
    }

    private static void allDirectionSearchRelocatesThenBlocks() {
        var sector = ExplorationSector.of(null, null, null, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.MAX_RELOCATIONS; i++) {
            check(burst(breaker, sector) == FrontierLegBreaker.Decision.RELOCATED,
                    "all-direction search still relocates before giving up");
        }
        check(breaker.onRelocationLegFailed(sector) == FrontierLegBreaker.Decision.EXHAUSTED,
                "all-direction search has no bearing to rotate to after relocations");
    }

    private static void fullCircleSectorRelocatesThenBlocks() {
        var sector = ExplorationSector.of("north", 360, 0, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        for (int i = 0; i < FrontierLegBreaker.MAX_RELOCATIONS; i++) {
            check(burst(breaker, sector) == FrontierLegBreaker.Decision.RELOCATED,
                    "full-circle sector relocates first");
        }
        check(breaker.onRelocationLegFailed(sector) == FrontierLegBreaker.Decision.EXHAUSTED,
                "full-circle sector has no new bearing to rotate to");
    }

    private static void blockedMessageCarriesDecisionFacts() {
        var sector = ExplorationSector.of(null, null, null, 256).at(0, 0, 0);
        var breaker = new FrontierLegBreaker();
        burst(breaker, sector);
        String message = FrontierLegBreaker.blockedMessage(
                "frontier leg", 37, breaker, sector);
        check(message.contains("frontier legs"), "message names the leg kind");
        check(message.contains("Anchor relocations"), "relocation-only history still names the launch terrain");
        check(message.contains("travel to open terrain"),
                "all-direction block advises travelling elsewhere");
        check(message.contains("may_alter_terrain"), "message offers the dig consent option");
    }

    /** 灌满一整窗失败，返回窗满那一刻的升级处置。 */
    private static FrontierLegBreaker.Decision burst(FrontierLegBreaker breaker, ExplorationSector.Area area) {
        FrontierLegBreaker.Decision decision = FrontierLegBreaker.Decision.KEEP_GOING;
        for (int i = 0; i < FrontierLegBreaker.ESCALATION_WINDOW; i++) {
            decision = breaker.onLegFailure(area);
            if (decision != FrontierLegBreaker.Decision.KEEP_GOING) return decision;
        }
        return decision;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

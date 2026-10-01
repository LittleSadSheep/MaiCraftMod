package org.maiwithu.maicraft.core.task.mine;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/** 正式判定绝不能把未变化且已耗尽的搜索再次启动为新的 A*；超龄证据同样让位给重扫。 */
public final class NoPathVerdictTest {
    public static void main(String[] args) {
        Vec3 stance = new Vec3(-77.5, 115, -9.5);
        var goals = GoalCompiler.mineField(List.of(new BlockPos(-69, 114, 27)), List.of()).semanticFingerprint();
        var failed = new NoPathVerdict(stance, goals, "open set exhausted", 0);
        for (int tick = 0; tick < 1000; tick++) {
            check(failed.next(stance, goals, true, tick) == NoPathVerdict.Next.FAIL,
                    "complete unchanged evidence restarted the same failed search");
            check(failed.next(stance, goals, false, tick) == NoPathVerdict.Next.WAIT_FOR_QUERY,
                    "unfinished index triggered A* before producing different target evidence");
        }
        check(failed.next(stance, goals, true, 1200) == NoPathVerdict.Next.FAIL,
                "evidence at the freshness boundary was discarded too early");
        check(failed.next(stance, goals, true, 1201) == NoPathVerdict.Next.SEARCH,
                "stale evidence kept adjudicating after the world could have moved on");
        check(failed.next(stance, goals, true, -1) == NoPathVerdict.Next.SEARCH,
                "a backwards clock let a future verdict stand");
        check(failed.next(stance.add(0, -12, 0), goals, true, 0) == NoPathVerdict.Next.SEARCH,
                "moving downstairs did not permit a new search");
        check(failed.next(stance.add(0.3, 0, 0), goals, false, 0) == NoPathVerdict.Next.SEARCH,
                "a changed physical stance inside the same block was ignored");
        var moreGoals = GoalCompiler.mineField(List.of(new BlockPos(-69, 114, 27), new BlockPos(-78, 115, -8)), List.of())
                .semanticFingerprint();
        check(failed.next(stance, moreGoals, false, 0) == NoPathVerdict.Next.SEARCH,
                "newly discovered reachable candidates could not start a search");
        check(failed.next(stance, goals, true, 999) == NoPathVerdict.Next.FAIL,
                "completing an unchanged query must settle NO_PATH, not restart the planner");
        System.out.println("NoPathVerdictTest: passed");
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}

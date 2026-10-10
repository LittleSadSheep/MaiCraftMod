// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 靠近动作：原地可用就一步不走；核对不过换下一个候选；都不行按许可决定要不要补救，
 * 补救也不行就按到不了结束，问题里带着试过哪些站位、败在哪。
 */
class ApproachTest {

    private static final ReachRules REACH = new ReachRules(4.5, 3.0, 3, 1.62);
    private static final BlockPos 目标 = new BlockPos(3, 64, 0);

    private final StubWorld world = new StubWorld();
    private final StubWalkCost walking = new StubWalkCost();
    private final StubGuarded guarded = new StubGuarded();

    private Approach approach(StubMoves moves, StandOpener opener, Permissions permissions) {
        return new Approach(ApproachTarget.ofBlock(目标), REACH, world, walking, guarded,
                moves, opener, permissions);
    }

    @Test
    void 已经站在合适的位置就一步都不走() {
        world.feet = new BlockPos(2, 64, 0);
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(1)));
        assertEquals(0, moves.begun.size());
    }

    @Test
    void 走到候选站位核对通过就完成() {
        // 出发时还在跳跃中：原地核对不过，走过去落地之后才算到。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, ActionStatus.done());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        assertEquals(ActionStatus.progressed(), approach.tick(new StubTick(1)));
        assertEquals(ActionStatus.progressed(), approach.tick(new StubTick(2)));
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(3)));
        assertEquals(1, moves.begun.size());
    }

    @Test
    void 到了核对不过换下一个候选() {
        // 找候选时半边都看得见；真走过去之后挡板立起来了，目标西侧的候选全部作废，走到东侧才完成。
        world.grounded = false;
        AtomicInteger walked = new AtomicInteger();
        StubMoves moves = new StubMoves(world, ActionStatus.done(), ActionStatus.done(), ActionStatus.done());
        moves.onArrive = walked::incrementAndGet;
        world.hidden = (eye, target) -> walked.get() > 0 && eye.x() < 1.5;
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 30 && !(status instanceof ActionStatus.Done); tick++) {
            status = approach.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), status);
        assertTrue(moves.begun.size() > 1);
        assertTrue(approach.tried().size() > 1);
        assertTrue(approach.tried().stream().allMatch(attempt -> attempt.result().contains("看不见")));
    }

    @Test
    void 这条路走不通也换下一个候选() {
        world.grounded = false;
        StubMoves moves = new StubMoves(world,
                ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "前面是断崖")),
                ActionStatus.done());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        assertInstanceOf(ActionStatus.Running.class, approach.tick(new StubTick(3)));
        approach.tick(new StubTick(4));
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(5)));
        assertEquals(2, moves.begun.size());
        assertEquals(1, approach.tried().size());
    }

    @Test
    void 候选用尽且许可不许改方块就按到不了结束() {
        world.hidden = (eye, target) -> true;
        Approach approach = approach(new StubMoves(world), null,
                new Permissions(Permissions.BlockChanges.NONE, Permissions.Fight.HOSTILE_MOBS,
                        false, Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, null));
        approach.tick(new StubTick(1));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(2)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        // 被挡住但不许动方块：建议里把"允许挖开遮挡"说出来，让 LLM 能点头放行。
        assertTrue(failed.problem().suggestion().contains("挖开遮挡"));
    }

    @Test
    void 许可允许时用补救作最后手段() {
        world.hidden = (eye, target) -> true;
        StubOpener opener = new StubOpener(true);
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(moves, opener, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        assertEquals(ActionStatus.progressed(), approach.tick(new StubTick(2)));
        // 补救做完了，但挡板还在（世界替身里始终看不见）：如实按到不了结束，不再回头换站位。
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(3)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertEquals(1, opener.asked.size());
        assertEquals(0, moves.begun.size());
    }

    @Test
    void 补救做不成也如实按到不了结束() {
        world.hidden = (eye, target) -> true;
        StubOpener opener = new StubOpener(false);
        Approach approach = approach(new StubMoves(world), opener, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(2)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertEquals(1, opener.asked.size());
        // 没走过任何候选：问题里交代附近被拒的候选位置。
        assertTrue(failed.problem().message().contains("候选位置"));
    }

    @Test
    void 收尾时停下脚步并收掉补救动作() {
        world.hidden = (eye, target) -> true;
        StubOpener opener = new StubOpener(true);
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(moves, opener, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        approach.close();
        assertEquals(1, moves.stops);
    }

    @Test
    void 被打断时只暂停脚步_回来接着走同一个站位() {
        // 出发时还在跳跃中：原地核对不过，挑了站位在路上时被打断。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, ActionStatus.running(), ActionStatus.running());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        approach.pause();
        assertEquals(1, moves.pauses);
        assertEquals(0, moves.stops, "被打断不该把这一趟丢掉");
        approach.tick(new StubTick(3));
        assertEquals(1, moves.begun.size(), "回来接着走，不重新挑站位");
    }
}

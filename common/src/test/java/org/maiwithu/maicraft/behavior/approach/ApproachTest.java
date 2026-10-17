// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 靠近动作：原地可用就一步不走；核对不过换下一个候选；都不行按许可决定要不要补救，
 * 补救也不行就按到不了结束，问题里带着试过哪些站位、败在哪；走向站位途中能不能打断、先停稳都按走路说。
 */
class ApproachTest {

    private static final ReachRules REACH = new ReachRules(4.5, 3.0, 3, 1.62);
    private static final BlockPos 目标 = new BlockPos(3, 64, 0);

    private final StubWorld world = new StubWorld();
    private final StubWalkCost walking = new StubWalkCost();
    private final StubGuarded guarded = new StubGuarded();

    private Approach approach(StubMoves moves, StandOpener opener, Permissions permissions) {
        return approach(ApproachTarget.ofBlock(目标), moves, opener, permissions, null);
    }

    private Approach approach(ApproachTarget target, StubMoves moves, StandOpener opener,
            Permissions permissions, TaskRecords records) {
        return new Approach(target, REACH, world, walking, guarded,
                moves, opener, permissions, records);
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
    void 走向站位途中能不能打断与先停稳都按走路说() {
        // 悬空走向站位：能不能打断按走路说（停不得），先到安全处再停转给走路，落地前不报停稳；还没上路时现在就能停。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, ActionStatus.running(), ActionStatus.running());
        moves.airborne = true;
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        assertEquals(Interruptibility.WORKING, approach.interruptibility(), "还在看原地，正常干活");
        assertFalse(approach.stopSafely(), "还没上路，现在就能停");
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        approach.tick(new StubTick(3));
        assertEquals(1, moves.begun.size(), "走在去站位的路上");

        assertEquals(Interruptibility.UNSAFE_TO_STOP, approach.interruptibility(), "悬空走着停不得");
        assertTrue(approach.stopSafely(), "转给走路先落地");
        assertEquals(1, moves.safeStops, "走路收到先停稳");
        assertFalse(approach.stoppedSafely(), "还没落地");
        moves.airborne = false;
        assertTrue(approach.stoppedSafely(), "落地站稳了");
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
    void 寻路程序出错就不再换站位_照原样交上去() {
        // 实机里算路程序出错时，采集挨个试了 58 个站位，每个都错在同一处；程序出错换站位没用。
        world.grounded = false;
        Problem broken = Problem.of(Problem.Kind.INTERNAL_ERROR, "寻路程序出错，这次没算出路线，不是没有路：x");
        StubMoves moves = new StubMoves(world, ActionStatus.failed(broken), ActionStatus.done());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        ActionStatus status = approach.tick(new StubTick(3));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.INTERNAL_ERROR, failed.problem().kind());
        assertEquals(1, moves.begun.size(), "只走了第一个站位");
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
        // 一个位置救不了就换下一个只是被挡住的位置再问，但有上限，不把几百个被拒位置挨个算一遍。
        assertTrue(opener.asked.size() > 1 && opener.asked.size() <= 24, "问了 " + opener.asked.size() + " 个");
        assertTrue(opener.asked.stream().allMatch(spot -> spot.failedItem().equals("看不见")));
        // 没走过任何候选：问题里交代附近被拒的候选位置，各败在哪一项、最接近能用的是哪一个。
        assertTrue(failed.problem().message().contains("候选位置"));
        assertTrue(failed.problem().message().contains("最接近能用的是"), failed.problem().message());
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

    @Test
    void 要挖的方块在脚下下面是岩浆就换旁边一格() {
        BlockPos 目标格 = new BlockPos(3, 64, 0);
        world.feet = 目标格.above();
        world.lavaUnderneath.add(目标格.below());
        StubMoves moves = new StubMoves(world, ActionStatus.done());
        Approach approach = approach(ApproachTarget.ofBlockToDig(目标格), moves, null, Permissions.DEFAULT, null);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 10 && !(status instanceof ActionStatus.Done); tick++) {
            status = approach.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), status);
        // 挪去了旁边一格，没有留在要挖的那格正上方。
        assertEquals(1, moves.begun.size());
        assertNotEquals(目标格.above(), moves.begun.get(0));
    }

    @Test
    void 要挖的方块在脚下下面是实地就原地挖() {
        BlockPos 目标格 = new BlockPos(3, 64, 0);
        world.feet = 目标格.above();
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(ApproachTarget.ofBlockToDig(目标格), moves, null, Permissions.DEFAULT, null);
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(1)));
        assertEquals(0, moves.begun.size());
    }

    @Test
    void 要挖的方块在脚下旁边站不了就就地挖并交代下面是什么() {
        BlockPos 目标格 = new BlockPos(3, 64, 0);
        world.feet = 目标格.above();
        world.lavaUnderneath.add(目标格.below());
        // 附近所有格子的地面都抽掉（候选脚位最低到 59，它的地面在 58）：旁边一格都站不了。
        for (int x = -2; x <= 8; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 58; y <= 67; y++) {
                    world.noFloor.add(new BlockPos(x, y, z));
                }
            }
        }
        List<String> 记录 = new ArrayList<>();
        TaskRecords 记账 = new TaskRecords() {
            @Override public void change(Change change) { }
            @Override public void unconfirmed(Change change) { }
            @Override public void attempt(String tried, String whatHappened) {
                记录.add(tried + "：" + whatHappened);
            }
        };
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(ApproachTarget.ofBlockToDig(目标格), moves, null, Permissions.DEFAULT, 记账);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 10 && !(status instanceof ActionStatus.Done); tick++) {
            status = approach.tick(new StubTick(tick));
        }
        // 不算到不了：留在原地动手，下面是什么跟着任务结果走。
        assertEquals(ActionStatus.done(), status);
        assertEquals(0, moves.begun.size());
        assertEquals(1, 记录.size());
        assertTrue(记录.get(0).contains("岩浆"));
    }
}

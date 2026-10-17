// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.timer;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计时能力：只改计时表、当场完成，参数和操作对不上一次说全，挂满与取消不到如实说并列出挂着的。 */
class TimerAbilityTest {

    private final Timers timers = new Timers(TaskEventSink.NONE);
    private final TimerAbility ability = new TimerAbility(timers);

    /** 决定阶段用的上下文：只有目标。 */
    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    /** 本刻上下文替身：游戏时间固定在第 2000 刻。 */
    private static final TickContext AT_2000 = new TickContext() {
        @Override public long gameTick() {
            return 2000;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("计时不应碰到角色对象");
        }
    };

    private ParamValues params(String json) {
        ParseResult result = ability.spec().paramSpecs().parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private StepDecision decide(String json) {
        return ability.decide(step(new Goal("maicraft:timer", null, null, params(json), null, List.of(), null)));
    }

    /** 决定开出任务就推进到结束，计时真的挂上或摘下。 */
    private TaskResult run(String json) {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(json));
        TimerTask task = new TimerTask((TimerInput) run.input(), timers);
        task.start(AT_2000);
        return assertInstanceOf(TickResult.Finished.class, task.tick(AT_2000)).result();
    }

    @Test
    void specDoesNotControlThePlayer() {
        assertEquals("maicraft:timer", ability.spec().id());
        assertEquals(ExecutionMode.MEMORY_ONLY, ability.spec().mode());
        assertFalse(ability.spec().paramSpecs().parse(JsonParser.parseString("{\"after_seconds\":0}")
                .getAsJsonObject()).ok(), "0 秒不在范围里");
    }

    @Test
    void settingReturnsTheIdAndListsWhatIsPending() {
        TaskResult result = run("{\"after_seconds\":600,\"note\":\" 看炉子烧好没 \"}");
        assertEquals(TaskResult.Status.DONE, result.status());
        TimerTask.TimerDetails details = assertInstanceOf(TimerTask.TimerDetails.class, result.details());
        assertEquals(1, details.timerId());
        assertEquals("看炉子烧好没", details.note(), "备注去掉首尾空白");
        assertEquals(List.of(new TimerTask.PendingTimer(1, 600, "看炉子烧好没")), details.pending());
        assertEquals(2000 + 600 * 20, timers.pending().getFirst().dueTick(), "从设下那一刻的游戏时间起算");
    }

    @Test
    void mismatchedParametersAreAllReportedAtOnce() {
        // 小模型常见的写法：设计时忘了备注，又顺手带了 timer_id。
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decide("{\"after_seconds\":60,\"timer_id\":2}"));
        Problem problem = finish.result().problem();
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
        assertTrue(problem.message().contains("note") && problem.message().contains("timer_id"), problem.message());
        assertTrue(timers.pending().isEmpty(), "参数不对不该挂上");

        StepDecision.Finish longNote = assertInstanceOf(StepDecision.Finish.class,
                decide("{\"after_seconds\":60,\"note\":\"" + "很".repeat(TimerAbility.MAX_NOTE_CHARS + 1) + "\"}"));
        assertTrue(longNote.result().problem().message().contains("最多 " + TimerAbility.MAX_NOTE_CHARS + " 字"));
    }

    @Test
    void theNinthTimerIsRefusedWithThePendingList() {
        for (int i = 0; i < Timers.MAX_PENDING; i++) {
            run("{\"after_seconds\":" + (100 + i) + ",\"note\":\"第 " + i + " 件\"}");
        }
        TaskResult refused = run("{\"after_seconds\":5,\"note\":\"多出来的\"}");
        assertEquals(TaskResult.Status.FAILED, refused.status());
        assertEquals(Problem.Kind.UNSUPPORTED, refused.problem().kind());
        assertTrue(refused.problem().suggestion().contains("operation=cancel"));
        TimerTask.TimerDetails details = assertInstanceOf(TimerTask.TimerDetails.class, refused.details());
        assertEquals(Timers.MAX_PENDING, details.pending().size());
    }

    @Test
    void cancelRemovesAndAnUnknownIdSaysSo() {
        run("{\"after_seconds\":60,\"note\":\"收作物\"}");
        TaskResult cancelled = run("{\"operation\":\"cancel\",\"timer_id\":1}");
        assertEquals(TaskResult.Status.DONE, cancelled.status());
        assertTrue(cancelled.summary().contains("收作物"));
        assertTrue(timers.pending().isEmpty());

        TaskResult missing = run("{\"operation\":\"cancel\",\"timer_id\":1}");
        assertEquals(Problem.Kind.NOT_FOUND, missing.problem().kind());
    }

    @Test
    void listSaysHowManyArePending() {
        assertEquals("没有挂着的计时", run("{\"operation\":\"list\"}").summary());
        run("{\"after_seconds\":60,\"note\":\"收作物\"}");
        assertEquals("挂着 1 个计时", run("{\"operation\":\"list\"}").summary());
    }
}

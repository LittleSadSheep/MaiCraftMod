// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死亡恢复决策：死亡挂一次问题、选项按连接增减、回答消费问题、执行失败重新挂、
 * 迟到的重复答复不再落在消费掉的问题上、回到活体后下次死亡再挂新的。
 */
class DeathRecoveryTest {

    private static final DeathFacts FACTS = new DeathFacts(12, "minecraft:overworld", 10.5, -3.0, 20.25);

    private final TaskEventLog events = new TaskEventLog();
    private final DeathRecovery recovery = new DeathRecovery(events);

    @Test
    void deathHangsTheQuestionOnceWithFacts() {
        recovery.onDeath(FACTS, true);

        Question question = recovery.pendingQuestion();
        assertNotNull(question, "死亡要挂出问题");
        assertEquals(Question.Reason.CHOOSE_ONE, question.reason());
        assertTrue(question.text().contains("分数 12"), "问题带死亡事实：" + question.text());
        assertTrue(question.text().contains("minecraft:overworld"), "问题带维度：" + question.text());
        assertEquals(List.of("respawn", "spectate", "cancel_task"), optionIds(question));

        // 继续死着：同一个死亡过程不重复挂。
        recovery.onDeath(FACTS, true);
        assertSameQuestion(question);
    }

    @Test
    void spectateIsOnlyOfferedWhileConnected() {
        recovery.onDeath(FACTS, false);

        assertEquals(List.of("respawn", "cancel_task"), optionIds(recovery.pendingQuestion()),
                "连接不在时不提供切观战");
    }

    @Test
    void answeringConsumesTheQuestionAndExecutes() throws InterruptedException {
        recovery.onDeath(FACTS, true);
        long id = recovery.decisionRun().orElseThrow().id();

        assertEquals(DeathRecovery.Choice.RESPAWN, recovery.answer(id, "respawn"));
        assertNull(recovery.pendingQuestion(), "答复后问题已消费");

        // 请求发出去了：本轮了结，决策记录定下结果，事件流里有执行结果。
        recovery.applied(DeathRecovery.Choice.RESPAWN, true);
        GoalRun decision = recovery.decisionRun().orElseThrow();
        assertTrue(decision.result().summary().contains("重生"), decision.result().summary());
        assertEquals(TaskEvent.Kind.DEATH_RECOVERY_APPLIED, latestEvent().kind());
        assertEquals(id, latestEvent().goalRunId());
    }

    @Test
    void failedExecutionHangsTheSameQuestionAgain() {
        recovery.onDeath(FACTS, true);
        long id = recovery.decisionRun().orElseThrow().id();
        String text = recovery.pendingQuestion().text();
        recovery.answer(id, "respawn");

        // 请求没发出去：同一个问题按原文重新挂上，不吞答复。
        recovery.applied(DeathRecovery.Choice.RESPAWN, false);
        assertNotNull(recovery.pendingQuestion(), "失败要重新挂问题");
        assertEquals(text, recovery.pendingQuestion().text(), "重新挂的是同一个问题");
        assertEquals(id, recovery.decisionRun().orElseThrow().id(), "还是同一条决策记录");

        // 重挂之后再答一次，这次成了，本轮了结。
        assertEquals(DeathRecovery.Choice.SPECTATE, recovery.answer(id, "spectate"));
        recovery.applied(DeathRecovery.Choice.SPECTATE, true);
        assertNull(recovery.pendingQuestion());
    }

    @Test
    void lateDuplicateAnswerDoesNotLandOnTheConsumedQuestion() {
        recovery.onDeath(FACTS, true);
        long id = recovery.decisionRun().orElseThrow().id();
        recovery.answer(id, "cancel_task");
        recovery.applied(DeathRecovery.Choice.CANCEL_TASK, true);

        // 本轮还没回到活体（死亡屏幕留给人），决策记录还在，但问题已经消费掉：
        // 迟到的重复答复如实报错，不悄悄当没听见，也不落到别的问题上。
        assertThrows(GoalRunTable.WrongGoalRunState.class, () -> recovery.answer(id, "respawn"));

        // 别的编号照常按"不在表里"报，不与目标运行混淆。
        assertThrows(GoalRunTable.UnknownGoalRun.class, () -> recovery.answer(id - 1, "respawn"));
    }

    @Test
    void backAliveClearsThisRoundSoTheNextDeathHangsAFreshQuestion() {
        recovery.onDeath(FACTS, true);
        long firstId = recovery.decisionRun().orElseThrow().id();
        recovery.answer(firstId, "respawn");
        recovery.applied(DeathRecovery.Choice.RESPAWN, true);
        recovery.backAlive();

        assertFalse(recovery.owns(firstId), "本轮决策已经丢掉");
        recovery.onDeath(FACTS, true);
        long secondId = recovery.decisionRun().orElseThrow().id();
        assertTrue(secondId != firstId, "下次死亡是一条新的决策记录");
        assertNotNull(recovery.pendingQuestion());
    }

    @Test
    void cancelTaskChoiceReportsItself() {
        recovery.onDeath(FACTS, true);
        long id = recovery.decisionRun().orElseThrow().id();

        assertEquals(DeathRecovery.Choice.CANCEL_TASK, recovery.answer(id, "cancel_task"));
        recovery.applied(DeathRecovery.Choice.CANCEL_TASK, true);
        assertTrue(recovery.decisionRun().orElseThrow().result().summary().contains("取消"),
                "结果说清取消了任务");
    }

    @Test
    void questionIsVisibleOnTheEventStream() throws InterruptedException {
        recovery.onDeath(FACTS, true);

        TaskEvent event = latestEvent();
        assertEquals(TaskEvent.Kind.ASKED, event.kind());
        assertEquals(recovery.decisionRun().orElseThrow().id(), event.goalRunId());
        assertTrue(event.message().contains("角色死了"), event.message());
    }

    @Test
    void describeFollowsTheQuestionLifecycle() {
        // 挂着问题在等回答；答复并执行后，说明要说清已答复以及执行了什么——
        // 这是按编号查"此刻在做什么"时给 LLM 看的话。
        recovery.onDeath(FACTS, true);
        long id = recovery.decisionRun().orElseThrow().id();
        assertTrue(recovery.describe().contains("等回答"), recovery.describe());

        recovery.answer(id, "respawn");
        recovery.applied(DeathRecovery.Choice.RESPAWN, true);
        String described = recovery.describe();
        assertTrue(described.contains("已答复"), described);
        assertTrue(described.contains("重生"), described);
    }

    private List<String> optionIds(Question question) {
        return question.options().stream().map(Question.Option::id).toList();
    }

    private void assertSameQuestion(Question expected) {
        assertEquals(expected, recovery.pendingQuestion());
    }

    private TaskEvent latestEvent() throws InterruptedException {
        var page = events.read(null, 0, -1, 256, 0);
        return page.events().get(page.events().size() - 1);
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发话任务：先提交，回显到了算发成；等不到就按已提交但没能确认收场，不盲目重发。 */
class ChatTaskTest {

    /** 本刻上下文替身：刻号由测试推进。 */
    private static final class TestTick implements TickContext {
        private long gameTick = 1000;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("发话测试不应碰到角色对象");
        }

        void advance() {
            gameTick++;
        }
    }

    /** 记下交出去的话的发送替身。 */
    private static final class StubSender implements SendsChatMessage {
        final List<String> sent = new ArrayList<>();

        @Override public void send(String message) {
            sent.add(message);
        }
    }

    /** 回显替身：出现哪些话、哪些时刻出现过新行，由测试摆。 */
    private static final class StubEcho implements ReadsChatEcho {
        private final Set<String> echoed = new HashSet<>();
        private boolean lineAfter;

        void echo(String message) {
            echoed.add(message);
        }

        void feedbackLine() {
            lineAfter = true;
        }

        @Override public boolean appearsInChat(String message) {
            return echoed.contains(message);
        }

        @Override public boolean anyLineAfter(long sinceMillis) {
            return lineAfter;
        }
    }

    /** 推进到出结果为止；每刻之间推进一刻刻号。 */
    private static TaskResult runToFinish(ChatTask task, TestTick tick) {
        task.start(tick);
        for (int i = 0; i < 200; i++) {
            TickResult result = task.tick(tick);
            if (result instanceof TickResult.Finished finished) {
                return finished.result();
            }
            tick.advance();
        }
        throw new AssertionError("发话任务没有按时收场");
    }

    @Test
    void sendsThenConfirmsOnEcho() {
        StubSender sender = new StubSender();
        StubEcho echo = new StubEcho();
        TestTick tick = new TestTick();
        ChatTask task = new ChatTask("大家好", sender, echo);
        // 回显在第二刻出现：提交后立刻确认。
        echo.echo("大家好");

        TaskResult result = runToFinish(task, tick);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("大家好"), sender.sent);
        assertTrue(result.summary().contains("聊天栏"), result.summary());
        assertTrue(result.unconfirmed().isEmpty(), "确认到了就不留没能确认的交互");
    }

    @Test
    void missingEchoEndsAsUnconfirmed() {
        StubSender sender = new StubSender();
        StubEcho echo = new StubEcho();
        TestTick tick = new TestTick();
        ChatTask task = new ChatTask("在吗", sender, echo);

        TaskResult result = runToFinish(task, tick);

        assertEquals(List.of("在吗"), sender.sent, "话确实交出去了一次");
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1, result.unconfirmed().size());
        Change unconfirmed = result.unconfirmed().get(0);
        assertEquals("在吗", unconfirmed.what());
        assertTrue(result.summary().contains("没能确认"), result.summary());
    }

    @Test
    void commandConfirmsOnFeedbackLineInsteadOfEcho() {
        StubSender sender = new StubSender();
        StubEcho echo = new StubEcho();
        TestTick tick = new TestTick();
        ChatTask task = new ChatTask("/time set day", sender, echo);
        // 命令没有自己那条回显；提交后聊天栏冒出反馈行就算发成。
        echo.feedbackLine();

        TaskResult result = runToFinish(task, tick);

        assertEquals(List.of("/time set day"), sender.sent);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("命令反馈行"), result.summary());
        assertTrue(result.unconfirmed().isEmpty(), "看到了反馈行就不留没能确认的交互");
    }

    @Test
    void commandWithoutFeedbackLineEndsAsUnconfirmed() {
        StubSender sender = new StubSender();
        StubEcho echo = new StubEcho();
        TestTick tick = new TestTick();
        ChatTask task = new ChatTask("/give @s diamond", sender, echo);

        TaskResult result = runToFinish(task, tick);

        assertEquals(List.of("/give @s diamond"), sender.sent, "命令确实交出去了一次");
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1, result.unconfirmed().size());
        assertTrue(result.summary().contains("没能确认"), result.summary());
    }

    @Test
    void inputDescribesWhatWillBeSaid() {
        ChatInput input = new ChatInput("直播开始了");
        assertTrue(input.describe().contains("直播开始了"));
        ChatTask task = new ChatTask("直播开始了", new StubSender(), new StubEcho());
        assertTrue(task.describe().contains("说话"), task.describe());
    }
}

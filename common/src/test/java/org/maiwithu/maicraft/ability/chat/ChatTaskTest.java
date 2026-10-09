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
        /** 聊天通道接没接上：没接上时话交不出去。 */
        boolean connected = true;

        @Override public boolean send(String message) {
            if (!connected) return false;
            sent.add(message);
            return true;
        }
    }

    /** 回显替身：出现哪些话、哪些时刻出现过新行，由测试摆。 */
    private static final class StubEcho implements ReadsChatEcho {
        private final Set<String> echoed = new HashSet<>();
        private final List<String> lines = new ArrayList<>();
        /** 提交后服务器回的一行：记号一取走就冒出来，模拟反馈在命令发出之后才到。 */
        private String replyAfterSend;

        void echo(String message) {
            echoed.add(message);
        }

        void feedbackLine() {
            replyAfterSend = "Set the time to 1000";
        }

        void earlierLine(String line) {
            lines.add(line);
        }

        @Override public boolean appearsInChat(String message) {
            return echoed.contains(message);
        }

        @Override public long mark() {
            long mark = lines.size();
            if (replyAfterSend != null) {
                lines.add(replyAfterSend);
                replyAfterSend = null;
            }
            return mark;
        }

        @Override public List<String> shownSince(long mark) {
            return List.copyOf(lines.subList((int) mark, lines.size()));
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
        assertTrue(result.summary().contains("Set the time to 1000"), "服务器回的话原样写进结果");
        assertTrue(result.unconfirmed().isEmpty(), "看到了反馈行就不留没能确认的交互");
    }

    @Test
    void chatBeforeTheCommandIsNotTakenAsItsFeedback() {
        // 发命令之前聊天栏里已有的话不算这条命令的回话：没有新行就按没能确认收场。
        StubSender sender = new StubSender();
        StubEcho echo = new StubEcho();
        echo.earlierLine("<Steve> 早上好");
        TaskResult result = runToFinish(new ChatTask("/time set day", sender, echo), new TestTick());

        assertEquals(1, result.unconfirmed().size());
        assertTrue(!result.summary().contains("早上好"), result.summary());
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
        // 没等到反馈不等于没执行：原版对没有产生变化的命令本来就静默。收场的话要写明这一点，
        // 不说"失败"，免得消费方把静默的命令（如切到当前已是模式）当成执行失败。
        assertTrue(result.summary().contains("没反馈不代表没执行"), result.summary());
        assertTrue(!result.summary().contains("失败"), result.summary());
        assertTrue(result.unconfirmed().get(0).note().contains("没反馈不代表没执行"), result.unconfirmed().toString());
    }

    @Test
    void notHandedOverWhenTheChannelIsDownSaysSoInsteadOfWaiting() {
        // 聊天通道没接上：话根本没交出去，当场如实收场，不等回显，也不说"已交给游戏执行"。
        StubSender sender = new StubSender();
        sender.connected = false;
        TaskResult result = runToFinish(new ChatTask("/time set day", sender, new StubEcho()), new TestTick());

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(sender.sent.isEmpty());
        assertTrue(result.summary().contains("没有交出去"), result.summary());
        assertTrue(!result.summary().contains("已交给游戏执行"), result.summary());
    }

    @Test
    void inputDescribesWhatWillBeSaid() {
        ChatInput input = new ChatInput("直播开始了");
        assertTrue(input.describe().contains("直播开始了"));
        ChatTask task = new ChatTask("直播开始了", new StubSender(), new StubEcho());
        assertTrue(task.describe().contains("说话"), task.describe());
    }
}

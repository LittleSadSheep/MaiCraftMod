// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.ChatDraftScreen;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 逐字打字：到点打一个字、组合表情按一个字、打完停一下；暂停保字数、框被占等让位、让不出如实收场。 */
class TypeChatDraftTest {

    /** 本刻上下文替身：刻号由测试推进。 */
    private static final class TestTick implements TickContext {
        private long gameTick;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("打字测试不应碰到角色对象");
        }

        void advance() {
            gameTick++;
        }
    }

    /** 聊天框替身：记下放进框里的草稿；可以摆成被别的界面占着、或中途被换掉。 */
    private static final class StubDrafts implements ChatDraftScreen {
        final List<String> shown = new ArrayList<>();
        /** true 时 show 交 false：模拟框被别的界面占着。 */
        boolean occupied;
        private boolean open;

        @Override public boolean show(String draft) {
            if (occupied) return false;
            open = true;
            shown.add(draft);
            return true;
        }

        @Override public boolean showing() {
            return open;
        }

        @Override public void close() {
            open = false;
        }

        /** 框里此刻的字：最后一次放进去的草稿。 */
        String current() {
            return shown.isEmpty() ? "" : shown.get(shown.size() - 1);
        }

        /** 模拟框被换成了别的界面：自己那份不再显示。 */
        void stealBox() {
            open = false;
        }
    }

    private static boolean progressed(ActionStatus status) {
        return status instanceof ActionStatus.Running running && running.progressed();
    }

    @Test
    void typesOneCharacterEveryOtherTickThenHoldsBeforeFinishing() {
        StubDrafts drafts = new StubDrafts();
        TestTick tick = new TestTick();
        TypeChatDraft action = new TypeChatDraft(drafts, "大家好");

        // 刻 0 开框放空草稿；此后每隔一刻长出一个字，第 6 刻三个字都进框，再停 5 刻才算打完。
        assertTrue(progressed(action.tick(tick)), "开框就是进展");
        assertEquals("", drafts.current());
        tick.advance();
        assertEquals(ActionStatus.running(), action.tick(tick), "没到点不打字");
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("大", drafts.current());
        tick.advance();
        assertEquals(ActionStatus.running(), action.tick(tick));
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("大家", drafts.current());
        tick.advance();
        assertEquals(ActionStatus.running(), action.tick(tick));
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("大家好", drafts.current(), "整句打完");
        // 打完到收场之间停 5 刻：前 4 刻还在等，第 5 刻到了才算打完。
        for (int i = 0; i < TypeChatDraft.HOLD_AFTER_LAST_TICKS - 1; i++) {
            tick.advance();
            assertEquals(ActionStatus.running(), action.tick(tick), "停顿期间还没到打完");
        }
        tick.advance();
        assertEquals(ActionStatus.done(), action.tick(tick), "停顿过后才算打完");
    }

    @Test
    void combinedEmojiCountsAsOneCharacter() {
        StubDrafts drafts = new StubDrafts();
        TestTick tick = new TestTick();
        TypeChatDraft action = new TypeChatDraft(drafts, "👍!");

        action.tick(tick);
        tick.advance();
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("👍", drafts.current(), "组合表情按一个字放进框");
        tick.advance();
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("👍!", drafts.current());
        assertEquals(3, drafts.shown.size(), "两个字的话只放了三次草稿");
    }

    @Test
    void pauseClosesTheBoxAndKeepsTheTypedPart() {
        StubDrafts drafts = new StubDrafts();
        TestTick tick = new TestTick();
        TypeChatDraft action = new TypeChatDraft(drafts, "大家好");
        action.tick(tick);
        tick.advance();
        tick.advance();
        action.tick(tick);
        assertEquals("大", drafts.current());

        // 被生存需求打断：框交出去，已打的字留在这份动作里。
        action.pause();
        assertTrue(!drafts.showing(), "打断时框要交出去");

        // 回来接着打：先重开框、把已打的字放回去，再从原字数往下打。
        tick.advance();
        tick.advance();
        tick.advance();
        assertEquals(ActionStatus.running(), action.tick(tick), "框不在，先等一刻");
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("大", drafts.current(), "重开框从已打的字放起，不从头打");
        tick.advance();
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("大家", drafts.current(), "接着往下打");
    }

    @Test
    void occupiedBoxFailsAfterTheWaitLimitInsteadOfSending() {
        StubDrafts drafts = new StubDrafts();
        drafts.occupied = true;
        TestTick tick = new TestTick();
        TypeChatDraft action = new TypeChatDraft(drafts, "在吗");

        ActionStatus status = null;
        for (int i = 0; i < TypeChatDraft.BOX_WAIT_LIMIT_TICKS; i++) {
            status = action.tick(tick);
            tick.advance();
        }
        assertInstanceOf(ActionStatus.Failed.class, status);
        Problem problem = ((ActionStatus.Failed) status).problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        assertTrue(problem.message().contains("聊天框被别的界面占着"), problem.message());
        assertTrue(drafts.shown.isEmpty(), "框被占着时不往里放字");
        // 失败之后收尾照常交还：框不是自己的，交还动作不动别人的界面。
        action.close();
    }

    @Test
    void boxTakenAwayMidTypingWaitsAndResumesFromTheTypedPart() {
        StubDrafts drafts = new StubDrafts();
        TestTick tick = new TestTick();
        TypeChatDraft action = new TypeChatDraft(drafts, "大家好");
        action.tick(tick);
        tick.advance();
        tick.advance();
        action.tick(tick);
        assertEquals("大", drafts.current());

        // 框被换成了别的界面：到点也不抢，先等。
        drafts.stealBox();
        tick.advance();
        tick.advance();
        assertEquals(ActionStatus.running(), action.tick(tick), "框不在了不硬打，等它让出来");
        assertEquals(2, drafts.shown.size(), "框被占着时不往里放字");

        // 对方让出来了：从原字数接着打。
        tick.advance();
        assertTrue(progressed(action.tick(tick)));
        assertEquals("大", drafts.current(), "重开框从已打的字放起");
    }

    @Test
    void describeTellsWhatIsInTheBox() {
        StubDrafts drafts = new StubDrafts();
        TypeChatDraft action = new TypeChatDraft(drafts, "大家好");
        assertTrue(action.describe().contains("等聊天框"), action.describe());
        action.tick(new TestTick());
        assertTrue(action.describe().contains("逐字打字"), action.describe());
    }
}

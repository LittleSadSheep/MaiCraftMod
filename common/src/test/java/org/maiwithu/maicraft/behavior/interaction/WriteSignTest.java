// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 写告示牌动作：等界面、逐行输入、按完成、按真实文字确认。 */
class WriteSignTest {

    /** 替身编辑界面：按脚本决定界面何时打开、告示牌上最后是什么字。 */
    private static final class FakeEditor implements SignEditor {
        private int openAfterTicks;
        private final List<String> actual = new ArrayList<>();
        private final List<String> typed = new ArrayList<>();
        private boolean donePressed;
        private boolean reveal = true;

        FakeEditor openAfter(int ticks) {
            openAfterTicks = ticks;
            return this;
        }

        /** 让完成之后也读不到告示牌上的文字，模拟方块一直没同步。 */
        FakeEditor hidesText() {
            reveal = false;
            return this;
        }

        @Override public boolean editScreenOpen() {
            return openAfterTicks <= 0;
        }

        @Override public void typeLine(int lineIndex, String text) {
            typed.add(lineIndex + ":" + text);
            // 游戏的行为：每行有宽度上限，这里按 10 个字截，模拟超宽的行被截掉。
            while (actual.size() <= lineIndex) actual.add("");
            actual.set(lineIndex, text.length() > 10 ? text.substring(0, 10) : text);
        }

        @Override public void pressDone() {
            donePressed = true;
        }

        @Override public Optional<List<String>> frontText() {
            return donePressed && reveal ? Optional.of(List.copyOf(actual)) : Optional.empty();
        }
    }

    private static ActionStatus tick(WriteSign sign, int times) {
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < times; i++) {
            status = sign.tick(new StubTick(i));
            if (!(status instanceof ActionStatus.Running)) break;
        }
        return status;
    }

    @Test
    void typesLineByLineAndConfirmsActualText() {
        FakeEditor editor = new FakeEditor();
        WriteSign sign = new WriteSign(editor, List.of("你好", "第二行"));
        // 第一刻界面就开着：等待阶段一步走过，并报告了进展。
        assertTrue(sign.tick(new StubTick(0)) instanceof ActionStatus.Running);
        ActionStatus status = tick(sign, 10);
        assertTrue(status instanceof ActionStatus.Done);
        assertEquals(List.of("0:你好", "1:第二行"), editor.typed);
        assertTrue(editor.donePressed);
        WriteSign.Written written = sign.written().orElseThrow();
        assertEquals(List.of("你好", "第二行"), written.lines());
        assertTrue(written.missing().isEmpty());
    }

    @Test
    void truncatedLineIsReportedAsMissing() {
        FakeEditor editor = new FakeEditor();
        String longLine = "这一行远远超过了游戏一行能写下的宽度会被截断";
        WriteSign sign = new WriteSign(editor, List.of(longLine));
        ActionStatus status = tick(sign, 10);
        assertTrue(status instanceof ActionStatus.Done);
        WriteSign.Written written = sign.written().orElseThrow();
        // 实际写下的只有前 10 个字；请求的整行写进剩余，不冒充都写上了。
        assertEquals(longLine.substring(0, 10), written.lines().getFirst());
        assertEquals(List.of(longLine), written.missing());
    }

    @Test
    void waxedSignNeverOpensAndIsReportedAsGameRefusal() {
        FakeEditor editor = new FakeEditor().openAfter(Integer.MAX_VALUE);
        WriteSign sign = new WriteSign(editor, List.of("写字"));
        ActionStatus status = tick(sign, WriteSign.WAIT_LIMIT_TICKS + 2);
        assertTrue(status instanceof ActionStatus.Failed);
        assertEquals(Problem.Kind.REFUSED_BY_GAME, ((ActionStatus.Failed) status).problem().kind());
    }

    @Test
    void unreadableTextAfterDoneTimesOut() {
        FakeEditor editor = new FakeEditor().openAfter(0).hidesText();
        WriteSign sign = new WriteSign(editor, List.of("写字"));
        tick(sign, 5);
        // pressDone 已发生但文字一直读不到：到期限按说不清结束。
        ActionStatus status = tick(sign, WriteSign.WAIT_LIMIT_TICKS + 2);
        assertTrue(status instanceof ActionStatus.Failed);
        assertEquals(Problem.Kind.STUCK, ((ActionStatus.Failed) status).problem().kind());
    }
}

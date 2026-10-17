// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在告示牌上写字的动作：等编辑界面打开、逐行输入、按完成，再按告示牌上的真实文字确认写好没有。
 *
 * <p>编辑界面最多等六十刻；上过蜡的告示牌打不开，等不到界面就是游戏的真实拒绝，如实上报，
 * 不对着告示牌反复右键。每行有宽度上限，超出的字游戏会自己截掉，所以最后以告示牌上的实际文字为准：
 * 实际写下的逐行给出去，被截掉的部分单独列进剩余，不冒充都写上了。
 */
public final class WriteSign implements Action {

    /** 告示牌的行数，原版就是四行。 */
    public static final int MAX_LINES = 4;

    /** 右键之后等编辑界面打开的期限，也是按完成之后核对文字的期限。 */
    static final int WAIT_LIMIT_TICKS = 60;

    /** 写字的结果：告示牌上实际写下的字，以及请求了但没写上去的部分。 */
    public record Written(List<String> lines, List<String> missing) {
        public Written {
            lines = List.copyOf(lines);
            missing = List.copyOf(missing);
        }
    }

    /** 动作内部的推进：等界面 → 逐行输入 → 核对文字。 */
    private enum Stage { WAIT_SCREEN, TYPING, CONFIRM }

    private final SignEditor editor;
    private final List<String> requestedLines;
    private Stage stage = Stage.WAIT_SCREEN;
    private int waited;
    private int nextLine;
    private Written written;
    private Problem failure;

    /**
     * @param editor         告示牌编辑界面的接缝
     * @param requestedLines 要写的文字，逐行；超过四行的部分在能力参数校验就该拦下，这里只取前四行
     */
    public WriteSign(SignEditor editor, List<String> requestedLines) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.requestedLines = List.copyOf(Objects.requireNonNull(requestedLines, "requestedLines"));
        if (this.requestedLines.isEmpty()) throw new IllegalArgumentException("要写的文字不能为空");
    }

    /** 写完之后的结果；还没核对到文字时为空。 */
    public Optional<Written> written() {
        return Optional.ofNullable(written);
    }

    @Override public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case WAIT_SCREEN -> waitScreen();
            case TYPING -> typeNextLine();
            case CONFIRM -> confirmText();
        };
    }

    // 右键已提交，编辑界面还没递过来；等不到就承认打不开（上过蜡的告示牌就是这样）。
    private ActionStatus waitScreen() {
        if (editor.editScreenOpen()) {
            stage = Stage.TYPING;
            return ActionStatus.progressed();
        }
        if (++waited > WAIT_LIMIT_TICKS) {
            failure = Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "告示牌的编辑界面一直没有打开；上过蜡的告示牌写不了字", null);
            return ActionStatus.failed(failure);
        }
        return ActionStatus.running();
    }

    // 每刻输入一行；把整行交给游戏，超宽的部分游戏自己截，最后按实际文字核对。
    private ActionStatus typeNextLine() {
        if (nextLine < requestedLines.size() && nextLine < MAX_LINES) {
            editor.typeLine(nextLine, requestedLines.get(nextLine));
            nextLine++;
            return ActionStatus.progressed();
        }
        editor.pressDone();
        waited = 0;
        stage = Stage.CONFIRM;
        return ActionStatus.progressed();
    }

    // 按完成之后核对告示牌上的真实文字：一致才算写好，被截掉的部分如实列出来。
    private ActionStatus confirmText() {
        Optional<List<String>> actual = editor.frontText();
        if (actual.isEmpty()) {
            if (++waited > WAIT_LIMIT_TICKS) {
                failure = Problem.of(Problem.Kind.STUCK,
                        "按了完成但一直没能读到告示牌上的文字，写没写成说不清", null);
                return ActionStatus.failed(failure);
            }
            return ActionStatus.running();
        }
        written = compare(requestedLines, actual.get());
        return ActionStatus.done();
    }

    // 逐行对照：实际文字逐行给出去；请求了但实际没有（整行被截空）或缺了的行，写进剩余。
    private static Written compare(List<String> requested, List<String> actual) {
        List<String> writtenLines = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            String got = i < actual.size() ? actual.get(i) : "";
            writtenLines.add(got);
            if (!got.equals(requested.get(i))) missing.add(requested.get(i));
        }
        return new Written(writtenLines, missing);
    }

    @Override public String describe() {
        return switch (stage) {
            case WAIT_SCREEN -> "等告示牌的编辑界面打开";
            case TYPING -> "在告示牌上逐行写字";
            case CONFIRM -> "核对告示牌上的实际文字";
        };
    }
}

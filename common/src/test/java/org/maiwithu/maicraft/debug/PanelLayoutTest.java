// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.serverlink.ServerCapabilityState;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskProgress;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 调试面板排版：每种画面排出来是什么样，宽度和行数守不守得住。 */
class PanelLayoutTest {
    private static final long NOW = 10_000_000L;
    private static final int WIDTH = 280;
    /** 假字体：英文数字 6 像素，其余（中文、符号）9 像素，和游戏字体的比例差不多。 */
    private static final PanelLayout.TextWidth FONT = text -> text.codePoints().map(cp -> cp < 128 ? 6 : 9).sum();
    private final PanelLayout layout = new PanelLayout(FONT, ZoneOffset.UTC);

    private List<PanelLine> lay(PanelScene scene, PanelLevel level) {
        return lay(scene, level, PanelPage.NOW, 0);
    }

    // 现状在 secondsAgo 秒前第一次出现，此刻排版：用来造出"已等 40s"这类画面。
    private List<PanelLine> lay(PanelScene scene, PanelLevel level, PanelPage page, long secondsAgo) {
        StatusSnapshot snapshot = scene.snapshot();
        FirstSeenTimes times = new FirstSeenTimes();
        times.observe(snapshot, NOW - secondsAgo * 1000);
        return layout.lay(snapshot, times, NOW, level, page, level == PanelLevel.FULL ? 420 : WIDTH, 60);
    }

    private static String text(List<PanelLine> lines) {
        return lines.stream().map(PanelLine::text).collect(Collectors.joining("\n"));
    }

    // 折行只是排版：断言一句话在不在时，把折开的行接回去再找。
    private static String flat(List<PanelLine> lines) {
        return lines.stream().map(PanelLine::text).collect(Collectors.joining());
    }

    private static List<String> labels(List<PanelLine> lines) {
        return lines.stream().map(PanelLine::label).filter(label -> label != null).toList();
    }

    @Test
    void idlePanelIsOneQuietLine() {
        List<PanelLine> lines = lay(new PanelScene(), PanelLevel.BRIEF);

        assertEquals(1, lines.size());
        assertEquals("目标", lines.getFirst().label());
        assertEquals("空闲，等 LLM 下达目标", lines.getFirst().text());
    }

    @Test
    void longPurposeWrapsBelowTheShortFactsAndEndsWithEllipsis() {
        // LLM 写的一句话常常一行放不下：短字段一行在上，purpose 在下面最多两行，再长截断。
        String purpose = "做一把铁镐，下矿之前先把手上的石镐换掉，顺便把背包里多出来的圆石和泥土都存进家里的箱子，再回来接着挖，"
                + "挖到铁就回家熔掉做镐，做完了再去找钻石";
        List<PanelLine> lines = lay(new PanelScene().working("maicraft:obtain", purpose, "采掘：挖下一格铁矿"), PanelLevel.BRIEF);

        assertEquals("obtain · 1m00s", lines.get(0).text(), "第一行只有能力和做了多久");
        assertEquals(null, lines.get(1).label());
        assertTrue(lines.get(2).text().endsWith("…"), "purpose 折两行后截断");
        assertEquals("此刻", lines.get(3).label());
    }

    @Test
    void normalProgressShowsOnlyGoalAndNow() {
        List<PanelLine> lines = lay(new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘：挖下一格铁矿"),
                PanelLevel.BRIEF);

        assertEquals(List.of("目标", "此刻"), labels(lines), "一切正常时没有提醒行");
        assertEquals(PanelColor.DOING, lines.getLast().pieces().getFirst().color());
    }

    @Test
    void temporaryTaskOnTopIsNamedYellowAndTheMainTaskShowsBelowIt() {
        PanelScene scene = new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘：走到矿点")
                .interruptedBy("自卫", Urgency.SOON, "自卫：攻击，砍僵尸");

        PanelLine now = lay(scene, PanelLevel.BRIEF).stream().filter(line -> "此刻".equals(line.label())).findFirst().orElseThrow();
        String full = text(lay(scene, PanelLevel.FULL));

        assertEquals("自卫插进来（尽快处理）：攻击，砍僵尸", now.text(), "需求名只说一次");
        assertEquals(PanelColor.WAITING, now.pieces().getFirst().color());
        assertTrue(full.contains("自卫插进来（尽快处理）：攻击，砍僵尸\n└ 主任务等着：采掘：走到矿点"),
                "运行栈从上往下画，主任务缩进在下：\n" + full);
    }

    @Test
    void questionAndEveryOptionAreShownInFullAndTurnRedWhenNobodyIsListening() {
        String text = "要拆掉玩家盖的那面墙才过得去，墙后面是去仓库的唯一一条路，拆吗";
        PanelScene scene = new PanelScene().working("maicraft:travel", "回家", "出行：走到门口")
                .asking(text, new Question.Option("1", "拆掉那面墙"), new Question.Option("2", "绕远路"),
                        new Question.Option("3", "不去了"));
        scene.hostWaiting(NOW - 5_000);

        List<PanelLine> listening = lay(scene, PanelLevel.BRIEF, PanelPage.NOW, 40);
        scene.calls.clear();
        scene.calls.add(PanelScene.succeeded("events", NOW - 120_000));
        List<PanelLine> silent = lay(scene, PanelLevel.BRIEF, PanelPage.NOW, 40);

        String shown = text(listening).replace("\n", "");
        assertTrue(shown.contains(text), "问题不截断");
        assertTrue(shown.contains("1 拆掉那面墙 / 2 绕远路 / 3 不去了"), "选项不截断");
        assertTrue(shown.contains("40s"), "写明已等多久");
        assertTrue(listening.stream().noneMatch(line -> line.text().contains("…")));
        assertTrue(flat(silent).contains("宿主 2m00s 没来读事件"));
        assertEquals(PanelColor.PROBLEM, silent.stream().filter(line -> "在等".equals(line.label())).findFirst()
                .orElseThrow().pieces().getFirst().color());
    }

    @Test
    void f8DeathParkedAndPausedEachSayWhoWeAreWaitingFor() {
        PanelScene f8 = new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘");
        f8.control = new StatusSnapshot.Control(true, false, false, true, null);
        assertTrue(flat(lay(f8, PanelLevel.BRIEF)).contains("角色在玩家手上（F8），再按 F8 交回"));

        PanelScene dead = new PanelScene();
        dead.loopState = StatusSnapshot.LoopState.WAITING_RESPAWN;
        assertTrue(flat(lay(dead, PanelLevel.BRIEF, PanelPage.NOW, 30)).contains("角色死了，等重生 · 30s"));

        PanelScene parked = new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘");
        parked.loopState = StatusSnapshot.LoopState.PARKED;
        parked.parkedWhere = "回不到打点 (12, 64, -3)";
        assertTrue(flat(lay(parked, PanelLevel.BRIEF)).contains("主任务停在半路：回不到打点 (12, 64, -3)，等 LLM 换活"));

        PanelScene paused = new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘");
        paused.main = PanelScene.goal(12, "maicraft:obtain", "做一把铁镐", GoalRunState.PAUSED, null);
        assertTrue(flat(lay(paused, PanelLevel.BRIEF, PanelPage.NOW, 180)).contains("已暂停，等 LLM 恢复 · 3m00s"));
        assertFalse(labels(lay(paused, PanelLevel.BRIEF)).contains("此刻"), "暂停时不另写此刻，免得说两遍");
    }

    @Test
    void nearlyStuckAppearsOnlyPastHalfTheStuckLimit() {
        PanelScene calm = new PanelScene().working("maicraft:sleep", "天黑了", "睡觉：走到床边");
        calm.progress = new TaskProgress("睡觉：走到床边", "走到床边", List.of(), "走到床边", 200, 600, 900, 12_000);
        PanelScene slow = new PanelScene().working("maicraft:sleep", "天黑了", "睡觉：走到床边");
        slow.progress = new TaskProgress("睡觉：走到床边", "走到床边", List.of(), "走到床边", 500, 600, 900, 12_000);

        assertFalse(text(lay(calm, PanelLevel.BRIEF)).contains("没有新进展"));
        assertTrue(flat(lay(slow, PanelLevel.BRIEF)).contains("自「走到床边」之后 25s 没有新进展，30s 算卡住"));
    }

    @Test
    void extraRemindersCollapseIntoOneLine() {
        PanelScene scene = new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘");
        scene.parkedWhere = "回不到打点";
        scene.loopState = StatusSnapshot.LoopState.PARKED;
        scene.heldBack = PanelScene.held("饥饿", Urgency.LATER, Interruptibility.UNSAFE_TO_STOP);
        scene.progress = new TaskProgress("采掘", "采掘", List.of(), "挖下一格", 500, 600, 900, 12_000);
        scene.performance = new StatusSnapshot.Performance(80, 50);
        scene.calls.add(PanelScene.failed("execute", NOW - 3_000, "goal.parameters.count", "count 必须是 1 到 256 的整数"));
        scene.calls.add(PanelScene.failed("execute", NOW - 2_000, "goal.parameters.count", "count 必须是 1 到 256 的整数"));

        List<PanelLine> lines = lay(scene, PanelLevel.BRIEF);

        assertTrue(flat(lines).endsWith("还有 1 条，按 F9 看详细"), text(lines));
    }

    @Test
    void widthStaysFixedAndNoLineRunsPastIt() {
        PanelScene scene = new PanelScene().working("maicraft:obtain", "短", "采掘：挖下一格铁矿，挖完把掉出来的铁矿捡起来再去下一格");
        for (PanelLine line : lay(scene, PanelLevel.BRIEF)) {
            int used = line.pieces().stream().mapToInt(piece -> FONT.width(piece.text())).sum();
            assertTrue(line.valueX() + used <= WIDTH, "超出宽度：" + line.text());
        }
        assertEquals(DebugPanel.width(PanelLevel.BRIEF, 960), DebugPanel.width(PanelLevel.BRIEF, 960));
        assertEquals(280, DebugPanel.width(PanelLevel.BRIEF, 960), "简要档三成宽，封顶 280");
        assertEquals(420, DebugPanel.width(PanelLevel.FULL, 960), "详细档四成半宽，封顶 420");
    }

    @Test
    void punctuationNeverStartsALine() {
        // 中文排版的常规：折行时逗号不挤到下一行行首，把前一个字一起带下去。
        PanelScene scene = new PanelScene().working("maicraft:travel", "回家睡觉", "出行");
        scene.heldBack = PanelScene.held("饥饿", Urgency.LATER, Interruptibility.UNSAFE_TO_STOP);

        for (PanelLine line : lay(scene, PanelLevel.BRIEF)) {
            assertFalse(!line.text().isEmpty() && PanelLayout.NO_LINE_START.indexOf(line.text().charAt(0)) >= 0,
                    "标点出现在行首：" + line.text());
        }
    }

    @Test
    void readFailureShowsOneLineSayingThePanelItselfFailed() {
        List<PanelLine> lines = layout.failure(new IllegalStateException("坏了"), WIDTH);

        assertEquals("面板读取出错：IllegalStateException", text(lines));
    }

    @Test
    void serverLinkSaysNothingOnceTheHandshakeIsDone() {
        PanelScene fine = new PanelScene();
        PanelScene missing = new PanelScene();
        missing.serverLink = new StatusSnapshot.ServerLink(ServerCapabilityState.State.NEGOTIATING, "awaiting_welcome",
                false, true);

        assertFalse(labels(lay(fine, PanelLevel.FULL)).contains("服务端"));
        assertTrue(flat(lay(missing, PanelLevel.BRIEF)).contains("服务端迟迟没握手，服务器多半没装 MaiCraft"));
    }

    @Test
    void timelineMergesRepeatedCallsAndShowsTheCallBeforeTheEventItCaused() {
        PanelScene scene = new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘");
        for (int i = 0; i < 4; i++) scene.calls.add(PanelScene.succeeded("events", NOW - 100_000 + i * 1000));
        scene.calls.add(PanelScene.failed("execute", NOW - 60_000, "goal.parameters.count", "count 必须是 1 到 256 的整数"));
        scene.events.add(new TaskEvent(1, TaskEvent.Kind.STARTED, 12, "开始做 obtain", null));
        scene.calls.add(PanelScene.succeeded("execute", NOW - 30_000));

        String shown = flat(lay(scene, PanelLevel.FULL, PanelPage.NOW, 30));

        assertTrue(shown.contains("→ events ×4 成功"), shown);
        // 折行处的空格不带到下一行行首，比对时不看空格。
        assertTrue(shown.replace(" ", "").contains("→execute报错invalid_parametergoal.parameters.count：count必须是1到256的整数"), shown);
        assertTrue(shown.indexOf("→ execute 成功") < shown.indexOf("开始 #12"), "先有调用，才有它引起的事件");
    }

    @Test
    void recentGoalsPageListsShortFactsFirst() {
        PanelScene scene = new PanelScene();
        scene.recent.add(PanelScene.goal(11, "maicraft:travel", "回家", GoalRunState.FINISHED,
                TaskResult.failed("没到家", Problem.of(Problem.Kind.UNREACHABLE, "试过的路线都不通"))));

        List<PanelLine> lines = lay(scene, PanelLevel.FULL, PanelPage.RECENT_GOALS, 300);

        assertEquals("最近的目标 · 共 1 个 · F9+H 返回", lines.get(0).text());
        assertEquals("#11 没做成 · travel · 用时 10s · 5m00s 前 · 到不了，试过的路线都不通", lines.get(1).text());
        assertEquals(PanelColor.PROBLEM, lines.get(1).pieces().getFirst().color());
    }
}

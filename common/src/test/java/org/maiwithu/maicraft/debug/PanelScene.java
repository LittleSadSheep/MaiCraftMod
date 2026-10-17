// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.game.serverlink.ServerCapabilityState;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskProgress;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 面板测试用的现场：默认一切正常——MCP 在监听、一个宿主连着、服务端握好了手、角色在世界里由自动化操作、
 * 手上没有目标。各测试只改自己关心的那一处，再交给排版。
 */
final class PanelScene {
    long gameTick = 2_000;
    boolean mcpListening = true;
    int hosts = 1;
    StatusSnapshot.ServerLink serverLink = new StatusSnapshot.ServerLink(ServerCapabilityState.State.READY, "", true, false);
    StatusSnapshot.Control control = new StatusSnapshot.Control(true, true, true, false, null);
    StatusSnapshot.Performance performance = new StatusSnapshot.Performance(10, 50);
    StatusSnapshot.GoalLine main;
    Question question;
    Question deathQuestion;
    List<StatusSnapshot.GoalLine> recent = new ArrayList<>();
    StatusSnapshot.LoopState loopState = StatusSnapshot.LoopState.IDLE;
    List<StatusSnapshot.Layer> layers = new ArrayList<>();
    TaskProgress progress;
    StatusSnapshot.HeldBack heldBack;
    List<StatusSnapshot.RetryWait> retryWaits = new ArrayList<>();
    String parkedWhere;
    List<TaskEvent> events = new ArrayList<>();
    List<StatusSnapshot.ToolCall> calls = new ArrayList<>();

    /** 手上有一个正在推进的目标，角色正按它干活。 */
    PanelScene working(String ability, String purpose, String doing) {
        main = goal(12, ability, purpose, GoalRunState.RUNNING, null);
        recent.add(main);
        loopState = StatusSnapshot.LoopState.WORKING;
        layers.add(new StatusSnapshot.Layer(null, null, doing, false));
        progress = new TaskProgress(doing, "采掘", List.of(), "挖下一格铁矿", 60, 600, 2_200, 12_000);
        return this;
    }

    /** 一个生存需求插进来，压在主任务上面。 */
    PanelScene interruptedBy(String need, Urgency urgency, String doing) {
        layers.add(new StatusSnapshot.Layer(need, urgency, doing, false));
        progress = new TaskProgress(doing, "攻击", List.of(), "砍中僵尸", 20, 600, 100, Long.MAX_VALUE);
        return this;
    }

    /** 手上的目标在等 LLM 回答。 */
    PanelScene asking(String text, Question.Option... options) {
        main = goal(main.id(), main.ability(), main.purpose(), GoalRunState.AWAITING_ANSWER, null);
        recent.set(0, main);
        question = new Question(Question.Reason.NEED_APPROVAL, text, List.of(options));
        return this;
    }

    /** 宿主正挂着 events 等新事件，说明它还在听。 */
    PanelScene hostWaiting(long startedAtMillis) {
        calls.add(new StatusSnapshot.ToolCall("events", "{\"wait_ms\":30000}", startedAtMillis, true, null, null, null));
        return this;
    }

    static StatusSnapshot.GoalLine goal(long id, String ability, String purpose, GoalRunState state, TaskResult result) {
        return new StatusSnapshot.GoalLine(id, ability, purpose, null, state, 0, 0, 800,
                state == GoalRunState.FINISHED ? 1_000 : -1, result);
    }

    StatusSnapshot snapshot() {
        StatusSnapshot.Connection connection = new StatusSnapshot.Connection(mcpListening, mcpListening ? 8766 : -1,
                mcpListening ? null : "端口 8766 被占用", hosts, serverLink, control, performance);
        StatusSnapshot.Goals goals = new StatusSnapshot.Goals(main, question, List.of(), deathQuestion, recent);
        StatusSnapshot.Loop loop = new StatusSnapshot.Loop(loopState, layers, progress, heldBack, retryWaits, parkedWhere);
        return new StatusSnapshot(gameTick, connection, goals, loop, events, calls);
    }

    /** 宿主在 60 秒内调用过、此刻手上也在等，用来说明"宿主在听"的默认情形。 */
    static StatusSnapshot.ToolCall succeeded(String tool, long atMillis) {
        return new StatusSnapshot.ToolCall(tool, "{}", atMillis, false, null, null, null);
    }

    static StatusSnapshot.ToolCall failed(String tool, long atMillis, String field, String message) {
        return new StatusSnapshot.ToolCall(tool, "{}", atMillis, false, "invalid_parameter", field, message);
    }

    static StatusSnapshot.HeldBack held(String need, Urgency urgency, Interruptibility current) {
        return new StatusSnapshot.HeldBack(need, urgency, current);
    }
}

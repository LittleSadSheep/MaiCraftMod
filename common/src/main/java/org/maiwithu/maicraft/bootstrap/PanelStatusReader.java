// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.maiwithu.maicraft.debug.StatusReader;
import org.maiwithu.maicraft.debug.StatusSnapshot;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.serverlink.ServerCapabilityState;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskProgress;
import org.maiwithu.maicraft.mcp.tool.RecentToolCalls;
import org.maiwithu.maicraft.mcp.transport.EmbeddedMcpService;

/**
 * 调试面板的现状读口：每刻从 MCP 入口、和服务端的会话、控制权、目标运行表、控制循环与任务事件里
 * 抄一份不可变的现状交给面板。只读，不调用任何会改变状态的方法；只在客户端线程调用。
 */
final class PanelStatusReader implements StatusReader {
    /** 面板时间线最多显示的任务事件条数。 */
    private static final int RECENT_EVENTS = 12;

    private final Supplier<EmbeddedMcpService> mcp;
    private final Supplier<String> mcpStartFailure;
    private final RecentToolCalls recentCalls;
    private final ServerLinkSession session;
    private final PlayerControlBoundary playerControl;
    private final GoalRunTable goals;
    private final ControlLoop controlLoop;
    private final TaskEventLog taskEvents;

    PanelStatusReader(Supplier<EmbeddedMcpService> mcp, Supplier<String> mcpStartFailure, RecentToolCalls recentCalls,
                      ServerLinkSession session, PlayerControlBoundary playerControl, GoalRunTable goals,
                      ControlLoop controlLoop, TaskEventLog taskEvents) {
        this.mcp = mcp;
        this.mcpStartFailure = mcpStartFailure;
        this.recentCalls = recentCalls;
        this.session = session;
        this.playerControl = playerControl;
        this.goals = goals;
        this.controlLoop = controlLoop;
        this.taskEvents = taskEvents;
    }

    @Override public StatusSnapshot read(Minecraft minecraft) {
        boolean inWorld = minecraft.player != null && minecraft.level != null;
        long gameTick = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        StatusSnapshot.Control control = control(inWorld);
        return new StatusSnapshot(gameTick, connection(minecraft, control), goals(),
                loop(minecraft, control, gameTick), events(), calls());
    }

    private StatusSnapshot.Connection connection(Minecraft minecraft, StatusSnapshot.Control control) {
        EmbeddedMcpService service = mcp.get();
        boolean listening = service != null && service.isRunning();
        ServerCapabilityState capabilities = session.capabilities();
        StatusSnapshot.ServerLink link = new StatusSnapshot.ServerLink(capabilities.state(), capabilities.reason(),
                session.serverConfirmed(), session.confirmationExpired());
        // 只有集成服务端（单人或局域网）测得到每刻耗时；连外部服务器时客户端无从采样，如实留空。
        IntegratedServer server = minecraft.getSingleplayerServer();
        StatusSnapshot.Performance performance = server == null ? null : new StatusSnapshot.Performance(
                server.getAverageTickTimeNanos() / 1_000_000.0, server.tickRateManager().millisecondsPerTick());
        return new StatusSnapshot.Connection(listening, listening ? service.port() : -1, mcpStartFailure.get(),
                listening ? service.sessionCount() : 0, link, control, performance);
    }

    private StatusSnapshot.Control control(boolean inWorld) {
        boolean owns = playerControl.input().automationOwnsControls();
        boolean requested = playerControl.automationControlRequested();
        String reason = requested && !owns ? "已经要了控制权，角色的输入还没接上（下一刻生效）" : null;
        return new StatusSnapshot.Control(inWorld, owns, requested, playerControl.input().humanTookOver(), reason);
    }

    private StatusSnapshot.Goals goals() {
        GoalRun main = goals.mainGoal().orElse(null);
        Question question = main == null ? null : goals.pendingQuestion(main.id()).orElse(null);
        List<StatusSnapshot.GoalLine> recent = new ArrayList<>();
        Question deathQuestion = null;
        for (GoalRun run : goals.recent()) {
            if (run.parentRunId() != GoalRun.NO_PARENT) continue;
            recent.add(line(run));
            // 死亡恢复决策的编号是负数：它挂着问题时单独交给面板，和目标的提问分开说。
            if (run.id() < 0 && run.unfinished()) deathQuestion = goals.pendingQuestion(run.id()).orElse(null);
        }
        return new StatusSnapshot.Goals(main == null ? null : line(main), question,
                main == null ? List.of() : permissionChanges(main.goal().permissions()), deathQuestion, recent);
    }

    private static StatusSnapshot.GoalLine line(GoalRun run) {
        return new StatusSnapshot.GoalLine(run.id(), run.goal().ability(), run.goal().purpose(), run.goal().target(),
                run.state(), run.stepIndex(), run.goal().steps().size(), run.startedTick(), run.finishedTick(),
                run.result());
    }

    // 和默认许可不同的几项，用 MCP 的字段写法：面板上看到的和 LLM 发来的是同一个名字。
    private static List<String> permissionChanges(Permissions permissions) {
        Permissions normal = Permissions.DEFAULT;
        List<String> changes = new ArrayList<>();
        if (permissions.changeBlocks() != normal.changeBlocks()) changes.add("change_blocks=" + wire(permissions.changeBlocks()));
        if (permissions.fight() != normal.fight()) changes.add("fight=" + wire(permissions.fight()));
        if (permissions.useRareItems() != normal.useRareItems()) changes.add("use_rare_items=" + permissions.useRareItems());
        if (permissions.killAnimals() != normal.killAnimals()) changes.add("kill_animals=" + wire(permissions.killAnimals()));
        if (permissions.survivalNeeds() != normal.survivalNeeds()) changes.add("survival_needs=" + wire(permissions.survivalNeeds()));
        if (!permissions.protectedLandmarks().isEmpty()) {
            changes.add("protected_landmarks=" + String.join("、", permissions.protectedLandmarks()));
        }
        return changes;
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private StatusSnapshot.Loop loop(Minecraft minecraft, StatusSnapshot.Control control, long gameTick) {
        List<ControlLoop.Layer> layers = controlLoop.layers();
        List<StatusSnapshot.Layer> lines = new ArrayList<>(layers.size());
        for (ControlLoop.Layer layer : layers) {
            lines.add(new StatusSnapshot.Layer(layer.needName(), layer.urgency(), doing(layer.task()), layer.parked()));
        }
        TaskProgress progress = layers.isEmpty() ? null : layers.getLast().task().currentProgress().orElse(null);
        ControlLoop.HeldBackNeed held = controlLoop.heldBackNeed();
        StatusSnapshot.HeldBack heldBack = held == null ? null
                : new StatusSnapshot.HeldBack(held.needName(), held.urgency(), controlLoop.askedInterruptibility());
        List<StatusSnapshot.RetryWait> waits = new ArrayList<>();
        for (ControlLoop.RetryWait wait : controlLoop.retryWaits()) {
            waits.add(new StatusSnapshot.RetryWait(wait.needName(), Math.max(0, wait.untilTick() - gameTick) / 20,
                    wait.failures(), wait.why()));
        }
        return new StatusSnapshot.Loop(loopState(minecraft, control, layers), lines, progress, heldBack, waits,
                controlLoop.parkedWhere());
    }

    // 控制循环此刻在干什么，从运行栈和角色是否死亡推出来：不在世界、不在自动化手上、等重生、停在半路、空闲、在做。
    private static StatusSnapshot.LoopState loopState(Minecraft minecraft, StatusSnapshot.Control control,
                                                      List<ControlLoop.Layer> layers) {
        if (!control.inWorld()) return StatusSnapshot.LoopState.NOT_IN_WORLD;
        if (minecraft.player.isDeadOrDying()) return StatusSnapshot.LoopState.WAITING_RESPAWN;
        if (!control.automationOwns()) return StatusSnapshot.LoopState.NOT_AUTOMATED;
        if (layers.isEmpty()) return StatusSnapshot.LoopState.IDLE;
        if (layers.size() == 1 && layers.getFirst().parked()) return StatusSnapshot.LoopState.PARKED;
        return StatusSnapshot.LoopState.WORKING;
    }

    // 一层任务说的话：能取到最深一层（真正在干活的那个任务）的说法就用它，免得前面套着"目标 obtain："这类重复前缀。
    private static String doing(Task task) {
        return task.currentProgress().map(TaskProgress::doing).orElseGet(task::describe);
    }

    private List<TaskEvent> events() {
        try {
            // 不带游标读：只要最近几条，不推进任何读者（宿主、LLM）的游标。
            return taskEvents.read(null, 0, OptionalLong.empty(), RECENT_EVENTS, 0).events();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private List<StatusSnapshot.ToolCall> calls() {
        List<StatusSnapshot.ToolCall> calls = new ArrayList<>();
        for (RecentToolCalls.Call call : recentCalls.recent()) {
            calls.add(new StatusSnapshot.ToolCall(call.tool(), call.arguments(), call.startedAtMillis(), call.running(),
                    call.errorCode(), call.errorField(), call.errorMessage()));
        }
        return calls;
    }
}

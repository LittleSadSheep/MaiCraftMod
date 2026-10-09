// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.List;
import java.util.Objects;
import org.maiwithu.maicraft.game.serverlink.ServerCapabilityState;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskProgress;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 一刻的现状：调试面板要显示的全部事实，由启动层每刻从 MCP 入口、和服务端的会话、控制权、目标运行表、
 * 控制循环与任务事件里抄一份交进来。全是不可变的值，面板拿着它改不了任何东西。
 *
 * @param gameTick   这一刻的游戏刻；目标做了多久按目标记下的开始刻算
 * @param connection MCP、服务端、控制权与性能
 * @param goals      目标：手上的、在等的问题、最近的
 * @param loop       控制循环：运行栈、此刻任务的进展、生存需求
 * @param events     最近的任务事件，先发生的在前
 * @param calls      最近的工具调用，先进来的在前
 */
public record StatusSnapshot(long gameTick, Connection connection, Goals goals, Loop loop,
                             List<TaskEvent> events, List<ToolCall> calls) {

    public StatusSnapshot {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(goals, "goals");
        Objects.requireNonNull(loop, "loop");
        events = List.copyOf(events);
        calls = List.copyOf(calls);
    }

    /**
     * 连接。
     *
     * @param mcpListening    MCP 入口有没有在监听
     * @param mcpPort         监听的端口；没在监听时为 -1
     * @param mcpStartFailure MCP 入口启动失败的原因；启动成功时为 null
     * @param hosts           已连上的宿主（MCP 会话）有几个
     * @param serverLink      和服务端 MaiCraft 的握手
     * @param control         谁在操作角色
     * @param performance     集成服务端的性能；连外部服务器时测不了，为 null
     */
    public record Connection(boolean mcpListening, int mcpPort, String mcpStartFailure, int hosts,
                             ServerLink serverLink, Control control, Performance performance) {
        public Connection {
            Objects.requireNonNull(serverLink, "serverLink");
            Objects.requireNonNull(control, "control");
        }
    }

    /**
     * 和服务端 MaiCraft 的握手。
     *
     * @param state     握手到哪一步
     * @param reason    没握手好的原因；握手好了为空串
     * @param confirmed 当前连接已经完成握手，服务端的确认信息作数
     * @param expired   服务端迟迟没完成握手，多半没装 MaiCraft
     */
    public record ServerLink(ServerCapabilityState.State state, String reason, boolean confirmed, boolean expired) {
        public ServerLink {
            Objects.requireNonNull(state, "state");
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * 谁在操作角色。
     *
     * @param inWorld             角色在不在世界里
     * @param automationOwns      自动化此刻拿着角色
     * @param automationRequested 自动化要了控制权（可能还没拿到）
     * @param humanTookOver       人按 F8 收回了角色，要等人再按 F8 交回
     * @param unavailableReason   自动化拿不到角色的原因；拿得到时为 null
     */
    public record Control(boolean inWorld, boolean automationOwns, boolean automationRequested,
                          boolean humanTookOver, String unavailableReason) {}

    /**
     * 集成服务端的性能。
     *
     * @param msPerTick       最近一百刻平均每刻耗时（毫秒）
     * @param targetMsPerTick 每刻的预算（毫秒），默认 50
     */
    public record Performance(double msPerTick, double targetMsPerTick) {}

    /**
     * 目标。
     *
     * @param main              手上的目标；没有时为 null
     * @param question          手上的目标在等回答的问题；不在等时为 null
     * @param permissionChanges 手上的目标和默认许可不同的几项，用 MCP 的字段写法，例如 change_blocks=any
     * @param deathQuestion     角色死后挂出的死亡恢复问题；没挂时为 null
     * @param recent            最近的顶层目标，最近下达的在前
     */
    public record Goals(GoalLine main, Question question, List<String> permissionChanges,
                        Question deathQuestion, List<GoalLine> recent) {
        public Goals {
            permissionChanges = List.copyOf(permissionChanges);
            recent = List.copyOf(recent);
        }
    }

    /**
     * 一个目标。
     *
     * @param id           目标运行的编号
     * @param ability      能力 ID
     * @param purpose      LLM 写的为什么做这件事；没写时为 null
     * @param target       目标对象；没给时为 null
     * @param state        处境
     * @param step         sequence 正在做第几步（从 0 数）
     * @param steps        sequence 一共几步；不是 sequence 时为 0
     * @param startedTick  开始推进的游戏刻；还没开始时为 -1
     * @param finishedTick 结束的游戏刻；还没结束时为 -1
     * @param result       结束时的结果；还没结束时为 null
     */
    public record GoalLine(long id, String ability, String purpose, Target target, GoalRunState state,
                           int step, int steps, long startedTick, long finishedTick, TaskResult result) {
        public GoalLine {
            Objects.requireNonNull(ability, "ability");
            Objects.requireNonNull(state, "state");
        }
    }

    /** 控制循环此刻在干什么。 */
    public enum LoopState {
        /** 角色不在世界里。 */
        NOT_IN_WORLD,
        /** 角色不在自动化手上，控制循环不推进。 */
        NOT_AUTOMATED,
        /** 手上没有任务，也没有生存需求要处理。 */
        IDLE,
        /** 推进了一个任务。 */
        WORKING,
        /** 主任务被临时任务停在了半路，等 LLM 换活。 */
        PARKED,
        /** 角色死了，等重生。 */
        WAITING_RESPAWN
    }

    /**
     * 控制循环。
     *
     * @param state       此刻在干什么
     * @param layers      运行栈，从下往上（最底是主任务，最上是此刻在推进的）
     * @param progress    此刻在推进的那个任务的进展；没有可说的进展时为 null
     * @param heldBack    本刻想插进来、却被打断规则按住的生存需求；没有时为 null
     * @param retryWaits  临时任务没做成、正在等着再试的生存需求
     * @param parkedWhere 主任务停在半路时停在了哪里；没停时为 null
     */
    public record Loop(LoopState state, List<Layer> layers, TaskProgress progress, HeldBack heldBack,
                       List<RetryWait> retryWaits, String parkedWhere) {
        public Loop {
            Objects.requireNonNull(state, "state");
            layers = List.copyOf(layers);
            retryWaits = List.copyOf(retryWaits);
        }
    }

    /**
     * 运行栈里的一层。
     *
     * @param needName 插进这一层的生存需求；主任务为 null
     * @param urgency  插进来时多急；主任务为 null
     * @param doing    这一层的任务说的一句话；能取到最深一层的说法时用最深一层的
     * @param parked   主任务被停在了半路
     */
    public record Layer(String needName, Urgency urgency, String doing, boolean parked) {
        public Layer {
            Objects.requireNonNull(doing, "doing");
        }
    }

    /**
     * 想插进来、却被打断规则按住的生存需求。
     *
     * @param needName 需求的名字
     * @param urgency  此刻多急
     * @param current  手上的任务此刻能不能打断；为什么插不进来就看它
     */
    public record HeldBack(String needName, Urgency urgency, Interruptibility current) {}

    /**
     * 临时任务没做成、正在等着再试的生存需求。
     *
     * @param needName    需求的名字
     * @param secondsLeft 还要等几秒
     * @param failures    已接连没做成几次
     * @param why         上次为什么没做成
     */
    public record RetryWait(String needName, long secondsLeft, int failures, String why) {}

    /**
     * 一次工具调用。
     *
     * @param tool            工具名
     * @param arguments       原样压缩成一行的参数
     * @param startedAtMillis 进来的现实时刻
     * @param running         还没结束（例如 events 正挂着等）
     * @param errorCode       出错时的错误码；成功或还没结束时为 null
     * @param errorField      出错时写错的字段；不是参数问题时为 null
     * @param errorMessage    出错时的说明
     */
    public record ToolCall(String tool, String arguments, long startedAtMillis, boolean running,
                           String errorCode, String errorField, String errorMessage) {
        /** 已经结束且成功。 */
        public boolean succeeded() {
            return !running && errorCode == null;
        }
    }
}

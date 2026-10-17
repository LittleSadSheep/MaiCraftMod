// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.KeepsTempTaskFacts;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 控制循环：每刻决定角色听谁的，并推进那个任务一刻。
 *
 * <p>角色同一时刻只听一个任务。栈底是主任务，上面是生存需求插进来的临时任务；临时任务做完就弹出，
 * 被打断的任务从原地接着做（不会再 start），它的进度跟踪也因为没有被推进而天然不计时。
 * 每一层都经子任务运行器推进：任务抛异常变成程序错误的结果，不会把控制循环一起带崩。
 *
 * <p>每刻的顺序：
 * <ol>
 *   <li>逐个问各生存需求此刻有多急（不需要处理的返回 null，不参与）；</li>
 *   <li>问手上的任务此刻能不能被打断；</li>
 *   <li>按打断规则挑出最急又能插进来的需求：能插就先暂停手上的任务、创建并推进它的临时任务；
 *       不能插就忍住，手上的任务照常推进，被按住的需求留给调用方去提醒；</li>
 *   <li>被推进的任务这一刻走到了结局，就收尾并弹出，下一刻轮到栈里下面的任务恢复。</li>
 * </ol>
 *
 * <p>同样急的几个需求按登记顺序，先登记的先插。某个需求自己的临时任务还在栈里（正在推进或等着恢复）时，
 * 不再为它创建新的临时任务——它的事已经有人在办了。临时任务没做成而处境没有更急时，
 * 这个需求先缓一阵再插：自救没成功就每刻重来一遍，只会让主任务永远动不了，也解决不了问题。
 * 主任务被临时任务停在了半路时只等生存需求先救命，活不接着干，等 LLM 明确换活或解除暂停。
 *
 * <p>不急（SOON）的需求碰上一直停下不安全的任务（飞着、开着车，连着两秒以上）时不干等：先叫手上的任务到安全的地方
 * （就近落地、刹停），它停稳了能打断了，需求照常插进来；收尾期间需求没了或缓成 LATER，就叫它照原样接着做。
 * 一会儿就过去的停不下（坐下、迈上船沿、悬空放一格）照常等它自己过去。
 *
 * <p>临时任务之间也分先后：正在办的临时任务只让给更急的需求，同样急时只让给登记在它前面的需求。
 * 自卫打到一半不会被"天黑了"抢去找床（实机这样被僵尸追着打死）；反过来找床时挨打照样先自卫。
 */
public final class ControlLoop {
    private static final Logger LOG = LoggerFactory.getLogger(ControlLoop.class);

    /**
     * 临时任务没做成后，同一个需求在同样急（或更缓）时先等多少刻再插进来：五秒。
     * 类别：玩家常识——自救没成功就先缓一缓，处境变了再试；处境变得更急则马上再试。
     */
    static final long RETRY_AFTER_FAILED_TICKS = 100;

    /** 接连没做成时等待逐次加倍，最长五分钟：弄不到吃的这类一时解决不了的事，不每隔几秒就打断一次手上的活。 */
    static final long MAX_RETRY_AFTER_FAILED_TICKS = 20L * 60 * 5;

    /** 手上的任务连着停下不安全多久（刻），不急的需求才叫它先到安全的地方：两秒，一会儿就过去的不打扰。 */
    static final long ASK_SAFE_STOP_AFTER_TICKS = 40;

    private final List<SurvivalNeed> needs;
    /** 角色死亡这类循环自身的处境变化从这里告诉宿主。 */
    private final TaskEventSink events;
    /** 死亡决策的挂载对象：停摆的第一刻把死亡事实交给它去挂「死亡恢复」问题。 */
    private final DeathDecisionHost deathDecisions;
    /** 上一刻是否已在死亡停摆里；只在本刻刚看到死亡时收尾插着的任务、发一次事件。 */
    private boolean waitingRespawn;
    /** 上一刻看到的重生次数；还没看过为 -1。 */
    private long seenRespawns = -1;
    /** 主任务被停在半路时的一句话说明；没被停过为 null。 */
    private String parkedWhere;
    /** 从底到顶的运行栈；最底是主任务，上面是插进来的临时任务。只在客户端线程读写。 */
    private final List<Frame> stack = new ArrayList<>();
    /** 临时任务刚没做成的需求：在这一刻之前、且不比当时更急，就先不插。 */
    private final Map<SurvivalNeed, Backoff> backoffs = new HashMap<>();
    /** 各需求接连没做成的次数；处境解除或做成一次就清零。 */
    private final Map<SurvivalNeed, Integer> failureStreaks = new HashMap<>();
    /** 判断时抛过异常的需求；同一个需求的异常只记一次日志，免得每刻刷屏。 */
    private final Set<SurvivalNeed> brokenNeeds = new HashSet<>();
    /** 本刻问到的手上任务能不能打断；本刻没问（手上没任务、停在半路、等重生）时为 null。只给面板看。 */
    private Interruptibility askedInterruptibility;
    /** 本刻想插进来、却被打断规则按住或正等着再试的需求；没有时为 null。只给面板看。 */
    private HeldBackNeed heldBackNeed;
    /** 上一次推进的是哪个任务：换了一件事做时先叫换手收尾。 */
    private Task lastAdvanced;
    /** 换一件事做、新的那件推进之前叫一次（关掉上一件留下的界面）；没接为空操作。 */
    private Consumer<TickContext> onSwitch = context -> {};

    /**
     * 换一件事做（插进临时任务、做完回到下面那件、换了主任务）时、新的那件推进之前叫一次：
     * 生产里关掉上一件留下的工作台、过滤器界面，像人先按 Esc 再干别的。
     */
    public void onTaskSwitch(Consumer<TickContext> hook) {
        this.onSwitch = Objects.requireNonNull(hook, "hook");
    }

    /** 各生存需求在启动时登记一次；顺序决定同样急时谁先插进来。 */
    public ControlLoop(List<SurvivalNeed> needs) {
        this(needs, TaskEventSink.NONE);
    }

    /**
     * @param events 角色死亡这类循环自身的处境变化从这里告诉宿主；测试没接事件流时用空实现。
     */
    public ControlLoop(List<SurvivalNeed> needs, TaskEventSink events) {
        this(needs, events, DeathDecisionHost.NONE);
    }

    /**
     * @param events         角色死亡这类循环自身的处境变化从这里告诉宿主；测试没接事件流时用空实现。
     * @param deathDecisions 死亡决策的挂载对象；没接时死亡照常停摆，只是不挂「死亡恢复」问题。
     */
    public ControlLoop(List<SurvivalNeed> needs, TaskEventSink events, DeathDecisionHost deathDecisions) {
        this.needs = List.copyOf(needs);
        this.events = Objects.requireNonNull(events, "events");
        this.deathDecisions = Objects.requireNonNull(deathDecisions, "deathDecisions");
    }

    /**
     * 换主任务：LLM 派了新活时调用。旧的主任务按"被替换"收尾，返回它交代的结果（没有旧主任务时为 null），
     * 调用方把它记进任务存储，已经发生的事不会因为换活而消失。已经插进来的临时任务不受影响，做完仍会弹出。
     * 新的主任务在第一次被推进时才 start。
     */
    public TaskResult setMainTask(Task task) {
        Objects.requireNonNull(task, "task");
        TaskResult replaced = endMain(CloseReason.REPLACED);
        stack.add(0, new Frame(task, null, null));
        return replaced;
    }

    /**
     * 结束主任务（LLM 取消、角色离开世界）：按给定原因收尾并返回它交代的结果；没有主任务时为 null。
     * 已经插进来的临时任务不受影响。
     */
    public TaskResult endMainTask(CloseReason reason) {
        return endMain(reason);
    }

    /**
     * 角色离开世界：插着的临时任务按"角色不在了"收尾弹出；主任务只从循环里撤下、不替它收尾——
     * 目标运行表已经让它停手并存成暂停，下次进这个世界时恢复。之前缓着的需求与停在半路的说明一并清掉。
     */
    public void leaveWorld() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Frame frame = stack.remove(i);
            if (frame.from != null) {
                frame.close(CloseReason.PLAYER_GONE);
            }
        }
        backoffs.clear();
        brokenNeeds.clear();
        parkedWhere = null;
        waitingRespawn = false;
    }

    // 主任务的许可关掉了生存需求（寻死这类明确的事）：一个生存需求都不插，临时任务也不建。
    private boolean survivalNeedsOff() {
        if (stack.isEmpty()) return false;
        Frame bottom = stack.get(0);
        return bottom.from == null && bottom.task instanceof SurvivalNeedsOff off && off.survivalNeedsOff();
    }

    /** 本刻正在被推进（或被压着等待恢复）的任务里最上面的那个；没有任务时为 null。 */
    public Task currentTask() {
        return stack.isEmpty() ? null : top().task;
    }

    /** 运行栈每一层此刻的样子，从下往上（最底是主任务，最上是此刻在推进的）：给调试面板看角色听谁的。 */
    public List<Layer> layers() {
        List<Layer> layers = new ArrayList<>(stack.size());
        for (Frame frame : stack) {
            layers.add(new Layer(frame.task, frame.from == null ? null : frame.from.name(), frame.urgency, frame.paused));
        }
        return List.copyOf(layers);
    }

    /**
     * LLM 让停在半路的主任务接着做（恢复目标）：撤掉"停在半路"，下一刻照常推进。没停着时什么都不做。
     */
    public void unparkMain() {
        if (!stack.isEmpty() && stack.getFirst().from == null && stack.getFirst().paused) {
            stack.getFirst().paused = false;
            parkedWhere = null;
        }
    }

    /** 主任务被临时任务停在半路时说停在了哪里；没停时为 null。 */
    public String parkedWhere() {
        return parkedWhere;
    }

    /** 临时任务没做成、正在等着再试的需求，按登记顺序。 */
    public List<RetryWait> retryWaits() {
        List<RetryWait> waits = new ArrayList<>();
        for (SurvivalNeed need : needs) {
            Backoff backoff = backoffs.get(need);
            if (backoff != null) {
                waits.add(new RetryWait(need.name(), backoff.urgency(), backoff.untilTick(),
                        failureStreaks.getOrDefault(need, 0), backoff.why()));
            }
        }
        return List.copyOf(waits);
    }

    /** 本刻问到的手上任务此刻能不能打断；被按住的需求为什么插不进来就看它。本刻没问时为 null。 */
    public Interruptibility askedInterruptibility() {
        return askedInterruptibility;
    }

    /** 本刻想插进来、却被打断规则按住或正等着再试的那个最急的需求；没有时为 null。 */
    public HeldBackNeed heldBackNeed() {
        return heldBackNeed;
    }

    /** 推进一刻。 */
    public Decision tick(TickContext context) {
        askedInterruptibility = null;
        heldBackNeed = null;
        // 角色死了（血量见底、死亡界面）循环就停摆：不再插生存需求的临时任务、不推进任务，
        // 也不把死亡当成"处境不急"继续谎报。重生后（角色对象换掉、活着）从这里自然恢复。
        PlayerContext player = context.player();
        if (player != null && player.isDeadOrDying()) {
            return waitForRespawn(context);
        }
        if (player != null) {
            // 重生次数变了却没经过死亡停摆：服务器开着立即重生，死亡落在两刻之间。
            long respawns = player.respawns();
            boolean missedDeath = seenRespawns >= 0 && respawns != seenRespawns && !waitingRespawn;
            seenRespawns = respawns;
            if (missedDeath) return diedBetweenTicks(context);
        }
        if (waitingRespawn) {
            // 从死亡停摆里走出来（重生或以别的方式回到活体）：本轮死亡决策了结，下次死亡再挂新的。
            waitingRespawn = false;
            deathDecisions.characterAliveAgain();
        }
        if (stack.isEmpty()) {
            // 手上没有任务，没有东西要保护：任何生存需求此刻都能插进来，找空当的也不例外。
            Choice choice = choose(context, Interruptibility.BETWEEN_ACTIONS, null, null);
            return choice.chosen == null ? Decision.IDLE : takeOver(choice, context);
        }
        Frame current = top();
        // 主任务被停在了半路：只等生存需求先救命，活不接着干；建不出救命任务就继续停着。
        if (current.paused) {
            for (SurvivalNeed need : survivalNeedsOff() ? List.<SurvivalNeed>of() : needs) {
                if (hasLiveTask(need)) continue;
                Urgency urgency = urgencyOf(need, context, null);
                // 停着时也按没做成的等待来：救不成的需求不每刻重建一遍。
                if (urgency == null || heldBack(need, urgency, context)) continue;
                Task rescue;
                try {
                    rescue = need.createTask(context);
                } catch (RuntimeException exception) {
                    LOG.warn("生存需求「{}」创建临时任务时出错", need.name(), exception);
                    holdBack(need, urgency, context, "创建临时任务时程序出错：" + exception.getClass().getSimpleName());
                    continue;
                }
                stack.add(new Frame(rescue, need, urgency));
                return advance(top(), context, null);
            }
            return new Decision.Parked(current.task, parkedWhere == null ? "位置不明" : parkedWhere);
        }
        askedInterruptibility = current.interruptibility(context);
        // 记下从哪一刻起一直停下不安全：一会儿就过去的不叫它收尾。
        if (askedInterruptibility != Interruptibility.UNSAFE_TO_STOP) {
            current.unsafeSince = Long.MIN_VALUE;
        } else if (current.unsafeSince == Long.MIN_VALUE) {
            current.unsafeSince = context.gameTick();
        }
        // 需求看此刻真正在做事的那一层（目标外壳里的能力任务），才认得出它自己管灯、自己在打仗。
        Choice choice = choose(context, askedInterruptibility, current.task.activeTask(), current);
        if (choice.chosen != null) {
            // 能走到这里，说明打断规则已经放行：NOW 本来就不管停不停得下，SOON 与 LATER 只在停下安全时才选得上。
            LOG.info("{}被{}打断（{}），插进临时任务", current.task.describe(), choice.chosen.name(), choice.urgency);
            current.stopAsked = false;
            current.pause();
            return takeOver(choice, context);
        }
        // 没有人能插进来：忍住，手上的任务继续；被按住的需求交给调用方提醒，也留给面板说清是谁在等。
        if (choice.deferred != null) heldBackNeed = new HeldBackNeed(choice.deferred.name(), choice.deferredUrgency);
        windDownFor(current, choice, context);
        return advance(current, context, choice.deferred);
    }

    // 不急的需求只因为手上的任务一直停下不安全才插不进来：叫它先到安全的地方再停，停稳了下一刻需求就插得进来。
    // 正在等着再试的需求不算（它本来就插不进来，不值得为它落地）；需求没了或缓成 LATER，就叫它照原样接着做。
    private void windDownFor(Frame current, Choice choice, TickContext context) {
        boolean wanted = choice.deferred != null && choice.deferredUrgency == Urgency.SOON
                && !backoffs.containsKey(choice.deferred);
        boolean longUnsafe = askedInterruptibility == Interruptibility.UNSAFE_TO_STOP
                && context.gameTick() - current.unsafeSince >= ASK_SAFE_STOP_AFTER_TICKS;
        if (wanted && !current.stopAsked && longUnsafe) {
            current.stopAsked = current.stopSafely();
            if (current.stopAsked) {
                LOG.info("{}要{}，先到安全的地方再停：{}", choice.deferred.name(), choice.deferredUrgency, current.task.describe());
            }
        } else if (!wanted && current.stopAsked) {
            LOG.info("等着插进来的需求没了，{}照原样接着做", current.task.describe());
            current.stopAsked = false;
            current.pause();
        }
    }

    /**
     * 服务器开着立即重生：死亡和重生落在两刻之间，死亡界面一刻都没出现。插着的临时任务照样按"角色没了"收尾——
     * 找床、自卫的打点都是死前那个地方的事，带到重生点只会接着找死前那片地方的床；主任务停一下，下一刻从新位置接着做。
     * 不挂死亡决策：已经重生了，没有可选的。原先看不到这次死亡，重生后还按死前的现场"缺床"去杀羊。
     */
    private Decision diedBetweenTicks(TickContext context) {
        endTempTasks();
        backoffs.clear();
        failureStreaks.clear();
        PlayerContext body = context.player();
        DeathFacts facts = body.deathFacts();
        String cause = facts == null || facts.cause() == null ? "" : "（" + facts.cause() + "）";
        String where = facts == null ? "" : String.format("，已经在 %.0f, %.0f, %.0f 重生", facts.x(), facts.y(), facts.z());
        String main = stack.isEmpty() ? "手上没有主任务"
                : "它下的目标从重生的地方接着做：" + stack.getFirst().task.describe();
        events.publish(TaskEvent.Kind.CHARACTER_DIED,
                "角色死了" + cause + where + "（服务器立即重生）；插着的任务已结束，" + main);
        LOG.info("角色死亡并立即重生：插着的临时任务收尾");
        return new Decision.WaitingRespawn(stack.isEmpty() ? null : stack.getFirst().task);
    }

    // 角色没了：插着的临时任务按"角色不在了"收尾弹出，主任务停手（不收尾）。
    private void endTempTasks() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Frame frame = stack.get(i);
            if (frame.from != null) {
                stack.remove(i);
                frame.close(CloseReason.PLAYER_GONE);
            }
        }
        // 主任务停在原地：先停手上的动作，别让上一刻的输入继续压在一个死掉的身体上。
        if (!stack.isEmpty()) {
            stack.getFirst().pause();
        }
    }

    /**
     * 死亡停摆：只在看到死亡的第一刻收尾与发事件，之后每刻如实回答"还在等重生"。
     * 插着的临时任务按"角色没了"收尾——已经发生的消耗留在它们交代的结果里；
     * 主任务不收尾，重生后从原地接着做。
     */
    private Decision waitForRespawn(TickContext context) {
        if (!waitingRespawn) {
            waitingRespawn = true;
            endTempTasks();
            // 死亡是 LLM 该拿主意的事：把现场事实交给挂决策的一方，连接还在才提供切观战这个选项。
            PlayerContext body = context.player();
            DeathFacts facts = body == null ? null : body.deathFacts();
            // 死因照死亡界面上那句原话带上（被谁、怎么死的），LLM 才知道下回该防什么。
            String cause = facts == null || facts.cause() == null ? "" : "（" + facts.cause() + "）";
            // 主任务是谁带着：对面按编号记账，只说"主任务"它对不上是哪条目标停了。
            String main = stack.isEmpty() ? "手上没有主任务"
                    : "它下的目标停在原地：" + stack.getFirst().task.describe();
            events.publish(TaskEvent.Kind.CHARACTER_DIED,
                    "角色死了" + cause + "，等重生；" + main + "，重生后接着做");
            deathDecisions.characterDied(facts, body != null && body.connection() != null);
            LOG.info("角色死亡：控制循环停摆，等重生");
        } else {
            // 还死着：到点没人选，普通世界直接点重生（像真人看一眼死亡界面就点），掉的东西还来得及捡。
            deathDecisions.stillDead(context.gameTick());
        }
        return new Decision.WaitingRespawn(stack.isEmpty() ? null : stack.getFirst().task);
    }

    /**
     * 按打断规则挑出这一刻最急又能插进来的需求，同时记下最急的那个被按住的需求。
     * 手上是临时任务时，排在它后面的需求不参与（见 {@link #outranks}）。
     */
    private Choice choose(TickContext context, Interruptibility interruptibility, Task currentTask, Frame top) {
        Choice choice = new Choice();
        if (survivalNeedsOff()) {
            return choice;
        }
        for (SurvivalNeed need : needs) {
            if (hasLiveTask(need)) continue;
            // 需求可以看到此刻被推进的任务：要不要让位给主任务由需求自己结合处境判断。
            Urgency urgency = urgencyOf(need, context, currentTask);
            if (urgency == null) {
                // 处境已经不需要处理：之前没做成留下的等待一并作废，下次需要时马上能插。
                backoffs.remove(need);
                failureStreaks.remove(need);
                continue;
            }
            if (!outranks(need, urgency, top)) continue;
            boolean waiting = heldBack(need, urgency, context);
            if (!waiting && InterruptRule.canInterrupt(urgency, interruptibility)) {
                // 同样急的先来先得：只有真的更急才顶掉前面选中的。
                if (choice.chosen == null || urgency.ordinal() > choice.urgency.ordinal()) {
                    choice.chosen = need;
                    choice.urgency = urgency;
                }
            } else if (choice.deferred == null || urgency.ordinal() > choice.deferredUrgency.ordinal()) {
                choice.deferred = need;
                choice.deferredUrgency = urgency;
            }
        }
        return choice;
    }

    /** 创建这个需求的临时任务压到栈顶并推进它；创建就出错时这个需求先缓一阵，本刻照常推进手上的任务。 */
    private Decision takeOver(Choice choice, TickContext context) {
        Task task;
        try {
            task = choice.chosen.createTask(context);
        } catch (RuntimeException exception) {
            LOG.warn("生存需求「{}」创建临时任务时出错", choice.chosen.name(), exception);
            holdBack(choice.chosen, choice.urgency, context,
                    "创建临时任务时程序出错：" + exception.getClass().getSimpleName());
            return stack.isEmpty() ? Decision.IDLE : advance(top(), context, choice.chosen);
        }
        stack.add(new Frame(task, choice.chosen, choice.urgency));
        return advance(top(), context, null);
    }

    /** 推进这一层一刻（第一次推进时先 start）；它若走到结局就弹出，临时任务没做成时它的需求先缓一阵。 */
    private Decision advance(Frame frame, TickContext context, SurvivalNeed deferred) {
        // 换了一件事做：先让上一件留下的界面关上，新的这件再动手。
        if (frame.task != lastAdvanced) {
            if (lastAdvanced != null) onSwitch.accept(context);
            lastAdvanced = frame.task;
        }
        TickResult result;
        if (!frame.started) {
            frame.started = true;
            frame.runner.begin(frame.task, context);
            // 启动就出错的任务当场有了结果，不再推进它。
            result = frame.runner.finished() ? TickResult.finished(frame.runner.result()) : frame.runner.tick(context);
        } else {
            result = frame.runner.tick(context);
        }
        if (!(result instanceof TickResult.Finished finished)) {
            return new Decision.Advanced(frame.task, frame.from, deferred, null);
        }
        LOG.info("任务结束：{}（{}）", frame.task.describe(), finished.result().summary());
        stack.remove(frame);
        // 插进来的临时任务做完要说一声的（夜里自己睡了一觉）：发一条事件，LLM 才知道时间跳了、人挪过。
        if (frame.from != null && frame.task instanceof AnnouncesFinish announces) {
            String note = announces.finishedNote(finished.result());
            if (note != null) events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED, note);
        }
        // 临时任务做成的事发生在主任务身上：插了一支灯、垫了一块料，把交代的结果递给主任务记账，
        // 不让变化跟着临时任务的结果一起消失。
        if (frame.from != null && !stack.isEmpty()
                && stack.getFirst().task instanceof KeepsTempTaskFacts keeps) {
            keeps.keepTempTaskFacts(finished.result());
        }
        if (frame.from != null && finished.result().status() != TaskResult.Status.DONE
                && (frame.from.waitsAfterFailure() || programError(finished.result()))) {
            // 自救没做成：处境不更急就先缓一阵再插，让主任务有机会动；做没做成都不影响主任务。
            // 声明不缓的需求（被攻击）不按住：每一下新挨的打都要接着处理，不让它站着挨打；
            // 但程序出错照样缓，免得一个坏掉的临时任务每刻重建、把主任务停停走走。
            holdBack(frame.from, frame.urgency, context, failureOf(finished.result()));
        } else if (frame.from != null) {
            // 做成了一次：之前接连没做成的次数清零，下次再出事从五秒等起。
            failureStreaks.remove(frame.from);
        }
        // 临时任务回不到原来的岗位时不让主任务在新地点悄悄续上：停住等 LLM 决定。
        // 去某地、拿东西这类在哪做都一样的主任务不停：从新地点接着做就是了（原先一被撤离就停在半路，
        // 也没人告诉 LLM，远程出行走走停停）。临时任务说要等 LLM 发话的（被陌生玩家打了）照样停。
        if (frame.task instanceof DisplacedTask displaced && displaced.cannotResumeInPlace()
                && !stack.isEmpty() && stack.getFirst().from == null
                && (displaced.waitsForOrders() || !stack.getFirst().task.resumesAnywhere())) {
            stack.getFirst().paused = true;
            parkedWhere = displaced.stoppedWhere();
            return new Decision.Parked(stack.getFirst().task, parkedWhere);
        }
        return new Decision.Advanced(frame.task, frame.from, deferred, finished.result());
    }

    /** 收尾主任务并移出栈底；主任务还没被推进过时也按同样的原因收尾，让它交代结果。 */
    private TaskResult endMain(CloseReason reason) {
        if (stack.isEmpty() || stack.get(0).from != null) {
            return null;
        }
        Frame bottom = stack.remove(0);
        LOG.info("主任务结束（{}）：{}", reason, bottom.task.describe());
        return bottom.close(reason);
    }

    // 手上是临时任务时，这个需求能不能抢它：更急的能；同样急的只有登记在前面的能（自卫排在夜晚前面）；更缓的不能。
    // 手上是主任务或没有任务时都能（照打断规则）。
    private boolean outranks(SurvivalNeed need, Urgency urgency, Frame top) {
        if (top == null || top.from == null) return true;
        int compared = urgency.compareTo(top.urgency);
        if (compared != 0) return compared > 0;
        return needs.indexOf(need) < needs.indexOf(top.from);
    }

    // 判断出错的需求按"此刻不需要处理"对待：一个需求的程序错误不能拖垮其余需求与主任务。
    private Urgency urgencyOf(SurvivalNeed need, TickContext context, Task currentTask) {
        try {
            Urgency urgency = need.urgency(context, currentTask);
            brokenNeeds.remove(need);
            return urgency;
        } catch (RuntimeException exception) {
            if (brokenNeeds.add(need)) {
                LOG.warn("生存需求「{}」判断急迫程度时出错，先按不需要处理对待", need.name(), exception);
            }
            return null;
        }
    }

    // 临时任务是不是因为程序出错没做成：不是游戏里的处境，重来也一样错。
    private static boolean programError(TaskResult result) {
        return result.problem() != null && result.problem().kind() == Problem.Kind.INTERNAL_ERROR;
    }

    // 记一次没做成：这个需求先缓一阵，接连没做成就缓得更久；为什么没做成一并记下，面板上照实说。
    private void holdBack(SurvivalNeed need, Urgency urgency, TickContext context, String why) {
        int streak = failureStreaks.merge(need, 1, Integer::sum);
        long delay = Math.min(RETRY_AFTER_FAILED_TICKS << Math.min(streak - 1, 12), MAX_RETRY_AFTER_FAILED_TICKS);
        backoffs.put(need, new Backoff(context.gameTick() + delay, urgency, why));
    }

    // 临时任务没做成的原因：有问题就用问题本身的说法，没有就用结果的一句话结论。
    private static String failureOf(TaskResult result) {
        return result.problem() != null ? result.problem().message() : result.summary();
    }

    // 临时任务刚没做成的需求：没到时间、处境也没更急，就先不插。
    private boolean heldBack(SurvivalNeed need, Urgency urgency, TickContext context) {
        Backoff backoff = backoffs.get(need);
        if (backoff == null) return false;
        if (context.gameTick() >= backoff.untilTick() || urgency.ordinal() > backoff.urgency().ordinal()) {
            backoffs.remove(need);
            return false;
        }
        return true;
    }

    private Frame top() {
        return stack.get(stack.size() - 1);
    }

    /** 这个需求创建的临时任务是否还在栈里：正在推进，或被打断后等着恢复。 */
    private boolean hasLiveTask(SurvivalNeed need) {
        return stack.stream().anyMatch(frame -> frame.from == need);
    }

    /** 一个需求的临时任务没做成后的等待：到哪一刻为止、当时有多急、为什么没做成。 */
    private record Backoff(long untilTick, Urgency urgency, String why) {}

    /** 一刻的挑选结果：插进来的需求与它多急，以及最急的那个被按住的需求。 */
    private static final class Choice {
        SurvivalNeed chosen;
        Urgency urgency;
        SurvivalNeed deferred;
        Urgency deferredUrgency;
    }

    /** 栈里的一层：一个任务、推进它的子任务运行器、它来自哪个生存需求（主任务为 null）与插进来时多急。 */
    private static final class Frame {
        final Task task;
        final ChildTaskRunner runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
        final SurvivalNeed from;
        final Urgency urgency;
        boolean started;
        /** 主任务被临时任务留在了半路：不能再推进，等 LLM 明确换任务或解除暂停。 */
        boolean paused;
        /** 为了让不急的需求插进来，已经叫它先到安全的地方再停。 */
        boolean stopAsked;
        /** 从哪一刻起一直停下不安全；此刻能停为 Long.MIN_VALUE。 */
        long unsafeSince = Long.MIN_VALUE;

        Frame(Task task, SurvivalNeed from, Urgency urgency) {
            this.task = Objects.requireNonNull(task, "task");
            this.from = from;
            this.urgency = urgency;
        }

        /** 被打断：推进过的经子任务运行器转发暂停，还没推进过的直接告诉任务。 */
        void pause() {
            if (started) {
                runner.pause();
            } else {
                task.pause();
            }
        }

        /** 先到安全的地方再停：只有推进过的任务才可能停下不安全，还没推进过的现在就能停。 */
        boolean stopSafely() {
            return started && runner.stopSafely();
        }

        /** 此刻能不能打断：推进过的问子任务运行器，还没推进过的直接问任务自己。 */
        Interruptibility interruptibility(TickContext context) {
            return started ? runner.interruptibility(context) : task.interruptibility(context);
        }

        /** 按原因收尾并拿结果：推进过的经子任务运行器收尾，还没推进过的直接让任务交代。 */
        TaskResult close(CloseReason reason) {
            if (started) {
                return runner.finished() ? runner.result() : runner.close(reason);
            }
            try {
                return task.close(reason);
            } catch (RuntimeException exception) {
                LOG.warn("还没开始的任务收尾时出错：{}", task.describe(), exception);
                return TaskResult.cancelled("任务还没开始就结束了：" + reason);
            }
        }
    }

    /**
     * 运行栈里的一层。
     *
     * @param task     这一层的任务
     * @param needName 插进这一层的生存需求的名字；主任务为 null
     * @param urgency  插进来时多急；主任务为 null
     * @param parked   主任务被临时任务停在了半路，不再推进
     */
    public record Layer(Task task, String needName, Urgency urgency, boolean parked) {}

    /**
     * 想插进来却没插进来的需求：被打断规则按住，或者上次没做成正等着再试。
     *
     * @param needName 需求的名字
     * @param urgency  此刻多急
     */
    public record HeldBackNeed(String needName, Urgency urgency) {}

    /**
     * 一个临时任务没做成、正在等着再试的需求。
     *
     * @param needName  需求的名字
     * @param urgency   没做成那时有多急；处境变得更急会马上再试
     * @param untilTick 缓到哪一刻（游戏刻）
     * @param failures  接连没做成了几次；次数越多缓得越久
     * @param why       上次为什么没做成，用游戏里的话说
     */
    public record RetryWait(String needName, Urgency urgency, long untilTick, int failures, String why) {}

    /** 控制循环本刻的决定：推进了哪个任务、是哪个生存需求插进来的、谁被按住了、有没有任务就此结束。 */
    public sealed interface Decision {

        /** 手上没有任务也没有需要处理的生存需求，本刻什么都没做。 */
        Decision IDLE = new Idle();

        /**
         * 本刻推进了一个任务。
         *
         * @param task         被推进的任务
         * @param interrupting 插进来的生存需求；主任务正常推进时为 null
         * @param deferred     想插进来但被打断规则按住、或刚没做成正在缓一阵的生存需求；没有时为 null
         * @param finished     任务本刻走到结局时的结果；还在做时为 null
         */
        record Advanced(Task task, SurvivalNeed interrupting, SurvivalNeed deferred, TaskResult finished)
                implements Decision {}

        /**
         * 临时任务收尾时把主任务停在了半路：主任务不再推进，事件里带上停在了哪里。
         * LLM 明确派新任务（换掉主任务）之前，角色不再替停住的主任务继续干活。
         */
        record Parked(Task pausedMain, String where) implements Decision {}

        /** 本刻什么都没做。 */
        record Idle() implements Decision {}

        /**
         * 角色死了：循环停摆，本刻不推进任何任务，等重生。
         *
         * @param mainTask 停在原地的主任务；手上没有主任务时为 null
         */
        record WaitingRespawn(Task mainTask) implements Decision {}
    }
}

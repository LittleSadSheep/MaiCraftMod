// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.agent.tool.LocalToolDispatcher;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.task.chain.MLGChain;
import org.maiwithu.maicraft.core.task.chain.BreathChain;

/** 决定玩家这一刻做哪件事：先考虑自救，再考虑临时动作和当前任务；不能让两件事同时按键。 */
final class CompanionBrain {

    private static final int HAND_PIN_GRACE_TICKS = 600;
    /** 换手被安全闸门拒绝的留痕冷却：持续拒绝只按节奏记，不刷屏。 */
    private static final long YIELD_REFUSAL_LOG_INTERVAL = 100;

    // 初值取 MIN_VALUE / 2：直接取 MIN_VALUE 时 now - last 在首次判定就回绕为负，
    // 冷却条件恒成立，留痕一次都打不出来。
    private long lastYieldRefusalLog = Long.MIN_VALUE / 2;

    private final Deque<TaskRecord> outbox = new ArrayDeque<>();
    final TaskSlot sync = new TaskSlot(outbox::addLast);
    final TaskSlot current = new TaskSlot(outbox::addLast);

    private final List<Task> reflexes = List.copyOf(BrainChains.build());
    private final List<Task> idlePoses = List.of();
    private final HandPinRelease handPinRelease = new HandPinRelease(HAND_PIN_GRACE_TICKS);

    private final Task syncProxy = new SlotProxy(sync);
    private final Task currentProxy = new SlotProxy(current);
    private Task holder;

    /** 每个游戏刻选一件事执行；需要换任务时，先让旧任务松开按键。 */
    void tick(LocalPlayer player) {
        if (handPinRelease.tick(!sync.isEmpty() || !current.isEmpty())) {
            // 一段时间没有任务后触发旧结束通知；当前没有监听者，不能把它描述成已经解除手持锁。
            TaskSessionHooks.fireSessionEnd(player);
        }

        // 在岗任务持有身体时，释放窗口反射（贴边退避等）不参与抢占；反射自救照常优先。
        Task winner = TaskSelector.select(
                reflexes,
                sync.isEmpty() ? null : syncProxy,
                current.isEmpty() ? null : currentProxy,
                idlePoses,
                player,
                slotHoldsBody());

        // 正在跳跃、下落或乘交通工具时，突然换人控制可能摔下去；通常先让当前动作走到能安全停下的位置。
        // 但如果原来的落地方案已经失败，或者获胜者是围困窒息这类等不起的紧急自救，就允许它马上接手。
        if (holder != winner && (!EmbeddedBaritoneRuntime.canSafelySuspendActive()
                || !TransportRuntime.canSafelySuspendActive())) {
            boolean rescue = winner instanceof MLGChain mlg
                    && EmbeddedBaritoneRuntime.canHandOffMissedLanding(player)
                    && mlg.prepareMissedLandingTakeover(player)
                    && EmbeddedBaritoneRuntime.handOffMissedLanding(player);
            // 坠落已被水缓冲后，低氧逃生可以接手；不能继续以“尚未落地”为由把角色压在水下。
            boolean breathing = winner instanceof BreathChain && !TransportRuntime.occupied()
                    && EmbeddedBaritoneRuntime.handOffForBreathing(player);
            boolean urgent = winner != null && winner.urgentBodyRescue(player);
            if (!rescue && !breathing && !urgent) {
                logYieldRefusal(player, winner);
                winner = holder;
            }
        }
        if (holder != null && holder != winner) {
            // 先停掉旧任务的自动走路和交通控制，再通知它暂停，避免旧路线继续按键干扰新任务。
            EmbeddedBaritoneRuntime.suspendActivePhysicalOutputs();
            TransportRuntime.suspendActive();
            holder.stop(player, Task.StopReason.PREEMPTED);
        }
        holder = winner;

        // 这次没轮到的任务，给它多一刻时间；等别人干活不应该花掉自己的执行时间。
        if (winner != syncProxy) {
            sync.freeze();
        }
        if (winner != currentProxy) {
            current.freeze();
        }

        if (winner == syncProxy) {
            sync.tick(player);
        } else if (winner == currentProxy) {
            current.tick(player);
        } else if (winner != null) {
            winner.tick(player);
        }

        sync.settleIfTerminal(player);
        current.settleIfTerminal(player);
        shipResults();
    }

    void submitSync(LocalPlayer player, TaskRecord record) {
        // 放入临时动作的位置；被替换的旧动作若产生结果，也在这里发回。
        sync.put(player, record);
        shipResults();
    }

    void submitCurrent(LocalPlayer player, TaskRecord record) {
        // 更换玩家当前要做的事；具体的停止旧任务和准备新任务由 TaskSlot 处理。
        current.put(player, record);
        shipResults();
    }

    TaskRecord current() {
        return current.record();
    }

    boolean allowsAuxiliaryWork() {
        // 自救与明确独占身体的动作不借出资源；普通工作仍由原槽执行，辅助动作只用剩余的准星和操作机会。
        return (holder == null || holder == currentProxy || holder == syncProxy)
                && (holder == null || !holder.suppressesSurvivalReflexes());
    }

    /** 身体此刻是否被任务槽记录持有（反射自救不算持有）：释放窗口反射据此让位给在岗任务。
     *  持有以任务槽的真实占用为准——槽位已结算而 holder 还停在槽代理上的那一刻不算持有，
     *  否则释放窗口反射会被一个已经不存在的任务无限期挡在门外。 */
    boolean slotHoldsBody() {
        return (!sync.isEmpty() && holder == syncProxy) || (!current.isEmpty() && holder == currentProxy);
    }

    /**
     * 换手安全闸门拒绝本次交接时留痕：获胜者是谁、哪个运行时拒绝、身体此刻在谁手里。
     * 这个分支此前完全静默——释放窗口反射"通过了触发判定却从未接管"的实机现象
     * （零事件、零动作）正是从这里被吃掉的，留痕后下一次实机窗口才能定位到具体闸门。
     */
    private void logYieldRefusal(LocalPlayer player, Task winner) {
        long now = player.level().getGameTime();
        if (!yieldRefusalLogDue(now)) return;
        lastYieldRefusalLog = now;
        Constants.LOG.info(
                "[maicraft-task] handover refused; winner={} holder={} baritone_safe={} transport_safe={}",
                winner == null ? "none" : winner.name(), describeHolder(),
                EmbeddedBaritoneRuntime.canSafelySuspendActive(), TransportRuntime.canSafelySuspendActive());
    }

    /** 距上次留痕是否已过冷却；首次判定（从未留痕过）必须返回 true。 */
    boolean yieldRefusalLogDue(long now) {
        return now - lastYieldRefusalLog >= YIELD_REFUSAL_LOG_INTERVAL;
    }

    private String describeHolder() {
        if (holder == null) return "none";
        if (holder == syncProxy) return "sync_task";
        if (holder == currentProxy) return "current_task";
        return holder.name();
    }

    // 先结清寻死目标再保存重生检查点；普通任务返回 false，仍按原有死亡恢复流程处理。
    boolean observeDeath(LocalPlayer player) {
        boolean expected = current.observeDeath(player);
        shipResults();
        return expected;
    }

    String controllingTask() {
        return holder == null ? "none" : holder == currentProxy ? "current_task"
                : holder == syncProxy ? "synchronous_task" : holder.getClass().getName();
    }

    /** 面板任务区用：此刻占用身体的持有者的行动与里程碑；身体空闲返回 {@code null}。 */
    CompanionTickDispatcher.BodyAction bodyAction() {
        if (holder == null) return null;
        if (holder == currentProxy) return slotAction(current);
        if (holder == syncProxy) return slotAction(sync);
        // 反射自救队占用身体：行动句子来自反射自己，任务里程碑与身体无关，保持为空。
        return new CompanionTickDispatcher.BodyAction(holder.describeCurrentAction(), Map.of(), true);
    }

    // 行动句子由一线执行器自答（describeCurrentAction 沿 child 链下钻），里程碑取任务单上的持久计数；
    // 两个来源都可能为空，面板按"缺则无"显示，不在此处编造退路。
    private CompanionTickDispatcher.BodyAction slotAction(TaskSlot slot) {
        if (slot.record() == null) return null;
        Map<String, Object> progress = slot.progress();
        return new CompanionTickDispatcher.BodyAction(
                slot.describeCurrentAction(), deepestMilestones(progress), false);
    }

    /** 进度行放行的里程碑键：done/total 沿仓库统一计数约定，来自当前干活者的任务单字段。 */
    private static final Set<String> MILESTONE_KEYS = Set.of("done", "total");

    /**
     * 里程碑只取 child 链上最深一层持有者的内容，不跨层合并——语义父任务的计数与
     * 子任务的操作对象分属不同层，混取会把两份不同口径的进度拼到一行。
     */
    private static Map<String, Object> deepestMilestones(Map<?, ?> progress) {
        Object child = progress.get("child");
        if (child instanceof Map<?, ?> nested) {
            Map<String, Object> deeper = deepestMilestones(nested);
            if (!deeper.isEmpty()) return deeper;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : MILESTONE_KEYS) {
            if (progress.containsKey(key)) out.put(key, progress.get(key));
        }
        return out;
    }



    List<TaskRecord> list() {
        // 返回当前仍在等待或执行的任务；已经结束的历史不保存在这个调度器里。
        List<TaskRecord> records = new ArrayList<>(2);
        if (sync.record() != null) {
            records.add(sync.record());
        }
        if (current.record() != null) {
            records.add(current.record());
        }
        return List.copyOf(records);
    }

    TaskRecord find(String publicId) {
        if (publicId == null) {
            return null;
        }
        for (TaskRecord record : list()) {
            if (publicId.equals(record.publicId())) {
                return record;
            }
        }
        return null;
    }

    boolean cancel(LocalPlayer player, String publicId) {
        // 未指定编号时只取消当前任务；指定编号时才去找对应任务，不误停另一件事。
        boolean cancelled = false;
        if (publicId == null || publicId.isBlank()) {
            cancelled = current.cancel(player);
        } else if (sync.record() != null && publicId.equals(sync.record().publicId())) {
            cancelled = sync.cancel(player);
        } else if (current.record() != null && publicId.equals(current.record().publicId())) {
            cancelled = current.cancel(player);
        }
        if (cancelled) {
            stopNonSlotHolder(player, Task.StopReason.REPLACED);
            TaskSessionHooks.fireSessionEnd(player);
            shipResults();
        }
        return cancelled;
    }

    void cancelAll(LocalPlayer player) {
        // 结束两个任务并停掉正在自救的行为，随后触发保留的结束通知；具体动作清理由各执行器 stop 负责。
        boolean hadWork = !sync.isEmpty() || !current.isEmpty();
        // 整体停机不做仅清理缓期：两个槽位都立即终态，现场事实由各自回执如实列出。
        sync.cancelImmediate(player);
        current.cancelImmediate(player);
        stopNonSlotHolder(player, Task.StopReason.REPLACED);
        if (hadWork) {
            TaskSessionHooks.fireSessionEnd(player);
        }
        shipResults();
    }

    /**
     * 普通任务刚才已经由 TaskSlot 停止，不要重复停；如果正在执行的是自救行为，则在这里通知它结束。
     * 只把 holder 清空还不够，自救行为可能记着“我还没做完”，下一刻又会接着执行。
     */
    private void stopNonSlotHolder(LocalPlayer player, Task.StopReason reason) {
        Task previousHolder = holder;
        holder = null;
        if (previousHolder == null || previousHolder == syncProxy || previousHolder == currentProxy) {
            return;
        }
        try {
            previousHolder.stop(player, reason);
        } catch (RuntimeException ignored) {
            // 即使自救行为清理失败，也继续完成任务取消和槽位结算。
        }
    }

    /** 过传送门时停止旧世界的具体动作，只把当前总任务带走，到新世界后重新观察再继续。 */
    TaskRecord detachCurrentForHandoff(LocalPlayer player) {
        Task previousHolder = holder;
        holder = null;
        if (previousHolder != null && previousHolder != syncProxy && previousHolder != currentProxy) {
            try {
                previousHolder.stop(player, Task.StopReason.BODY_GONE);
            } catch (RuntimeException ignored) {
                // 自救行为收尾失败不能阻止任务槽位清理和身体交接。
            }
        }
        boolean hadWork = !sync.isEmpty() || !current.isEmpty();
        sync.bodyGone(player);
        TaskRecord detached = current.detachForHandoff(player);
        if (hadWork) {
            TaskSessionHooks.fireSessionEnd(player);
        }
        shipResults();
        return detached;
    }
    void bodyGone(LocalPlayer player) {
        // 玩家退出、死亡或被新玩家对象替换后，旧对象上的任务都不能再执行。
        Task previousHolder = holder;
        holder = null;
        if (previousHolder != null && previousHolder != syncProxy && previousHolder != currentProxy) {
            try {
                previousHolder.stop(player, Task.StopReason.BODY_GONE);
            } catch (RuntimeException ignored) {
                // 仍由下方统一完成任务槽位的结束处理。
            }
        }
        boolean hadWork = !sync.isEmpty() || !current.isEmpty();
        sync.bodyGone(player);
        current.bodyGone(player);
        if (hadWork) {
            TaskSessionHooks.fireSessionEnd(player);
        }
        shipResults();
    }

    private void shipResults() {
        // 尝试交回旧 ToolCall 通道中的等待者；当前语义结果由 IntentTask 自行结算，未登记的编号会被旧通道忽略。
        while (!outbox.isEmpty()) {
            TaskRecord record = outbox.removeFirst();
            String callId = record.getToolCallId();
            if (callId == null || callId.isBlank()) {
                continue;
            }
            TaskResult result = record.getResult();
            String json = result == null
                    ? TaskResult.fail("task completed without a result").toJson()
                    : result.toJson();
            LocalToolDispatcher.deliver(callId, json);
        }
    }

    /** 让调度器可以像询问自救行为一样，询问“这个位置上放的任务现在能不能做”。 */
    private static final class SlotProxy implements Task {
        private final TaskSlot slot;

        private SlotProxy(TaskSlot slot) {
            this.slot = slot;
        }

        @Override
        public boolean canRun(LocalPlayer player) {
            return slot.canRun(player);
        }

        @Override public boolean suppressesSurvivalReflexes() {
            return slot.suppressesSurvivalReflexes();
        }

        @Override
        public TaskState tick(LocalPlayer player) {
            slot.tick(player);
            return TaskState.RUNNING;
        }

        @Override
        public void stop(LocalPlayer player, StopReason why) {
            slot.loseBody(player);
        }

        @Override
        public String name() {
            return "task_slot";
        }
    }
}

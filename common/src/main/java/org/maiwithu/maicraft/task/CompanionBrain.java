package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.LocalToolDispatcher;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 决定玩家这一刻做哪件事：先考虑自救，再考虑临时动作和当前任务；不能让两件事同时按键。 */
final class CompanionBrain {

    private static final int HAND_PIN_GRACE_TICKS = 600;

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

        Task winner = TaskSelector.select(
                reflexes,
                sync.isEmpty() ? null : syncProxy,
                current.isEmpty() ? null : currentProxy,
                idlePoses,
                player);

        // 正在跳跃、下落或乘交通工具时，突然换人控制可能摔下去；通常先让当前动作走到能安全停下的位置。
        // 但如果原来的落地方案已经失败，而且自救任务准备好了，就允许它马上接手。
        if (holder != winner && (!EmbeddedBaritoneRuntime.canSafelySuspendActive()
                || !org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.canSafelySuspendActive())) {
            boolean rescue = winner instanceof org.maiwithu.maicraft.core.task.chain.MLGChain mlg
                    && EmbeddedBaritoneRuntime.canHandOffMissedLanding(player)
                    && mlg.prepareMissedLandingTakeover(player)
                    && EmbeddedBaritoneRuntime.handOffMissedLanding(player);
            if (!rescue) winner = holder;
        }
        if (holder != null && holder != winner) {
            // 先停掉旧任务的自动走路和交通控制，再通知它暂停，避免旧路线继续按键干扰新任务。
            EmbeddedBaritoneRuntime.suspendActivePhysicalOutputs();
            org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.suspendActive();
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

    String controllingTask() {
        return holder == null ? "none" : holder == currentProxy ? "current_task"
                : holder == syncProxy ? "synchronous_task" : holder.getClass().getName();
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
        sync.cancel(player);
        current.cancel(player);
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
            // Cancellation and slot settlement remain authoritative even if reflex cleanup fails.
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
                // Slot cleanup and handoff must still complete.
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
                // Slot settlement below must still run.
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

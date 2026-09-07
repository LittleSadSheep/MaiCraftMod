package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.LocalToolDispatcher;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** The one scheduler state machine for the active local player body. */
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

    /** Select and advance no more than one body owner for this client tick. */
    void tick(LocalPlayer player) {
        if (handPinRelease.tick(!sync.isEmpty() || !current.isEmpty())) {
            TaskSessionHooks.fireSessionEnd(player);
        }

        Task winner = TaskSelector.select(
                reflexes,
                sync.isEmpty() ? null : syncProxy,
                current.isEmpty() ? null : currentProxy,
                idlePoses,
                player);

        // A launched parkour/fall movement cannot surrender steering midway. Keep its existing
        // body owner for this tick and retry the priority hand-off at the next safe movement
        // boundary. Reflexes that need airborne takeover must first provide an explicit
        // continuation controller; clearing the route's keys is never a safe approximation.
        if (holder != winner && (!EmbeddedBaritoneRuntime.canSafelySuspendActive()
                || !org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.canSafelySuspendActive())) {
            boolean rescue = winner instanceof org.maiwithu.maicraft.core.task.chain.MLGChain mlg
                    && EmbeddedBaritoneRuntime.canHandOffMissedLanding(player)
                    && mlg.prepareMissedLandingTakeover(player)
                    && EmbeddedBaritoneRuntime.handOffMissedLanding(player);
            if (!rescue) winner = holder;
        }
        if (holder != null && holder != winner) {
            // A composite holder may keep its active navigator inside a child task, so stopping
            // only the holder's own nav field is not a complete body hand-off. Retire the shared
            // pathing keys/look/native receipt before the higher-priority winner gets this tick.
            EmbeddedBaritoneRuntime.suspendActivePhysicalOutputs();
            org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.suspendActive();
            holder.stop(player, Task.StopReason.PREEMPTED);
        }
        holder = winner;

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
        sync.put(player, record);
        shipResults();
    }

    void submitCurrent(LocalPlayer player, TaskRecord record) {
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
     * Stop a reflex which currently owns the body before forgetting scheduler ownership.
     * Slot tasks wind themselves down through {@link TaskSlot#cancel}; stopping either proxy
     * here would double-stop the same task.  A reflex, however, may retain an episode or a
     * bounded movement burst across ticks, so merely clearing {@link #holder} would let it
     * reacquire the body after the semantic task had already been cancelled.
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

    /**
     * Detach only the current semantic parent for a previously authorised body replacement.
     * Native child state is discarded and will be re-derived from the new world's facts.
     */
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

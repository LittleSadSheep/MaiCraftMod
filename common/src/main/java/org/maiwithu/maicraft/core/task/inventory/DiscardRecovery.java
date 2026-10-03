// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.DiscardedItems;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts.ObservedDrop;
import org.maiwithu.maicraft.core.task.collect.CollectItemsCompanionTask;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 烧不掉的垃圾占了唯一路线时，临时允许这批 UUID 的原生回收；拿回后才找侧袋，不能把失败投掷留成路障。 */
final class DiscardRecovery {
    private final List<DiscardedItems.Watch> watches;
    private final CollectItemsTaskRecord record;
    private final CollectItemsCompanionTask task;
    private boolean started, closed;
    private TaskState outcome;
    private long settledAfter;
    private Map<String, Object> evidence = Map.of();

    DiscardRecovery(LocalPlayer player, String callId, List<DiscardedItems.Watch> watches, List<ObservedDrop> remaining) {
        this.watches = List.copyOf(watches);
        record = new CollectItemsTaskRecord(callId + "-recover-discard", player.level().getGameTime() + 400,
                Set.of(), 8, "unburned discarded items", remaining.stream().map(ObservedDrop::uuid).collect(Collectors.toSet()),
                player.level().dimension().location());
        task = new CollectItemsCompanionTask(player, record);
    }
    TaskState tick(LocalPlayer player) {
        if (!started) {
            DiscardedItems.recovering(player, watches, true); started = true; task.start(player); return TaskState.RUNNING;
        }
        if (outcome == null) {
            TaskState state = player.level().getGameTime() >= record.getDeadlineGameTime() ? TaskState.TIMEOUT : task.tick(player);
            if (!state.isTerminal()) return state;
            // 回收数量来自原生拾取包与入包证据；再留出实体移除同步窗口，避免旧避让格拦住刚拿回物品的角色。
            evidence = task.result(state).data(); outcome = state; settledAfter = player.level().getGameTime() + 6;
        }
        DiscardedItems.observe(player);
        if (player.level().getGameTime() < settledAfter) return TaskState.RUNNING;
        close(player); return outcome;
    }
    int collected() { return record.getCollected(); }
    Map<String, Object> result() { return Map.of("collected", collected(), "pickup", evidence); }
    void close(LocalPlayer player) {
        if (closed) return;
        if (started && outcome == null) { task.stop(player, Task.StopReason.REPLACED); evidence = task.result(TaskState.CANCELLED).data(); }
        DiscardedItems.recovering(player, watches, false); closed = true;
    }
}

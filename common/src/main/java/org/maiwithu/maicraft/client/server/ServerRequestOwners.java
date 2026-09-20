// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.maiwithu.maicraft.task.TaskRecord;

/** 分发前先检查所属任务是否到期，避免调度器稍后才检查期限时误执行旧请求。 */
final class ServerRequestOwners {
    private final Map<UUID, String> owners = new LinkedHashMap<>();

    void remember(UUID request, String owner) { if (owner != null && !owner.isBlank()) owners.put(request, owner); }
    void clear() { owners.clear(); }
    boolean selected(UUID request, String selectedOwner) {
        String owner = owners.get(request);
        return owner == null || owner.equals(selectedOwner);
    }
    static boolean ordinaryWinner(String winner) {
        return winner.equals("current_task") || winner.equals("synchronous_task") || winner.equals("none");
    }

    void retire(ClientRequestRouter router, Function<String, TaskRecord> lookup, long gameTick) {
        var iterator = owners.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var receipt = router.poll(entry.getKey());
            if (receipt.isEmpty() || (receipt.get().settled() && !receipt.get().unresolvedMutation())) {
                iterator.remove();
                continue;
            }
            TaskRecord owner = lookup.apply(entry.getValue());
            if (owner == null || owner.getState().isTerminal() || gameTick >= owner.getDeadlineGameTime()) {
                router.cancel(entry.getKey());
                iterator.remove();
            }
        }
    }
}

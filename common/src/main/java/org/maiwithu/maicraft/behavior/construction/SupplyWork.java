// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备料：缺什么就经拿到物品的引擎去弄一种，弄到了再弄下一种；弄不到记一笔接着弄别的，
 * 不整单拒绝——有多少建多少，材料用完时施工停在材料尽头。
 */
final class SupplyWork extends ConstructionWork {

    private final Permissions permissions;
    private final String purpose;
    private final Deque<Map.Entry<String, Integer>> wanted;
    private Map.Entry<String, Integer> fetching;

    SupplyWork(ConstructionServices services, ConstructionSite site, Records records, Permissions permissions, String purpose,
               Map<String, Integer> missing) {
        super(services, site, records);
        this.permissions = permissions;
        this.purpose = purpose;
        this.wanted = new ArrayDeque<>(missing.entrySet());
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (fetching != null) {
            records.attempt("备料 " + fetching.getKey() + " ×" + fetching.getValue(), "拿到了");
            fetching = null;
        }
        if (wanted.isEmpty() || services.needs() == null) return ActionStatus.done();
        fetching = wanted.poll();
        begin(services.needs().actionFor(new ItemRequest(WantedItem.ofItem(fetching.getKey()), fetching.getValue(), purpose), permissions),
                "去拿 " + fetching.getKey() + " ×" + fetching.getValue());
        return ActionStatus.progressed();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        // 这一种弄不到：记下，接着弄下一种；缺的格到施工时如实停在材料尽头。
        records.attempt("备料 " + fetching.getKey() + " ×" + fetching.getValue(), failure.problem().message());
        fetching = null;
        return advance(context);
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.navigation.ScaffoldBlocks;
import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 正要用的东西：上一个目标刚拿到的，加上手上这件事正在拿的（拿石镐时要的圆石、备料要的木板）。
 * 路上垫脚不先动它们——挖了三块圆石，爬上来时又把三块垫下去，这一趟就白跑了。
 *
 * <p>只留拿到的那么多件，多出来的照样能垫；下一个目标结束就换成那个目标拿到的。
 * 要的是标签（"任一石质工具材料"）时，挂着这个标签的料都按这个数留。只在客户端线程读写。
 */
public final class ItemsInUse implements ScaffoldBlocks.Keeps {

    private final ReadsItemTags tags;
    /** 上一个目标拿到的：物品或 #标签 → 件数。 */
    private Map<String, Integer> lastGoal = Map.of();
    /** 手上这件事正在拿的：拿东西的引擎此刻在推进的那一串请求；引擎建好前是空的。 */
    private Supplier<List<ItemRequest>> acquiring = List::of;

    public ItemsInUse(ReadsItemTags tags) {
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    /** 一个目标结束：记下它拿到的东西，换掉更早那个目标的。 */
    public void goalFinished(List<Change> changes) {
        Map<String, Integer> gained = new LinkedHashMap<>();
        for (Change change : changes) {
            if (change.kind() == Change.Kind.ITEM_GAINED && change.count() > 0 && !change.what().isBlank()) {
                gained.merge(change.what(), change.count(), Integer::sum);
            }
        }
        lastGoal = Map.copyOf(gained);
    }

    /** 接上拿东西的引擎：它此刻在推进的那一串请求就是手上正在拿的。 */
    public void watch(Supplier<List<ItemRequest>> acquiring) {
        this.acquiring = Objects.requireNonNull(acquiring, "acquiring");
    }

    @Override
    public int keep(String itemId) {
        Set<String> itemTags = tags.tagsOf(itemId);
        int keep = 0;
        for (var gained : lastGoal.entrySet()) {
            if (new WantedItem(gained.getKey()).matches(itemId, itemTags)) keep += gained.getValue();
        }
        for (ItemRequest request : acquiring.get()) {
            if (request.wanted().matches(itemId, itemTags)) keep += request.count();
        }
        return keep;
    }
}

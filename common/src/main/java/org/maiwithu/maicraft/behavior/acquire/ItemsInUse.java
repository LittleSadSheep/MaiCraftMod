// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.game.player.EverydayFood;
import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 正要用的东西：上一个目标刚拿到的，加上手上这件事正在拿的（拿石镐时要的圆石、备料要的木板）。
 * 路上垫脚不先动它们——挖了三块圆石，爬上来时又把三块垫下去，这一趟就白跑了。
 *
 * <p>只留拿到的那么多件，多出来的照样能垫；下一个目标结束就换成那个目标拿到的。
 * 要的是标签（"任一石质工具材料"）时，挂着这个标签的料都按这个数留。
 *
 * <p>上一个目标要的本来就是吃的（能吃的口粮一大类、{@code c:foods} 下的面包熟肉这类），拿到的就是拿来吃的，
 * 不算正要用的：饿着弄来一块生羊肉，饿了却因为"上一个目标刚拿到"不让吃，这一趟就白弄了。
 * 点名要的具体食物（要交任务的熟海带）照旧留着。只在客户端线程读写。
 */
public final class ItemsInUse implements ItemsToKeep {

    private final ReadsItemTags tags;
    /** 吃的这一棵标签的根：c:foods 和它下面的分类（c:foods/bread、c:foods/cooked_meat……）都是吃的。 */
    private static final String FOOD_TAG_ROOT = "c:foods";

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
            // 要的本来就是吃的：拿来就是吃的，不留着。
            if (change.kind() == Change.Kind.ITEM_GAINED && change.count() > 0 && !change.what().isBlank()
                    && !wantsFood(change.what())) {
                gained.merge(change.what(), change.count(), Integer::sum);
            }
        }
        lastGoal = Map.copyOf(gained);
    }

    /** 目标要的是不是吃的那几类标签：能吃的口粮一大类，或 c:foods 与它下面的分类。点名的具体物品不算。 */
    static boolean wantsFood(String what) {
        if (!what.startsWith("#")) return false;
        String tag = what.substring(1);
        return tag.equals(EverydayFood.TAG) || tag.equals(FOOD_TAG_ROOT) || tag.startsWith(FOOD_TAG_ROOT + "/");
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

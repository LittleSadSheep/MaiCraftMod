package org.maiwithu.maicraft.core.task.build;

import net.minecraft.world.item.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 把已算好的材料缺口写成简短提示；不统计施工进度，也不决定如何备料。 */
final class BuildLedger {
    private static final int MISSING_ITEMS_LISTED = 8;
    private BuildLedger() {}

    /**
     * 缺料清单:按缺口从大到小点名前几种,其余只报种类数与总件数。
     *
     * <p>一栋社区图纸能缺一百五十多种材料,全列出来是几千字符——玩家读不完,
     * 模型的上下文也白烧掉一大块,而真正决定"先去干什么"的永远是排头那几样。
     */
    static String summarizeShortfall(Map<Item, Integer> shortfall) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(shortfall.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        StringBuilder out = new StringBuilder();
        int listed = Math.min(MISSING_ITEMS_LISTED, sorted.size());
        for (int i = 0; i < listed; i++) {
            if (i > 0) out.append(", ");
            out.append(label(sorted.get(i).getKey())).append(" x").append(sorted.get(i).getValue());
        }
        if (sorted.size() > listed) {
            // 中途回执是不请自来、而且会反复出现的,所以这里保持截断。但只给一个数字
            // 等于给了个死胡同——指出哪里能拿到全量单子,才叫交代完。
            int restKinds = sorted.size() - listed;
            int restCount = 0;
            for (int i = listed; i < sorted.size(); i++) {
                restCount += sorted.get(i).getValue();
            }
            out.append(", and ").append(restKinds).append(" more kinds (")
                    .append(restCount).append(" items) — `blueprint_read` lists every one");
        }
        int total = 0;
        for (int v : shortfall.values()) {
            total += v;
        }
        out.append("; ").append(total).append(" items short in total");
        return out.toString();
    }

    private static String label(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}

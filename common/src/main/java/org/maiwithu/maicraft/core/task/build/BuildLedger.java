package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整理材料数量和缺料提示。当前施工器只调用本类的静态缺料摘要，下面的实例统计没有生产代码创建或调用。
 * 因此不能用 remainingNeed 等旧统计方法来解释当前 FirstPersonBuildCompanionTask 的完整备料流程。
 */
final class BuildLedger {

    /** 报缺料时最多点名几种。 */
    private static final int MISSING_ITEMS_LISTED = 8;

    private final LocalPlayer player;
    private final BuildTaskRecord r;
    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;

    BuildLedger(LocalPlayer player, BuildTaskRecord r, BuildCellRules rules,
                BuildInventory inv, BuildFixtures fixtures) {
        this.player = player;
        this.r = r;
        this.rules = rules;
        this.inv = inv;
        this.fixtures = fixtures;
    }

    /**
     * 旧统计：汇总尚未完成、需要材料且没有被替换规则或不可建判断排除的格子。
     * 带特殊料单的格子另由 remainingCellNeeds 统计；摆设实体本体的物品在这里加入。
     */
    Map<Item, Integer> remainingNeed() {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (!rules.costsMaterial(target)) continue;
            if (!needsFor(target).isEmpty()) continue;   // 这一格走料单,不能两边都算
            if (target.matches(rules.peek(target.pos()))) continue;
            // 我们不会去动的格子不该进清单。此前这里的过滤比完工统计那处少两条,
            // 于是同一张回执上能同时出现"built 812/812"和"还差 chest x2":报价
            // 索要的是永远不会被放置的格子的材料,开工前还会让玩家先去凑一堆
            // 她压根不打算放的方块。
            if (rules.blockedByMode(target) || rules.hopeless(target)) continue;
            need.merge(target.item(), target.materialCount(), Integer::sum);
        }
        // 摆设实体也要料:它们不是目标格,但同样是玩家得掏出来的东西
        for (BuildTaskRecord.EntitySpawn spawn : r.entities) {
            if (spawn.item() != Items.AIR && !fixtures.alreadyThere(spawn)) {
                need.merge(spawn.item(), 1, Integer::sum);
            }
        }
        return need;
    }

    /**
     * 这一格的料单——空表示走默认的"一件本方块的物品 × 件数"。
     *
     * <p>一格一件是特例而不是通则:带花的花盆是盆加花两件,带花纹的旗帜是一叠但要求
     * 组件一致。这张单子整个盖过默认口径。
     */
    List<BuildTaskRecord.CellNeed> needsFor(BuildTaskRecord.Target target) {
        if (r.cellNeeds().isEmpty()) {
            return List.of();
        }
        var found = r.cellNeeds().get(target.pos().asLong());
        return found == null ? List.of() : found;
    }

    /**
     * 旧统计：收集未完成格子的特殊料单，例如花盆加植物，再加尚未存在的摆设携带的物品。
     */
    List<BuildTaskRecord.CellNeed> remainingCellNeeds() {
        List<BuildTaskRecord.CellNeed> out = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            var needs = needsFor(target);
            if (needs.isEmpty() || !rules.costsMaterial(target)) continue;
            if (target.matches(rules.peek(target.pos()))) continue;
            if (rules.blockedByMode(target) || rules.hopeless(target)) continue;
            out.addAll(needs);
        }
        for (BuildTaskRecord.EntitySpawn spawn : r.entities) {
            if (!fixtures.alreadyThere(spawn)) {
                for (ItemStack carried : spawn.payload(player.level().registryAccess())) {
                    out.add(new BuildTaskRecord.CellNeed(carried, true));
                }
            }
        }
        return out;
    }

    /** 逐格料单的缺口:哪几叠凑不齐,缺几件就列几条。 */
    List<BuildTaskRecord.CellNeed> needShortfall(List<BuildTaskRecord.CellNeed> needs) {
        List<BuildTaskRecord.CellNeed> kinds = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (BuildTaskRecord.CellNeed want : needs) {
            int at = -1;
            for (int i = 0; i < kinds.size(); i++) {
                // 同一档口径才算同一种:按类型收的和按组件收的不能合并
                if (kinds.get(i).exact() == want.exact() && kinds.get(i).matches(want.stack())) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                kinds.add(want);
                counts.add(1);
            } else {
                counts.set(at, counts.get(at) + 1);
            }
        }
        // 每个匹配组分别扣一次当前背包数量；这里没有跨组预留同一物品，所以宽松组和精确组可能重复使用库存。
        List<BuildTaskRecord.CellNeed> missing = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            int have = inv.countMatching(kinds.get(i));
            for (int k = have; k < counts.get(i); k++) {
                missing.add(kinds.get(i));
            }
        }
        return missing;
    }

    /** 需求减去背包现货 = 还差多少。存量与逐格闸门、实扣共用同一个 36 格口径。 */
    Map<Item, Integer> shortfallAgainstInventory(Map<Item, Integer> need) {
        Map<Item, Integer> shortfall = new LinkedHashMap<>();
        for (var e : need.entrySet()) {
            int have = inv.mainInventoryCount(e.getKey());
            if (have < e.getValue()) {
                shortfall.put(e.getKey(), e.getValue() - have);
            }
        }
        return shortfall;
    }

    /** 还差什么才能收工——按此刻的剩余需求算,不是本遍的过程量。 */
    String missingReason(Map<Item, Integer> passMissing) {
        Map<Item, Integer> shortfall = shortfallAgainstInventory(remainingNeed());
        List<BuildTaskRecord.CellNeed> exact = needShortfall(remainingCellNeeds());
        if (shortfall.isEmpty() && exact.isEmpty()) {
            shortfall = passMissing;   // 期间被人补过料的边缘情形,退回本遍统计
        }
        return "still needs " + summarizeShortfall(shortfall, exact)
                + ". Everything already standing stays; restock and send the SAME call again to carry "
                + "on from exactly here — finished cells are skipped automatically.";
    }

    /**
     * 缺料清单,含<b>精确</b>那一档。
     *
     * <p>精确料要单独点名并说明"要一模一样的那件":只报一句"还差 white_banner x1",
     * 而玩家手里明明有一面白旗,他会以为是我们数错了——真正缺的是带着图纸那套花纹的
     * 那面旗。判据严到哪里,话就得说到哪里。
     */
    static String summarizeShortfall(Map<Item, Integer> shortfall,
                                     List<BuildTaskRecord.CellNeed> extra) {
        String bulk = shortfall.isEmpty() ? "" : summarizeShortfall(shortfall);
        if (extra.isEmpty()) {
            return bulk.isEmpty() ? "nothing" : bulk;
        }
        // 两档分开说:按类型收的照常点名,按组件收的要讲清"要一模一样的那件"
        // 提示按显示名字合并数量；两个同名但花纹不同的物品，在文字里可能合成同一行。
        Map<String, Integer> plain = new LinkedHashMap<>();
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (BuildTaskRecord.CellNeed need : extra) {
            (need.exact() ? byName : plain)
                    .merge(need.stack().getHoverName().getString(), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        plain.forEach((name, n) -> parts.add(name + " x" + n));
        if (!byName.isEmpty()) {
            List<String> exactParts = new ArrayList<>();
            byName.forEach((name, n) -> exactParts.add(name + " x" + n));
            parts.add("and exactly these (same enchantments / patterns / contents, not just the"
                    + " same kind of item): " + String.join(", ", exactParts));
        }
        String tail = String.join(", ", parts);
        return bulk.isEmpty() ? tail : bulk + "; " + tail;
    }

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

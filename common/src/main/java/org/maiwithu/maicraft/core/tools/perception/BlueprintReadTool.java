package org.maiwithu.maicraft.core.tools.perception;

import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.blueprint.BlueprintStore;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.WorkProfile;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 读一张图纸:尺寸、用料、按层分布——不动世界一格。
 *
 * <p>此前这些只能作为<b>失败的副产品</b>出现:得先选好位置、发起施工、被拒绝,
 * 才知道要多少料。实测就是这个样子——盖屋顶、缺四十一块楼梯、跑去找工作台、
 * 找不到、再试、再缺,全程没有任何一步能提前回答"这栋房子要多少料"。
 *
 * <p>而生存模式的价值恰恰在那条链上:她设计 → 报料 → 一起去采 → 施工。报料是
 * 第二环,不该靠撞墙触发。
 */
public final class BlueprintReadTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    /** 一句话概览里点名几种,其余只报个数——完整清单在 data 里,那才是拿去采集的。 */
    private static final int NAMED_IN_HEADLINE = 5;
    /** 按层分布最多报几层,再多就分桶。 */
    private static final int MAX_LAYERS = 24;

    private record Args(String file, Integer x, Integer y, Integer z, Integer rotation) {}

    @Override
    public String name() {
        return "blueprint_read";
    }

    @Override
    public String description() {
        return "Read a blueprint without touching the world: overall size, cell count, the materials it "
                + "costs (biggest items first), and how its cells are spread across height — the layer "
                + "profile is what lets you reason about storeys, e.g. where a second floor or a roof "
                + "starts. Call this BEFORE proposing a build so you can tell the player what it will "
                + "take; in survival that shopping list is the whole point, since a large structure needs "
                + "several gathering trips. Pass the optional anchor x,y,z (and rotation) as well and you "
                + "also get what is ALREADY standing there, what is still missing, and what she is short "
                + "of right now — use that to resume a part-built structure or to check a site before "
                + "committing to it. Read-only; import progresses in the background. Use the blueprint tool to actually build.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file", Map.of("type", "string",
                "description", "Blueprint name from `blueprint action=list`, without extension."));
        props.put("x", Map.of("type", "integer",
                "description", "Optional anchor minimum X — give all three to also get site progress."));
        props.put("y", Map.of("type", "integer", "description", "Optional anchor bottom Y."));
        props.put("z", Map.of("type", "integer", "description", "Optional anchor minimum Z."));
        // 值域写描述不写 enum:代码对任意整数取模,enum 在这儿不添约束,
        // 而整数 enum 会被一部分只认字符串 enum 的上游拒收。
        props.put("rotation", Map.of("type", "integer",
                "description", "Optional clockwise rotation (0/90/180/270), matching the build you plan."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("file"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a.file() == null || a.file().isBlank()) {
            throw new IllegalArgumentException("file is required; use `blueprint action=list` first");
        }
        boolean anchored = a.x() != null && a.y() != null && a.z() != null;
        BlockPos anchor = anchored ? new BlockPos(a.x(), a.y(), a.z()) : BlockPos.ZERO;
        int quarters = a.rotation() == null ? 0 : Math.floorMod(a.rotation(), 360) / 90;
        org.maiwithu.maicraft.core.blueprint.BlueprintPreparation.read(
                companion, toolCallId, a.file(), anchor, quarters,
                new ReadReport(a), reply);
    }

    private static final class ReadReport implements org.maiwithu.maicraft.core.blueprint.BlueprintPreparation.Report {
        private final Args a;
        private final boolean anchored;
        private final Map<Item, Integer> cost = new LinkedHashMap<>();
        private final Map<Integer, Integer> byLayer = new TreeMap<>();
        private final Map<Item, Integer> remaining = new LinkedHashMap<>();
        private final Map<Item, Integer> unknownCost = new LinkedHashMap<>();
        private final Map<String, Integer> extra = new LinkedHashMap<>();
        private final Map<String, Integer> exact = new LinkedHashMap<>();
        private int placed, clears, unknownSiteCells, baseY;
        private java.util.Iterator<List<BuildTaskRecord.CellNeed>> needs;
        private java.util.Iterator<BuildTaskRecord.EntitySpawn> spawns;
        private java.util.Iterator<BuildTaskRecord.Target> targets;

        ReadReport(Args a) {
            this.a = a;
            anchored = a.x() != null && a.y() != null && a.z() != null;
        }

        @Override public TaskResult tick(LocalPlayer companion, BlueprintStore.Loaded loaded) {
            var level = ClientRuntime.requireContext(companion).level();
            var siteView = (org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView)
                    org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView.of(level);
            if (needs == null) {
                needs = loaded.cellNeeds().values().iterator();
                spawns = loaded.entities().iterator();
                targets = loaded.targets().iterator();
                baseY = loaded.targets().stream().mapToInt(t -> t.pos().getY()).min().orElse(0);
            }
            long deadline = System.nanoTime() + 2_000_000L;
            int remainingBudget = 128;
            while (needs.hasNext() && remainingBudget-- > 0 && System.nanoTime() < deadline) {
                var list = needs.next();
                for (var need : list) {
                    (need.exact() ? exact : extra)
                            .merge(need.stack().getHoverName().getString(), 1, Integer::sum);
                }
            }
            if (needs.hasNext()) return null;
            while (spawns.hasNext() && remainingBudget-- > 0 && System.nanoTime() < deadline) {
                var spawn = spawns.next();
                for (var stack : spawn.payload(level.registryAccess())) {
                    exact.merge(stack.getHoverName().getString(), 1, Integer::sum);
                }
            }
            if (spawns.hasNext()) return null;
            while (targets.hasNext() && remainingBudget-- > 0 && System.nanoTime() < deadline) {
                BuildTaskRecord.Target t = targets.next();
                byLayer.merge(t.pos().getY() - baseY, 1, Integer::sum);
                if (!t.costsMaterial()) {
                    clears++;
                    continue;
                }
                // 有料单的格不进普通清单,否则同一面旗帜/同一个花盆会被索要两次
                if (loaded.cellNeeds().containsKey(t.pos().asLong())) {
                    continue;
                }
                // 件数问 Target 要,不在这里另数一遍:清单与实扣一旦不同源,玩家
                // 按清单备齐了照样建到一半停下(双层砖一格两件就是这么漏掉的)
                cost.merge(t.item(), t.materialCount(), Integer::sum);
                if (anchored) {
                    if (!siteView.isLoaded(t.pos().getX(), t.pos().getZ())) {
                        unknownSiteCells++;
                        unknownCost.merge(t.item(), t.materialCount(), Integer::sum);
                    } else if (t.matches(siteView.getBlockState(t.pos()))) {
                        placed++;
                    } else {
                        remaining.merge(t.item(), t.materialCount(), Integer::sum);
                    }
                }
            }

            if (targets.hasNext()) return null;
            Map<String, Object> data = new LinkedHashMap<>();
            var size = loaded.size();
            data.put("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
            data.put("cells", loaded.targets().size());
            data.put("cells_costing_materials", sum(cost));
            if (clears > 0) {
                data.put("cells_that_only_clear", clears);
            }
            if (loaded.dropped() > 0) {
                // 掉格要在<b>报价这一步</b>就说清:模型正是在这里决定要不要建、缺什么料。
                // 不说的话它拿到的格数就是"全部",而设计已经缺了一块,谁都不知道。
                data.put("cells_dropped", loaded.dropped()
                        + " (liquids, or blocks with no item to pay with — she will not build these)");
            }
            data.put("materials", summarize(cost));
            // 这两项和 materials 一样出 map 而不是拼好的字符串:模型要拿它们做算术(还差
            // 几件、够不够),给字符串等于逼它先解析我们的排版。同一份数据两种形状,是给
            // 自己找的麻烦。
            if (!extra.isEmpty()) {
                // 一格多件的那些(带花的花盆是盆加花两件),单列出来才对得上实扣
                data.put("materials_for_multi_item_cells", extra);
            }
            if (!exact.isEmpty()) {
                data.put("materials_needing_an_exact_match", exact);
                data.put("exact_match_means",
                        "same patterns / enchantments / contents, not just the same kind of item");
            }
            data.put("layer_profile", layerProfile(byLayer));

            StringBuilder msg = new StringBuilder();
            msg.append(a.file()).append(": ").append(size.getX()).append('x').append(size.getY())
                    .append('x').append(size.getZ()).append(", ").append(loaded.targets().size())
                    .append(" cells, needs ").append(sum(cost)).append(" items across ")
                    .append(cost.size()).append(" kinds — ").append(topLine(cost));

            if (anchored) {
                data.put("already_standing_visible", placed);
                data.put("still_to_place_visible", sum(remaining));
                if (unknownSiteCells == 0) {
                    data.put("still_to_place", sum(remaining));
                } else {
                    data.put("site_cells_unknown_unloaded", unknownSiteCells);
                    data.put("materials_if_unknown_cells_are_missing", summarize(unknownCost));
                }
                msg.append(". At this anchor, loaded client facts show ").append(placed)
                        .append(" already standing and ").append(sum(remaining)).append(" still to place");
                if (unknownSiteCells > 0) {
                    msg.append("; ").append(unknownSiteCells)
                            .append(" cells are outside loaded chunks and remain unknown");
                }
                if (WorkProfile.of(companion).freeMaterials()) {
                    msg.append("; she builds free of charge in this mode");
                } else {
                    Map<Item, Integer> shortOf = new LinkedHashMap<>();
                    Map<Item, Integer> conservativeRemaining = new LinkedHashMap<>(remaining);
                    unknownCost.forEach((item, amount) -> conservativeRemaining.merge(item, amount, Integer::sum));
                    for (var e : conservativeRemaining.entrySet()) {
                        // 和逐格闸门、实扣同源的 36 格口径。用 41 格的那个会让报价说"料够了"
                        // 而施工每格都判缺料——副手上那叠木板正是这么骗过报价的。
                        int have = PlayerInv.buildableCount(companion.getInventory(), e.getKey());
                        if (have < e.getValue()) {
                            shortOf.put(e.getKey(), e.getValue() - have);
                        }
                    }
                    data.put(unknownSiteCells == 0 ? "short_of" : "short_of_if_unknown_cells_are_missing",
                            summarize(shortOf));
                    msg.append(shortOf.isEmpty()
                            ? "; she is carrying enough to finish it"
                            : "; still short " + topLine(shortOf));
                }
            }
            return TaskResult.ok(msg.toString(), data);
        }
    }

    private static int sum(Map<Item, Integer> m) {
        int n = 0;
        for (int v : m.values()) {
            n += v;
        }
        return n;
    }

    /**
     * 按数量降序<b>列全</b>——这张单子是拿去采集的,截断了就没法用。
     *
     * <p>此前只列前十种。日式小屋有 169 种方块,模型看到十种加一句"还有 159 种"——那个
     * 数字回答不了"我该去挖什么"。全量列出来约 5KB,而这是模型<b>建之前主动调、只调一次</b>
     * 的工具,正是该花这点 token 的地方。
     *
     * <p>真正该截断的是<b>施工中途</b>的缺料回执:那个是不请自来、而且会反复出现的。
     * 两者的区别不在长短,在<b>谁在什么时候要它</b>。
     */
    private static Map<String, Object> summarize(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> all = new LinkedHashMap<>();
        for (var e : sorted) {
            all.put(label(e.getKey()), e.getValue());
        }
        out.put("items", all);
        out.put("total_items", sum(counts));
        out.put("total_kinds", counts.size());
        return out;
    }

    private static String topLine(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        List<String> parts = new ArrayList<>();
        int listed = Math.min(NAMED_IN_HEADLINE, sorted.size());
        for (int i = 0; i < listed; i++) {
            parts.add(label(sorted.get(i).getKey()) + " x" + sorted.get(i).getValue());
        }
        String head = String.join(", ", parts);
        return sorted.size() > listed
                ? head + " and " + (sorted.size() - listed) + " more kinds"
                : head;
    }

    /**
     * 每一层有多少格。这是让模型能推断"二楼大概在哪一层"的原料——只报总尺寸的话,
     * "去掉二楼"这种要求它无从下手。层数太多就分桶,免得刷屏。
     */
    private static Map<String, Integer> layerProfile(Map<Integer, Integer> byLayer) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (byLayer.isEmpty()) {
            return out;
        }
        int levels = byLayer.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        int bucket = Math.max(1, (levels + MAX_LAYERS - 1) / MAX_LAYERS);
        for (var e : byLayer.entrySet()) {
            int lo = (e.getKey() / bucket) * bucket;
            String key = bucket == 1 ? ("y+" + lo) : ("y+" + lo + ".." + (lo + bucket - 1));
            out.merge(key, e.getValue(), Integer::sum);
        }
        return out;
    }

    private static String label(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}

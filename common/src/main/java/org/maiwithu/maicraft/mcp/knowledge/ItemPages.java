// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions.ItemDescription;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 物品资料页：一件物品或方块的总入口。LLM 想知道"这是什么、怎么用、怎么做、在哪用"时先读这一页。
 *
 * <p>这一页不重复别处的正文，只给事实和去处：名字、方块状态、鼠标悬停与按住 Shift、Ctrl 看到的说明（当前语言，
 * 和玩家看到的一样），配方有几条、去哪查，以及别的资料来源里跟它有关的条目（思索场景、要交它的任务书条目……）。
 * 某个来源没装、没加载好，在"资料来源现状"里如实写，不写成"没有"。
 *
 * <p>只在客户端线程上读：悬停说明、配方、别的来源都要读游戏现场。
 */
public final class ItemPages implements KnowledgeSource {
    /** 物品资料页地址的前缀，后面接命名空间与路径。 */
    public static final String PREFIX = "maicraft://knowledge/item/";
    /** 物品资料页怎么用的那一篇，放进资料目录。 */
    public static final String INDEX = PREFIX + "index";
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final ReadsItemDescriptions items;
    private final RecipeLookup recipes;
    private final List<KnowledgeSource> others;

    /**
     * @param items   读物品说明
     * @param recipes 配方查询：页上写配方有几条、去哪查
     * @param others  别的资料来源（思索、任务书……）：页上列它们跟这件物品有关的条目与现状
     */
    public ItemPages(ReadsItemDescriptions items, RecipeLookup recipes, List<KnowledgeSource> others) {
        this.items = Objects.requireNonNull(items, "items");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.others = List.copyOf(others);
    }

    /** 一件物品的资料页地址，例如 create:mechanical_mixer → maicraft://knowledge/item/create/mechanical_mixer。 */
    public static String uri(String itemId) {
        return PREFIX + itemId.replaceFirst(":", "/");
    }

    /** 这个写法是不是物品或方块的注册 ID（带冒号、不是资料地址）。 */
    public static boolean looksLikeItemId(String id) {
        return !id.contains("://") && ITEM_ID.matcher(id).matches();
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(INDEX, "item.index", "物品资料页",
                "每件物品、方块一页：说明、按住 Shift 的用法、思索、配方与相关资料；用物品 ID 读，例如 id=minecraft:furnace",
                "物品 方块 资料 用法 item block"));
    }

    // 搜物品：名字或 ID 对得上全部关键词的，每件一条资料页。
    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
        List<String> terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[\\s,，、;；。?？!！]+"))
                .filter(term -> !term.isEmpty()).toList();
        if (terms.isEmpty()) return List.of();
        List<KnowledgeDocument.Entry> entries = new ArrayList<>();
        for (String id : items.search(terms)) {
            String name = items.nameOf(id);
            entries.add(new KnowledgeDocument.Entry(uri(id), "item." + id, name, "物品资料页 · " + id, id + " " + name));
        }
        return List.copyOf(entries);
    }

    @Override public KnowledgeDocument read(String uri) {
        if (uri.equals(INDEX)) return index();
        if (!uri.startsWith(PREFIX)) return null;
        String tail = uri.substring(PREFIX.length());
        int slash = tail.indexOf('/');
        if (slash <= 0) return null;
        String itemId = tail.substring(0, slash) + ":" + tail.substring(slash + 1);
        Optional<ItemDescription> described = items.describe(itemId);
        if (described.isEmpty()) return null;
        ItemDescription item = described.get();
        return new KnowledgeDocument(uri, "item." + item.id(), item.name(), "物品资料页 · " + item.id(), page(item));
    }

    // 物品资料页本身是总入口，不是任何来源的"现状"：资料目录的现状一节里不出现它。
    @Override public String status() {
        return "";
    }

    private KnowledgeDocument index() {
        String text = """
                # 物品资料页

                每件物品、方块都有一页，用它的注册 ID 读：lookup(topic="knowledge", id="minecraft:furnace")。
                只知道中文名时先搜：lookup(topic="knowledge", query="熔炉")。

                一页里有：名字与方块状态；鼠标悬停看到的说明；按住 Shift、Ctrl 看到的用法（模组写了才有）；
                能做出它的配方有几条、它是哪几类配方的工作站、拿它当原料的配方有几条，以及去哪查；
                别的资料来源里跟它有关的条目（思索场景、要交它的任务书条目……）。
                """;
        return new KnowledgeDocument(INDEX, "item.index", "物品资料页", "物品资料页怎么用", text);
    }

    private String page(ItemDescription item) {
        StringBuilder text = new StringBuilder("# ").append(item.name()).append("（").append(item.id()).append("）\n\n");
        if (item.blockId() != null) {
            text.append("- 方块：").append(item.blockId());
            if (!item.blockProperties().isEmpty()) {
                text.append("；方块状态：");
                List<String> properties = new ArrayList<>();
                for (ReadsItemDescriptions.BlockProperty property : item.blockProperties()) {
                    properties.add(property.name() + "（" + String.join(" / ", property.values()) + "，默认 " + property.defaultValue() + "）");
                }
                text.append(String.join("、", properties));
            }
            text.append('\n');
        }
        lines(text, "鼠标悬停看到的说明", item.tooltip());
        if (!item.usageSummary().isEmpty() || !item.usage().isEmpty()) {
            text.append("\n按住 Shift 看到的用法：\n");
            if (!item.usageSummary().isEmpty()) text.append("  ").append(item.usageSummary()).append('\n');
            item.usage().forEach(line -> text.append("  - ").append(line.when()).append(" → ").append(line.result()).append('\n'));
        }
        if (!item.controls().isEmpty()) {
            text.append("\n按住 Ctrl 看到的操作：\n");
            item.controls().forEach(line -> text.append("  - ").append(line.when()).append(" → ").append(line.result()).append('\n'));
        }
        recipes(text, item.id());
        related(text, item.id());
        return text.toString();
    }

    private static void lines(StringBuilder text, String title, List<String> lines) {
        if (lines.isEmpty()) return;
        text.append('\n').append(title).append("：\n");
        lines.forEach(line -> text.append("  - ").append(line).append('\n'));
    }

    // 配方只给条数与去处：正文在 lookup(topic=recipe)，这里不重复。查不到时如实写原因。
    private void recipes(StringBuilder text, String itemId) {
        text.append("\n配方：\n");
        RecipeLookup.Answer making = recipes.making(itemId);
        if (!making.answered()) {
            text.append("  这次查不到：").append(String.join("；", making.notes())).append('\n');
            return;
        }
        text.append("  能做出它的配方 ").append(making.recipes().size()).append(" 条（读自 ").append(making.readFrom())
                .append("）→ lookup(topic=\"recipe\", id=\"").append(itemId).append("\")\n");
        RecipeLookup.Answer station = recipes.atWorkstation(itemId);
        RecipeLookup.Answer using = recipes.using(itemId);
        Set<String> categories = new LinkedHashSet<>();
        station.recipes().stream().map(ShownRecipe::categoryName).forEach(categories::add);
        if (!categories.isEmpty()) {
            text.append("  它是 ").append(String.join("、", categories.stream().map(name -> "\"" + name + "\"").toList()))
                    .append(" 这几类配方的工作站，共 ").append(station.recipes().size()).append(" 条\n");
        }
        text.append("  拿它当原料或催化剂的配方 ").append(using.recipes().size()).append(" 条\n");
        if (!categories.isEmpty() || !using.recipes().isEmpty()) {
            text.append("  → lookup(topic=\"recipe\", id=\"").append(itemId).append("\", uses=true)\n");
        }
        Set<String> notes = new LinkedHashSet<>(making.notes());
        if (notes.isEmpty()) return;
        notes.forEach(note -> text.append("  说明：").append(note).append('\n'));
    }

    // 别的来源跟它有关的条目，同一地址只列一次；没有时说"没有"，并附各来源现状，免得把"没装"读成"没有"。
    private void related(StringBuilder text, String itemId) {
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        for (KnowledgeSource source : others) {
            source.entriesAbout(itemId).forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        }
        text.append("\n相关资料：\n");
        if (entries.isEmpty()) {
            text.append("  别的资料来源里没有跟它有关的条目\n");
        } else {
            entries.values().forEach(entry -> text.append("  - ").append(entry.title()).append("：").append(entry.uri()).append('\n'));
        }
        List<String> statuses = others.stream().map(KnowledgeSource::status)
                .filter(status -> status != null && !status.isBlank()).toList();
        text.append("\n资料来源现状：\n");
        if (statuses.isEmpty()) {
            text.append("  这个实例没有登记模组的资料来源（思索、任务书）\n");
        } else {
            statuses.forEach(status -> text.append("  - ").append(status).append('\n'));
        }
    }
}

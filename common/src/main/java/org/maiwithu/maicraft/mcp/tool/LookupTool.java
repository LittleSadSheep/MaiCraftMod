// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeNotReady;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.RecipePages;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * lookup：查资料。不带 id 和 query 时返回全部能力的一行签名，宿主开局可以直接放进上下文；
 * 带 id 时返回这个能力的完整说明与参数表；带 query 时按名字和用途搜索。
 *
 * <p>topic=knowledge 查资料：随包的机制常识、物品资料页、联动模组登记的资料（思索、任务书……）。
 * 不带 id、query 列目录并附各来源现状，query 按关键词找，id 读一篇；id 给物品的注册 ID 读它的物品资料页。
 * 物品资料页与模组的资料要读游戏现场，在客户端线程上读；角色不在世界里时只查得到随包常识，并如实说明。
 *
 * <p>topic=recipe 查配方：id 是物品 ID，默认给配方页（怎么做出它），uses=true 给用途页（能拿它做什么、
 * 它当工作站能做什么）；query 按名字找物品 ID。配方要读游戏现场，角色不在世界里时回答 not_in_world。
 * Wiki 还没接上，明确回答还没接上。
 */
public final class LookupTool implements McpTool {
    private static final List<String> TOPICS = List.of("abilities", "knowledge", "wiki", "recipe");
    /** 一篇资料一刻准备不完时最多等多久；客户端每刻接着准备一点。 */
    private static final Duration PREPARE_WAIT = Duration.ofSeconds(10);

    private final AbilityRegistry registry;
    private final KnowledgeLibrary knowledge;
    // 角色不在世界里时的退路：只有随包常识，不碰游戏现场。
    private final KnowledgeLibrary offline = KnowledgeLibrary.offline();
    private final RecipeLookup recipes;
    private final ReadsItemDescriptions items;
    private final ClientThread clientThread;

    /**
     * @param knowledge    知识库：随包常识、物品资料页与联动模组的资料来源
     * @param recipes      配方查询
     * @param items        读物品名字、按名字找物品
     * @param clientThread 读游戏现场的部分交给客户端线程
     */
    public LookupTool(AbilityRegistry registry, KnowledgeLibrary knowledge, RecipeLookup recipes,
                      ReadsItemDescriptions items, ClientThread clientThread) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.items = Objects.requireNonNull(items, "items");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
    }

    /** 离线的 lookup：角色永远不在世界里，只查得到能力与随包常识；接口快照与离线测试用。 */
    public LookupTool(AbilityRegistry registry, KnowledgeLibrary knowledge) {
        this(registry, knowledge, new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)), NO_ITEMS, NEVER_IN_WORLD);
    }

    /** 离线时没有物品可读。 */
    private static final ReadsItemDescriptions NO_ITEMS = new ReadsItemDescriptions() {
        @Override public Optional<ItemDescription> describe(String itemId) {
            return Optional.empty();
        }

        @Override public List<String> search(List<String> terms) {
            return List.of();
        }

        @Override public String nameOf(String itemId) {
            return itemId;
        }
    };

    /** 离线时角色永远不在世界里。 */
    private static final ClientThread NEVER_IN_WORLD = new ClientThread() {
        @Override public <T> T call(Function<TickContext, T> work) {
            throw new NotInWorld();
        }
    };

    @Override public String name() {
        return ToolCatalog.LOOKUP;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("topic", "id", "query", "url", "uses"));
        String topic = check.choice(arguments, "topic", "topic", TOPICS);
        String id = check.text(arguments, "id", "id", false);
        String query = check.text(arguments, "query", "query", false);
        String url = check.text(arguments, "url", "url", false);
        Boolean uses = check.bool(arguments, "uses", "uses");
        if (id != null && query != null) check.error("query", "id 和 query 只能给一个", "精确查用 id，搜索用 query");
        if (url != null && !"wiki".equals(topic)) check.error("url", "url 只在 topic=wiki 时用", null);
        if (uses != null && !"recipe".equals(topic)) check.error("uses", "uses 只在 topic=recipe 时用", null);
        if ("recipe".equals(topic) && id == null && query == null) {
            check.error("id", "topic=recipe 要给物品 ID（id）或名字（query）", "例如 id=\"minecraft:furnace\"");
        }
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        if ("knowledge".equals(topic)) {
            return knowledgeNow(id, query, check);
        }
        if ("recipe".equals(topic)) {
            return recipeNow(id, query, Boolean.TRUE.equals(uses), check);
        }
        if (topic != null && !topic.equals("abilities")) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER,
                    "topic=" + topic + " 还没有接上，目前能查 abilities、knowledge 和 recipe");
        }
        if (id != null) {
            return ability(id, check);
        }
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);
        JsonArray list = new JsonArray();
        for (AbilityModule module : registry.all()) {
            AbilitySpec spec = module.spec();
            if (spec.listing() != Listing.LISTED) continue;
            if (needle != null && !spec.id().contains(needle) && !spec.summary().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            list.add(signature(spec));
        }
        JsonObject data = new JsonObject();
        data.add("abilities", list);
        return ToolReply.ok(data, check.notes(), null);
    }

    // 查资料在客户端线程上做：物品资料页与模组的资料都读游戏现场。一篇资料一刻准备不完（思索场景要在演示世界里放一遍）
    // 就下一刻接着读同一篇，等到准备好或超时；超时如实说还在准备，不拿半截冒充完整的。
    // 角色不在世界里时退到只有随包常识的那份，并如实说这一次查不到物品与模组的资料；客户端迟迟轮不到就回答 busy。
    private JsonObject knowledgeNow(String id, String query, RequestCheck check) {
        long deadline = System.nanoTime() + PREPARE_WAIT.toNanos();
        while (true) {
            try {
                return knowledgeOnce(id, query, check);
            } catch (KnowledgeNotReady preparing) {
                if (System.nanoTime() > deadline) {
                    return ToolReply.error(ErrorCode.BUSY, "这篇资料还在准备：" + preparing.getMessage() + "；过一会儿再读同一篇");
                }
            }
        }
    }

    private JsonObject knowledgeOnce(String id, String query, RequestCheck check) {
        try {
            return clientThread.call(context -> knowledge(knowledge, id, query, new ArrayList<>(check.notes())));
        } catch (ClientThread.NotInWorld absent) {
            List<String> notes = new ArrayList<>(check.notes());
            notes.add("角色不在世界里：这次只查得到随包的机制常识，物品资料页与模组的资料要进了世界才读得到");
            return knowledge(offline, id, query, notes);
        } catch (ClientThread.Busy busy) {
            return ToolReply.error(ErrorCode.BUSY, "游戏这会儿腾不出手查资料：" + busy.getMessage() + "；稍后再试");
        }
    }

    /**
     * 查资料：给 id 读一篇正文（完整地址、末段名或物品的注册 ID 都行），给 query 按关键词找，都不给就列出目录并附各来源现状。
     * 常识只讲游戏规则，让角色去做时看对应能力的说明。
     */
    private static JsonObject knowledge(KnowledgeLibrary knowledge, String id, String query, List<String> notes) {
        if (id != null) {
            KnowledgeDocument document = knowledge.find(id).orElse(null);
            if (document == null) {
                return ToolReply.error(ErrorCode.UNKNOWN_ID, "没有这篇资料：" + id
                        + "；不带 id 调用 lookup(topic=knowledge) 看目录，或者用 query 按关键词找");
            }
            if (!document.uri().equals(id.strip())) {
                notes.add("id \"" + id + "\" 按 " + document.uri() + " 读取");
            }
            JsonObject data = new JsonObject();
            data.addProperty("id", document.uri());
            data.addProperty("title", document.title());
            data.addProperty("text", document.text());
            return ToolReply.ok(data, notes, null);
        }
        List<KnowledgeDocument.Entry> entries = query == null ? knowledge.listing() : knowledge.matching(query);
        JsonArray list = new JsonArray();
        for (KnowledgeDocument.Entry entry : entries) {
            JsonObject row = new JsonObject();
            row.addProperty("id", entry.uri());
            row.addProperty("title", entry.title());
            row.addProperty("summary", entry.description());
            list.add(row);
        }
        JsonObject data = new JsonObject();
        data.add("knowledge", list);
        if (query == null) {
            // 目录最后附各来源现状：没列出来的来源就是这个实例没有，列出来但读不了的写明为什么。
            JsonArray sources = new JsonArray();
            knowledge.sourceStatuses().forEach(sources::add);
            data.add("sources", sources);
        }
        JsonObject next = null;
        if (query != null) {
            // 找到了就建议读排在最前的那篇；没找到不等于没有这条规则，建议列出完整目录自己挑。
            JsonObject arguments = new JsonObject();
            arguments.addProperty("topic", "knowledge");
            if (!entries.isEmpty()) arguments.addProperty("id", entries.getFirst().uri());
            next = ToolReply.next(ToolCatalog.LOOKUP, arguments);
        }
        return ToolReply.ok(data, notes, next);
    }

    // 查配方在客户端线程上做：配方查看器与游戏配方表都在客户端线程上读。角色不在世界里时没有配方表可读。
    private JsonObject recipeNow(String id, String query, boolean uses, RequestCheck check) {
        try {
            return clientThread.call(context -> query != null ? recipeSearch(query, check) : recipe(id, uses, check));
        } catch (ClientThread.NotInWorld absent) {
            return ToolReply.error(ErrorCode.NOT_IN_WORLD, "角色不在世界里，读不到配方；进了世界再查");
        } catch (ClientThread.Busy busy) {
            return ToolReply.error(ErrorCode.BUSY, "游戏这会儿腾不出手查配方：" + busy.getMessage() + "；稍后再试");
        }
    }

    // 配方页或用途页：物品 ID 要在注册表里；查到的配方一条不省，跳过了哪个配方查看器、为什么写进 notes。
    private JsonObject recipe(String rawId, boolean uses, RequestCheck check) {
        String itemId = rawId.strip().toLowerCase(Locale.ROOT);
        Optional<ReadsItemDescriptions.ItemDescription> item = items.describe(itemId);
        if (item.isEmpty()) {
            return ToolReply.error(ErrorCode.UNKNOWN_ID, "没有这件物品：" + rawId
                    + "；只知道名字时用 lookup(topic=recipe, query=名字) 找它的 ID");
        }
        List<String> notes = new ArrayList<>(check.notes());
        JsonObject data;
        if (uses) {
            RecipeLookup.Answer asIngredient = recipes.using(itemId);
            RecipeLookup.Answer asWorkstation = recipes.atWorkstation(itemId);
            data = RecipePages.uses(itemId, item.get().name(), asIngredient, asWorkstation);
            addAll(notes, asIngredient.notes());
            addAll(notes, asWorkstation.notes());
        } else {
            RecipeLookup.Answer making = recipes.making(itemId);
            data = RecipePages.making(itemId, item.get().name(), making);
            addAll(notes, making.notes());
            if (making.answered() && making.recipes().isEmpty()) {
                notes.add("在 " + making.readFrom() + " 里没有做出它的配方；这不代表拿不到，可能要挖、打、交易或在世界里找");
            }
        }
        return ToolReply.ok(data, notes, null);
    }

    // 按名字找物品 ID：名字或 ID 对得上全部关键词的都列出来；next 建议读第一个的配方页。
    private JsonObject recipeSearch(String query, RequestCheck check) {
        List<String> terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[\\s,，、;；。?？!！]+"))
                .filter(term -> !term.isEmpty()).toList();
        List<String> found = terms.isEmpty() ? List.of() : items.search(terms);
        JsonArray list = new JsonArray();
        for (String itemId : found) {
            JsonObject row = new JsonObject();
            row.addProperty("id", itemId);
            row.addProperty("name", items.nameOf(itemId));
            list.add(row);
        }
        JsonObject data = new JsonObject();
        data.add("items", list);
        JsonObject next = null;
        if (!found.isEmpty()) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("topic", "recipe");
            arguments.addProperty("id", found.getFirst());
            next = ToolReply.next(ToolCatalog.LOOKUP, arguments);
        }
        return ToolReply.ok(data, check.notes(), next);
    }

    // 两份说明合并时去掉重复的句子：用途页的两部分多半由同一个查看器回答，跳过的原因会重复。
    private static void addAll(List<String> notes, List<String> more) {
        more.stream().filter(note -> !notes.contains(note)).forEach(notes::add);
    }

    /** 一个能力的完整说明：用途、能力说明正文、参数表、接受的目标对象、执行方式、需要的模组。 */
    private JsonObject ability(String rawId, RequestCheck check) {
        String id = rawId.contains(":") ? rawId.toLowerCase(Locale.ROOT)
                : registry.all().stream().map(module -> module.spec().id())
                .filter(candidate -> candidate.endsWith(":" + rawId.toLowerCase(Locale.ROOT))).findFirst()
                .orElse(ModIdentity.MOD_ID + ":" + rawId.toLowerCase(Locale.ROOT));
        AbilityModule module = registry.find(id).orElse(null);
        if (module == null) {
            List<String> missing = registry.missingModsFor(id);
            if (!missing.isEmpty()) {
                return ToolReply.error(ErrorCode.UNKNOWN_ABILITY, "能力 " + rawId + " 需要模组 "
                        + String.join("、", missing) + "，这个实例没装");
            }
            return ToolReply.error(ErrorCode.UNKNOWN_ABILITY, "没有能力 " + rawId + "；" + SimilarAbilities.hint(registry, id));
        }
        AbilitySpec spec = module.spec();
        JsonObject data = signature(spec);
        data.add("parameters", spec.paramSpecs().describe());
        JsonArray mods = new JsonArray();
        spec.requiredMods().stream().map(RequiredMod::modId).sorted().forEach(mods::add);
        data.add("required_mods", mods);
        JsonArray knowledge = new JsonArray();
        spec.knowledgeUris().forEach(knowledge::add);
        data.add("knowledge", knowledge);
        List<String> notes = new ArrayList<>(check.notes());
        try {
            data.addProperty("doc", spec.doc().load());
        } catch (IllegalStateException missing) {
            notes.add("这个能力还没有能力说明正文");
        }
        return ToolReply.ok(data, notes, null);
    }

    /** 一行签名：能力 ID、用途、参数名与类型、接受的目标对象、执行方式。 */
    private static JsonObject signature(AbilitySpec spec) {
        JsonObject json = new JsonObject();
        json.addProperty("ability", spec.id());
        json.addProperty("summary", spec.summary());
        List<String> params = new ArrayList<>();
        for (ParamSpec param : spec.paramSpecs().all()) {
            String type = param.type().schemaType();
            if (param.required()) {
                params.add(param.name() + ": " + type);
            } else if (param.defaultValue() != null) {
                params.add(param.name() + ": " + type + " = " + param.defaultValue());
            } else {
                params.add(param.name() + "?: " + type);
            }
        }
        json.addProperty("parameters", String.join(", ", params));
        JsonArray targets = new JsonArray();
        spec.targets().stream().map(ResultJson::lower).sorted().forEach(targets::add);
        json.add("targets", targets);
        json.addProperty("mode", ResultJson.lower(spec.mode()));
        return json;
    }
}

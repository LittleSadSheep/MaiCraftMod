// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * lookup：查资料。不带 id 和 query 时返回全部能力的一行签名，宿主开局可以直接放进上下文；
 * 带 id 时返回这个能力的完整说明与参数表；带 query 时按名字和用途搜索。
 *
 * <p>topic=knowledge 查随包的游戏机制常识：不带 id、query 列目录，query 按关键词找，id 读一篇正文。
 * Wiki 与配方两种 topic 还没接上，明确回答还没接上。
 */
public final class LookupTool implements McpTool {
    private static final List<String> TOPICS = List.of("abilities", "knowledge", "wiki", "recipe");

    private final AbilityRegistry registry;
    private final KnowledgeLibrary knowledge;

    public LookupTool(AbilityRegistry registry, KnowledgeLibrary knowledge) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    @Override public String name() {
        return ToolCatalog.LOOKUP;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("topic", "id", "query", "url"));
        String topic = check.choice(arguments, "topic", "topic", TOPICS);
        String id = check.text(arguments, "id", "id", false);
        String query = check.text(arguments, "query", "query", false);
        String url = check.text(arguments, "url", "url", false);
        if (id != null && query != null) check.error("query", "id 和 query 只能给一个", "精确查用 id，搜索用 query");
        if (url != null && !"wiki".equals(topic)) check.error("url", "url 只在 topic=wiki 时用", null);
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        if ("knowledge".equals(topic)) {
            return knowledge(id, query, check);
        }
        if (topic != null && !topic.equals("abilities")) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER,
                    "topic=" + topic + " 还没有接上，目前能查 abilities 和 knowledge");
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

    /**
     * 查游戏机制常识：给 id 读一篇正文（完整地址或末段名都行），给 query 按关键词找，都不给就列出目录。
     * 常识只讲游戏规则，让角色去做时看对应能力的说明；这里不读游戏世界。
     */
    private JsonObject knowledge(String id, String query, RequestCheck check) {
        List<String> notes = new ArrayList<>(check.notes());
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

    /** 一个能力的完整说明：用途、能力说明正文、参数表、接受的目标对象、执行方式、需要的模组。 */
    private JsonObject ability(String rawId, RequestCheck check) {
        String id = rawId.contains(":") ? rawId.toLowerCase(Locale.ROOT)
                : registry.all().stream().map(module -> module.spec().id())
                .filter(candidate -> candidate.endsWith(":" + rawId.toLowerCase(Locale.ROOT))).findFirst().orElse(rawId);
        AbilityModule module = registry.find(id).orElse(null);
        if (module == null) {
            return ToolReply.error(ErrorCode.UNKNOWN_ABILITY, "没有能力 " + rawId + "；" + SimilarAbilities.hint(registry, id));
        }
        AbilitySpec spec = module.spec();
        JsonObject data = signature(spec);
        data.add("parameters", spec.params().describe());
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
        for (Param param : spec.params().params()) {
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

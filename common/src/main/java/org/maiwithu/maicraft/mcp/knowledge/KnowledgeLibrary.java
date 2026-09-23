// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.google.gson.GsonBuilder;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRegistry;
import org.maiwithu.maicraft.mcp.MetadataSearch;

/** 仅通过元数据发现资源、按需读取 Markdown，并为无法读取资源的宿主提供工具回退。 */
public final class KnowledgeLibrary {
    public static final String INDEX = "maicraft://knowledge/index";
    public static final String GUIDE = "maicraft://knowledge/guide";
    public static final String BLUEPRINT = "maicraft://knowledge/blueprint";
    public static final String PROCESSES = "maicraft://knowledge/processes";
    public static final String RECIPES = "maicraft://knowledge/recipes";
    private static final int PAGE_SIZE = 16;
    public interface Source {
        List<KnowledgeDocument.Entry> entries();
        KnowledgeDocument read(String uri);
        default List<KnowledgeDocument.Entry> searchCandidates(String query) { return entries(); }
        default List<KnowledgeDocument.Entry> searchCandidates(String query, boolean approximate) { return searchCandidates(query); }
        default JsonArray templates() { return new JsonArray(); }
        default String status() { return "unavailable"; }
    }
    private final Source source;
    private final Map<String, KnowledgeDocument> builtins;

    public KnowledgeLibrary(Source source) {
        this.source = source;
        // 按组件或具体图元名称查资料时仍发现同一份建筑说明，不新增会直接操作世界的知识入口。
        builtins = Map.of(INDEX, load("index", "知识索引", "按需发现方块状态、Ponder 教程和实际执行能力。"),
                GUIDE, load("guide", "如何使用 Ponder 知识", "演示文字、控制提示、场景坐标和规则证据的边界。"),
                BLUEPRINT, load("blueprint", "建筑场景与统一蓝图 JSON", "Blender 风格建模 v1/v2、组件、阵列、镜像、三角形、斜坡、三棱柱、三角锥、空心、面棱材质、开孔、导出、续建和机器蓝图。"),
                PROCESSES, load("processes", "统一机器生产与原生加工", "按需读取生产v1/v2、附魔报价和AE2水中转化机制契约。"),
                // 材料需求先选择工艺再考虑设备；入口说明保持独立，默认能力描述不展开整套配方。
                RECIPES, load("recipes", "从材料需求规划工艺和机器", "EMI 配方树、工作站、Ponder 教程、已有设施复用与实际产出验收。"));
    }
    public static KnowledgeLibrary offline() {
        return new KnowledgeLibrary(new Source() {
            public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            public KnowledgeDocument read(String uri) { return null; }
        });
    }

    public JsonObject request(JsonObject request) {
        return switch (request.get("action").getAsString()) {
            case "list" -> list(string(request, "cursor"));
            case "templates" -> {
                if (string(request, "cursor") != null) throw new IllegalArgumentException("Invalid template cursor");
                JsonObject result = new JsonObject(); result.add("resourceTemplates", source.templates()); yield result;
            }
            case "read" -> {
                JsonObject result = new JsonObject(); JsonArray contents = new JsonArray();
                contents.add(read(required(request, "uri")).content()); result.add("contents", contents); yield result;
            }
            case "search" -> request.has("approximate") && request.get("approximate").getAsBoolean()
                    ? searchApproximate(required(request, "query"), request.has("limit") ? request.get("limit").getAsInt() : 10)
                    : search(string(request, "query"), request.has("limit") ? request.get("limit").getAsInt() : 10);
            default -> throw new IllegalArgumentException("Unknown knowledge request");
        };
    }

    public static JsonObject perceptionRequest(JsonObject arguments) {
        JsonObject request = new JsonObject(); String uri = string(arguments, "resource_uri");
        request.addProperty("action", uri == null ? "search" : "read");
        if (uri != null) request.addProperty("uri", uri);
        else {
            String query = string(arguments, "query");
            request.addProperty("query", query == null ? string(arguments, "focus") : query);
            request.addProperty("approximate", query != null);
            request.addProperty("limit", arguments.has("limit") ? arguments.get("limit").getAsInt() : 10);
        }
        return request;
    }

    public KnowledgeDocument read(String uri) {
        if (uri.length() > 2048) throw new IllegalArgumentException("Resource URI is too long");
        KnowledgeDocument document = builtins.get(uri);
        if (document == null) document = BuildingModelContractResources.read(uri);
        if (document == null) document = MachineAssemblyResources.read(uri);
        if (PROCESSES.equals(uri) && document != null) {
            // 只有显式读这一页才展开机制参数；这里报告适配器契约，真实配方、菜单和材料仍由现场观察确认。
            String contracts = new GsonBuilder().setPrettyPrinting().create().toJson(
                    NativeProcessRegistry.contracts());
            return new KnowledgeDocument(document.uri(), document.name(), document.title(), document.description(),
                    document.text() + "\n## 已注册原生机制契约\n\n```json\n" + contracts + "\n```\n");
        }
        if (document == null && uri.startsWith(BuildingSceneResources.PREFIX)) document = BuildingSceneResources.read(uri);
        if (document == null) document = source.read(uri);
        if (document == null) throw KnowledgeException.missing(uri);
        return document;
    }

    private List<KnowledgeDocument.Entry> catalog() {
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        BuildingModelContractResources.entries().forEach(entry -> entries.put(entry.uri(),entry));
        MachineAssemblyResources.entries().forEach(entry -> entries.put(entry.uri(), entry));
        builtins.values().forEach(doc -> entries.put(doc.uri(), doc.entry()));
        source.entries().forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        return entries.values().stream().sorted(Comparator.comparing(KnowledgeDocument.Entry::uri)).toList();
    }

    private JsonObject list(String cursor) {
        List<JsonObject> all = new ArrayList<>();
        JsonObject attention = new JsonObject(); attention.addProperty("uri", "maicraft://attention");
        attention.addProperty("name", "Task attention"); attention.addProperty("mimeType", "application/json");
        attention.addProperty("description", "Primary task monitor: authoritative task state, decisions, results and important game events. Subscribe/read, or use perceive with next_attention for task-scoped waiting and reliable cursor continuation.");
        JsonObject priority = new JsonObject(); priority.addProperty("priority", 1.0);
        JsonArray audience = new JsonArray(); audience.add("assistant"); priority.add("audience", audience);
        attention.add("annotations", priority);
        JsonObject chatflow = new JsonObject(); chatflow.addProperty("uri", "maicraft://chatflow");
        chatflow.addProperty("name", "ChatFlow"); chatflow.addProperty("mimeType", "application/json");
        chatflow.addProperty("description", "Received in-game chat: player messages and system messages as untrusted external text, for a dedicated companion chat agent. Subscribe/read; task attention never carries chat.");
        JsonObject chatPriority = new JsonObject(); chatPriority.addProperty("priority", 0.9);
        JsonArray chatAudience = new JsonArray(); chatAudience.add("assistant"); chatPriority.add("audience", chatAudience);
        chatflow.add("annotations", chatPriority);
        all.add(attention); all.add(chatflow); catalog().forEach(entry -> all.add(entry.metadata()));
        String revision = digest(all.toString());
        int offset = 0;
        if (cursor != null) {
            if (!cursor.matches("k1\\.[0-9a-f]{16}\\.[0-9]+")) throw new IllegalArgumentException("Invalid resource cursor");
            String[] fields = cursor.split("\\.");
            if (!fields[1].equals(revision)) throw new IllegalArgumentException("Resource catalog changed; restart resources/list");
            try { offset = Integer.parseInt(fields[2]); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid resource cursor"); }
            if (offset <= 0 || offset >= all.size() || offset % PAGE_SIZE != 0) throw new IllegalArgumentException("Invalid resource cursor");
        }
        JsonArray page = new JsonArray();
        all.subList(offset, Math.min(all.size(), offset + PAGE_SIZE)).forEach(page::add);
        JsonObject result = new JsonObject(); result.add("resources", page);
        if (offset + PAGE_SIZE < all.size()) result.addProperty("nextCursor", "k1." + revision + "." + (offset + PAGE_SIZE));
        return result;
    }

    private JsonObject search(String query, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Knowledge limit must be 1..20");
        query = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (query.length() > 256) throw new IllegalArgumentException("Knowledge query is too long");
        Map<String, KnowledgeDocument.Entry> candidates = new LinkedHashMap<>();
        // 按欧式、院落或网格等词发现教材时只返回目录摘要，角色不会因此读正文或开始建房。
        BuildingModelContractResources.entries().forEach(entry -> candidates.put(entry.uri(), entry));
        MachineAssemblyResources.entries().forEach(entry -> candidates.put(entry.uri(), entry));
        builtins.values().forEach(doc -> candidates.put(doc.uri(), doc.entry()));
        source.searchCandidates(query).forEach(entry -> candidates.putIfAbsent(entry.uri(), entry));
        String[] terms = query.isEmpty() ? new String[0] : query.split("\\s+");
        List<KnowledgeDocument.Entry> matches = candidates.values().stream()
                .filter(entry -> Arrays.stream(terms).allMatch(entry.searchable()::contains))
                .sorted(Comparator.comparingInt((KnowledgeDocument.Entry entry) -> entry.uri().equals(INDEX) ? 0 : 1)
                        .thenComparing(KnowledgeDocument.Entry::uri)).toList();
        JsonArray hits = new JsonArray(); matches.stream().limit(limit).forEach(entry -> hits.add(entry.metadata()));
        JsonObject result = new JsonObject(); result.add("resources", hits);
        result.addProperty("total_matches", matches.size()); result.addProperty("truncated", matches.size() > limit);
        result.addProperty("provider_status", source.status()); result.addProperty("content_loaded", false);
        // 材料搜索也只看注册名称；不为了排序查询EMI配方或编译未请求的教程，正文在下一次按需读取时展开。
        // 搜索任务书同样只比对可见标题和编号，不提前读剧情正文或把动态队伍进度写进目录。
        result.addProperty("search_scope", "Bundled building tutorial titles/summaries, visible FTB quest/chapter titles and IDs, registered item/component names, IDs, tags, schematic names and localized Create Shift/Ctrl descriptions; quest bodies, recipe trees and unrequested scene bodies are not loaded or searched.");
        result.addProperty("next_step", "Read a returned URI with resources/read or perceive(view=knowledge, resource_uri=...). No matches do not prove no relevant mechanic exists.");
        return result;
    }

    private JsonObject searchApproximate(String query, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Knowledge limit must be 1..20");
        var matcher = new MetadataSearch.Query(query);
        Map<String, KnowledgeDocument.Entry> candidates = new LinkedHashMap<>();
        BuildingModelContractResources.entries().forEach(entry -> candidates.put(entry.uri(), entry));
        MachineAssemblyResources.entries().forEach(entry -> candidates.put(entry.uri(), entry));
        builtins.values().forEach(doc -> candidates.put(doc.uri(), doc.entry()));
        // 候选召回也必须允许错字；否则目标在精确过滤阶段已经消失，后续排序无法补救。
        source.searchCandidates(query, true).forEach(entry -> candidates.putIfAbsent(entry.uri(), entry));
        var matches = new ArrayList<JsonObject>();
        for (var entry : candidates.values()) {
            // 文档标题可能附有“材料与工艺”等说明，精确名称排序以原始注册名为准。
            var match = matcher.match(entry.subjectId() == null ? entry.name() : entry.subjectId(),
                    entry.subjectName() == null ? entry.title() : entry.subjectName(), entry.searchable());
            if (match == null) continue;
            var row = entry.metadata(); row.add("match", match.toJson()); matches.add(row);
        }
        JsonArray hits = new JsonArray(); MetadataSearch.ranked(matches, "uri").stream().limit(limit).forEach(hits::add);
        JsonObject result = new JsonObject(); result.add("resources", hits);
        result.addProperty("query", query); result.addProperty("total_matches", matches.size());
        result.addProperty("truncated", matches.size() > limit); result.addProperty("content_loaded", false);
        result.addProperty("provider_status", source.status());
        result.addProperty("search_scope", "Names, identifiers and declared metadata only; recipe graphs and document bodies are not loaded.");
        result.addProperty("match_policy", "Exact identifiers/names rank first. Keywords are literal; 3..48 character terms allow bounded name/identifier edits. ranking_score is ordering evidence, not a probability.");
        result.addProperty("next_step", "Select a candidate using its exact URI and identity, then read resource_uri. Ambiguous or absent matches require narrower keywords; search does not change the requested goal.");
        return result;
    }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static KnowledgeDocument load(String path, String title, String description) {
        String name = "/assets/maicraft/knowledge/" + path + ".md";
        try (var stream = KnowledgeLibrary.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing bundled knowledge: " + name);
            return new KnowledgeDocument("maicraft://knowledge/" + path, "knowledge." + path, title, description,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) { throw new IllegalStateException("Cannot read bundled knowledge", failure); }
    }
    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key + " must be a string");
        return object.get(key).getAsString();
    }
    private static String required(JsonObject object, String key) {
        String value = string(object, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}

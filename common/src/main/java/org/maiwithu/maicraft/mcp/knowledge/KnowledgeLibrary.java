// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.NoSuchAlgorithmException;

/**
 * 知识库：通过目录元数据发现资源、按需读取正文，并为没有资源读取能力的宿主提供搜索回退。
 *
 * <p>内置资料随包发布（索引与游戏机制常识）；其余资料由登记的知识来源提供。
 * 目录与搜索只比较元数据，不读正文，也不读取游戏世界。
 */
public final class KnowledgeLibrary {
    public static final String INDEX = "maicraft://knowledge/index";
    public static final String GAME_MECHANICS_PREFIX = "maicraft://knowledge/game_mechanics/";
    private static final int PAGE_SIZE = 16;

    /**
     * 游戏机制常识条目：slug、标题、检索描述；描述带中英关键词与注册 ID，
     * 精确与近似搜索都按条目元数据匹配。
     */
    static final String[][] GAME_MECHANICS = {
            {"gravity-blocks", "重力方块与塌落", "gravel sand 落沙 gravity 塌方 塌落 掩埋掉落物 瞬时 no_path 落方块伤害"},
            {"fluid-flow", "流体流动与灌满", "water lava 水 岩浆 流动 灌满 倒水 自救 淹 黑曜石 生成 隧道进水"},
            {"item-drops", "掉落物物理", "item entity 掉落物 拾取半径 漂移 消失 despawn remaining_live_drops 没进背包 确认"},
            {"drop-rates", "关键掉率与方差", "flint 燧石 10% 掉率 方差 fortune 时运 loot 战利品表 掉落概率 苹果 树苗"},
            {"tool-tiers", "工具等级与挖掘资格", "tool tier 工具等级 wrong_tool 黑曜石 obsidian diamond_pickaxe 镐 挖不动 不掉落 挖掘资格"},
            {"ore-heights", "mine 源查询的范围语义", "mine 源查询 半径 深度 query_complete known_sources 保留意见 暴露源 埋藏矿 扫描范围"},
            {"food", "食物与饥饿", "food eat 食物 饥饿 进食 consume 饱食度 saturation 营养 打猎 狩猎 耕种 种植 农田 补种 小麦 胡萝卜 土豆 甜菜 面包 cook 烹饪 熔炉"},
            {"sleep-night", "睡眠与夜晚", "sleep bed 睡觉 床 幻翼 phantom 夜晚 night 刷怪 spawn 羊毛 wool sheep_color 染料 dye 同色 重生点 respawn insomnia 跳夜"},
            {"tunneling", "下降掘进与寻路死角", "descent digging staircase tunnel 斜向阶梯 竖井 planning_stall no_path 寻路死角 树冠 下掘 水平掘进 矿带 travel"},
            {"lighting", "挖掘工作面照明", "lighting torch 火把 照明 光照 刷怪 spawn 黑暗 洞穴 深掘 营地 auto_light light_area 布光 煤 木棍 合成"},
            {"tick-rate", "世界刻速与失焦限流", "tps tick 刻速 刻率 失焦 focus 限流 throttle 慢放 停滞 冻结 game_time recent_tps tick_rate 任务变慢 卡死"},
    };

    private final KnowledgeSource source;
    private final Map<String, KnowledgeDocument> builtins;

    public KnowledgeLibrary(KnowledgeSource source) {
        this.source = source;
        // 内置资料随包发布；缺一篇就在启动时失败，不把空正文当知识交给模型。
        Map<String, KnowledgeDocument> docs = new LinkedHashMap<>();
        docs.put(INDEX, load("index", "知识索引", "按需发现游戏机制常识、已登记的资料来源和检索方式。"));
        for (String[] entry : GAME_MECHANICS)
            docs.put(GAME_MECHANICS_PREFIX + entry[0], load("game_mechanics/" + entry[0], entry[1], entry[2]));
        builtins = Map.copyOf(docs);
    }

    /** 没有任何登记来源时的空知识库：只剩内置资料，来源状态如实标为不可用。 */
    public static KnowledgeLibrary offline() {
        return new KnowledgeLibrary(new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            @Override public KnowledgeDocument read(String uri) { return null; }
        });
    }

    /** 知识请求的四类操作：列目录、读模板、读正文、搜索；未知操作明确报错。 */
    public JsonObject request(JsonObject request) {
        return switch (request.get("action").getAsString()) {
            case "list" -> list(string(request, "cursor"));
            case "templates" -> {
                if (string(request, "cursor") != null) throw new IllegalArgumentException("Invalid template cursor");
                JsonObject result = new JsonObject();
                result.add("resourceTemplates", source.templates());
                yield result;
            }
            case "read" -> {
                JsonObject result = new JsonObject();
                JsonArray contents = new JsonArray();
                contents.add(read(required(request, "uri")).content());
                result.add("contents", contents);
                yield result;
            }
            case "search" -> request.has("approximate") && request.get("approximate").getAsBoolean()
                    ? searchApproximate(required(request, "query"), request.has("limit") ? request.get("limit").getAsInt() : 10)
                    : search(string(request, "query"), request.has("limit") ? request.get("limit").getAsInt() : 10);
            default -> throw new IllegalArgumentException("Unknown knowledge request");
        };
    }

    public KnowledgeDocument read(String uri) {
        if (uri.length() > 2048) throw new IllegalArgumentException("Resource URI is too long");
        KnowledgeDocument document = builtins.get(uri);
        if (document == null) document = source.read(uri);
        // 目录之外的地址不偷换成相近正文；调用方须重新发现目录。
        if (document == null) throw KnowledgeException.missing(uri);
        return document;
    }

    private List<KnowledgeDocument.Entry> catalog() {
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        builtins.values().forEach(doc -> entries.put(doc.uri(), doc.entry()));
        source.entries().forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        return entries.values().stream()
                .sorted(Comparator.comparing(KnowledgeDocument.Entry::uri)).toList();
    }

    /** 资源目录分页；游标绑定目录版本，目录变了旧游标明确报错而不是悄悄换页。 */
    private JsonObject list(String cursor) {
        List<JsonObject> all = new ArrayList<>();
        catalog().forEach(entry -> all.add(entry.metadata()));
        String revision = digest(all.toString());
        int offset = 0;
        if (cursor != null) {
            if (!cursor.matches("k1\\.[0-9a-f]{16}\\.[0-9]+")) throw new IllegalArgumentException("Invalid resource cursor");
            String[] fields = cursor.split("\\.");
            if (!fields[1].equals(revision)) throw new IllegalArgumentException("Resource catalog changed; restart resources/list");
            try {
                offset = Integer.parseInt(fields[2]);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid resource cursor");
            }
            if (offset <= 0 || offset >= all.size() || offset % PAGE_SIZE != 0) throw new IllegalArgumentException("Invalid resource cursor");
        }
        JsonArray page = new JsonArray();
        all.subList(offset, Math.min(all.size(), offset + PAGE_SIZE)).forEach(page::add);
        JsonObject result = new JsonObject();
        result.add("resources", page);
        if (offset + PAGE_SIZE < all.size()) result.addProperty("nextCursor", "k1." + revision + "." + (offset + PAGE_SIZE));
        return result;
    }

    private JsonObject search(String query, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Knowledge limit must be 1..20");
        String cleaned = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (cleaned.length() > 256) throw new IllegalArgumentException("Knowledge query is too long");
        Map<String, KnowledgeDocument.Entry> candidates = new LinkedHashMap<>();
        builtins.values().forEach(doc -> candidates.put(doc.uri(), doc.entry()));
        source.searchCandidates(cleaned).forEach(entry -> candidates.putIfAbsent(entry.uri(), entry));
        String[] terms = cleaned.isEmpty() ? new String[0] : cleaned.split("\\s+");
        List<KnowledgeDocument.Entry> matches = candidates.values().stream()
                .filter(entry -> Arrays.stream(terms).allMatch(entry.searchable()::contains))
                .sorted(Comparator.comparingInt((KnowledgeDocument.Entry entry) -> entry.uri().equals(INDEX) ? 0 : 1)
                        .thenComparing(KnowledgeDocument.Entry::uri)).toList();
        JsonArray hits = new JsonArray();
        matches.stream().limit(limit).forEach(entry -> hits.add(entry.metadata()));
        JsonObject result = new JsonObject();
        result.add("resources", hits);
        result.addProperty("total_matches", matches.size());
        result.addProperty("truncated", matches.size() > limit);
        result.addProperty("provider_status", source.status());
        result.addProperty("content_loaded", false);
        // 搜索只比较目录元数据；正文、配方与动态进度不在搜索时展开。
        result.addProperty("search_scope",
                "Bundled titles, summaries and keywords plus registered catalog metadata; document bodies are not loaded or searched.");
        result.addProperty("next_step",
                "Read a returned URI with resources/read. No matches do not prove no relevant mechanic exists.");
        return result;
    }

    /** 近似搜索：允许条目名有错字、漏字；命中依据随条目返回，排序值不是概率。 */
    private JsonObject searchApproximate(String query, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Knowledge limit must be 1..20");
        var matcher = new MetadataSearch.Query(query);
        Map<String, KnowledgeDocument.Entry> candidates = new LinkedHashMap<>();
        builtins.values().forEach(doc -> candidates.put(doc.uri(), doc.entry()));
        // 候选召回也必须允许错字；否则目标在精确过滤阶段已经消失，后续排序无法补救。
        source.searchCandidates(query, true).forEach(entry -> candidates.putIfAbsent(entry.uri(), entry));
        var matches = new ArrayList<JsonObject>();
        for (var entry : candidates.values()) {
            // 文档标题可能附有说明文字，精确名称排序以原始注册名为准。
            var match = matcher.match(entry.subjectId() == null ? entry.name() : entry.subjectId(),
                    entry.subjectName() == null ? entry.title() : entry.subjectName(), entry.searchable());
            if (match == null) continue;
            var row = entry.metadata();
            row.add("match", match.toJson());
            matches.add(row);
        }
        JsonArray hits = new JsonArray();
        MetadataSearch.ranked(matches, "uri").stream().limit(limit).forEach(hits::add);
        JsonObject result = new JsonObject();
        result.add("resources", hits);
        result.addProperty("query", query);
        result.addProperty("total_matches", matches.size());
        // 与其他搜索共用零命中反馈，调用者可以收缩关键词而不必重载整份目录。
        matcher.describe(result, matches.isEmpty());
        result.addProperty("truncated", matches.size() > limit);
        result.addProperty("content_loaded", false);
        result.addProperty("provider_status", source.status());
        result.addProperty("search_scope", "Names, identifiers and declared metadata only; document bodies are not loaded.");
        result.addProperty("match_policy",
                "Exact identifiers/names rank first. Keywords are literal; 3..48 character terms allow bounded name/identifier edits. ranking_score is ordering evidence, not a probability.");
        result.addProperty("next_step",
                "Select a candidate using its exact URI and identity, then read it. Ambiguous or absent matches require narrower keywords; search does not change the requested goal.");
        return result;
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static KnowledgeDocument load(String path, String title, String description) {
        String name = "/assets/maicraft/knowledge/" + path + ".md";
        try (var stream = KnowledgeLibrary.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing bundled knowledge: " + name);
            return new KnowledgeDocument("maicraft://knowledge/" + path, "knowledge." + path, title, description,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read bundled knowledge", failure);
        }
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException(key + " must be a string");
        return object.get(key).getAsString();
    }

    private static String required(JsonObject object, String key) {
        String value = string(object, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}

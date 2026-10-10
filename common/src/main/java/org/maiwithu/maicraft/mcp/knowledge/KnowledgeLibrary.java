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
import java.util.Optional;
import java.util.stream.Collectors;
import java.security.NoSuchAlgorithmException;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

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
     * 游戏机制常识条目：slug、标题、一句话说明、检索词。一句话说明给目录看；检索词带中英说法与注册 ID，
     * 搜索按标题、说明和检索词匹配，不读正文。
     */
    static final String[][] GAME_MECHANICS = {
            {"gravity-blocks", "重力方块与塌落", "挖掉砾石、沙子的下方会整列塌落，地形和掉落物的位置随之改变",
                    "gravel sand 砾石 沙子 落沙 gravity 塌方 塌落 掩埋掉落物 砸伤 窒息"},
            {"fluid-flow", "流体流动与灌满", "挖开的空间会被水或岩浆灌满；倒水自救前先看流向",
                    "water lava 水 岩浆 流动 灌满 倒水 舀水 桶 bucket 自救 黑曜石 圆石 隧道进水"},
            {"item-drops", "掉落物", "拾取半径约 1 格、会漂走、5 分钟后消失；“方块碎了没进包”的排查顺序",
                    "item entity 掉落物 拾取 拾取半径 漂走 消失 despawn 没进背包 背包满 拾取冷却 捡东西"},
            {"drop-rates", "关键掉率与方差", "燧石 10% 掉率是常态，连挖几块不掉不是故障",
                    "flint 燧石 10% 掉率 方差 fortune 时运 精准采集 战利品表 掉落概率 苹果 树苗 种子"},
            {"tool-tiers", "工具等级与挖掘资格", "等级不够挖不动或不掉落；黑曜石要钻石镐",
                    "tool tier 工具等级 镐 pickaxe 黑曜石 obsidian 远古残骸 挖不动 不掉落 挖掘资格 深板岩"},
            {"ore-heights", "矿物生成高度", "各种矿在哪一层最多；采掘只在角色附近找，先到对应高度再要",
                    "ore 矿 矿石 找矿 高度 生成 分布 层 钻石 diamond 铁 iron 煤 coal 铜 copper 金 gold 红石 redstone 青金石 lapis 绿宝石 emerald 深板岩 deepslate 粗铁 raw_iron 远古残骸"},
            {"food", "食物与饥饿", "饥饿与回血规则、食物从哪来、耕地保湿",
                    "food eat 食物 吃 饿 饥饿 饱和度 saturation 回血 饿死 打猎 狩猎 生肉 熟食 烧熟 耕种 耕地 锄头 种植 小麦 胡萝卜 马铃薯 甜菜 面包 骨粉"},
            {"sleep-night", "睡眠与夜晚", "黑暗处刷怪、床跳夜与重设重生点、同色羊毛、3 天不睡刷幻翼",
                    "sleep bed 睡觉 床 幻翼 phantom 夜晚 晚上 night 刷怪 spawn 羊毛 wool 剪羊毛 shears 染料 dye 同色 重生点 respawn 跳夜 封顶"},
            {"tunneling", "往下挖与地下通行", "不要垂直往下挖；阶梯式下降和两格高的通道更安全",
                    "dig down staircase tunnel 往下挖 竖井 阶梯 下矿 通道 两格高 矿洞 迷路 入口"},
            {"tick-rate", "世界刻速与失焦", "游戏窗口失焦时世界可能停住或变慢；任务变慢先想到它",
                    "tps tick 刻速 刻率 失焦 focus 暂停 pause 限速 throttle 变慢 慢放 卡死 任务变慢"},
            {"lighting", "照明与刷怪", "方块光照为 0 才刷敌对生物；火把 = 煤或木炭 + 木棍",
                    "lighting torch 火把 照明 光照 亮 刷怪 spawn 黑暗 洞穴 地下 煤 木炭 木棍 灯笼 萤石"},
    };

    private final List<KnowledgeSource> sources;
    private final Map<String, KnowledgeDocument> builtins;
    // 内置资料的目录条目：比正文多一份检索词，目录与搜索都用它。
    private final Map<String, KnowledgeDocument.Entry> builtinEntries;

    /** 只有一个登记来源的知识库。 */
    public KnowledgeLibrary(KnowledgeSource source) {
        this(List.of(source));
    }

    /** 内置资料加上登记的来源：目录合并、正文按来源先后找；联动模组交来的资料来源也从这里进。 */
    public KnowledgeLibrary(List<KnowledgeSource> sources) {
        this.sources = List.copyOf(sources);
        // 内置资料随包发布；缺一篇就在启动时失败，不把空正文当知识交给模型。
        Map<String, KnowledgeDocument> docs = new LinkedHashMap<>();
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        KnowledgeDocument index = load("index", "知识索引", "按需发现游戏机制常识、已登记的资料来源和检索方式。");
        docs.put(INDEX, index);
        entries.put(INDEX, index.entry());
        for (String[] row : GAME_MECHANICS) {
            KnowledgeDocument document = load("game_mechanics/" + row[0], row[1], row[2]);
            docs.put(document.uri(), document);
            entries.put(document.uri(), new KnowledgeDocument.Entry(
                    document.uri(), document.name(), document.title(), document.description(), row[3]));
        }
        builtins = Map.copyOf(docs);
        builtinEntries = Map.copyOf(entries);
    }

    /** 没有任何登记来源时的空知识库：只剩内置资料，来源状态如实标为不可用。 */
    public static KnowledgeLibrary offline() {
        return new KnowledgeLibrary(List.of());
    }

    /** 知识请求的四类操作：列目录、读模板、读正文、搜索；未知操作明确报错。 */
    public JsonObject request(JsonObject request) {
        return switch (request.get("action").getAsString()) {
            case "list" -> list(string(request, "cursor"));
            case "templates" -> {
                if (string(request, "cursor") != null) throw new IllegalArgumentException("Invalid template cursor");
                JsonObject result = new JsonObject();
                JsonArray templates = new JsonArray();
                for (KnowledgeSource source : sources) templates.addAll(source.templates());
                result.add("resourceTemplates", templates);
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
        for (KnowledgeSource source : sources) {
            if (document != null) break;
            document = source.read(uri);
        }
        // 目录之外的地址不偷换成相近正文；调用方须重新发现目录。
        if (document == null) throw KnowledgeException.missing(uri);
        return document;
    }

    /** 给 lookup 的资料目录：内置常识与登记来源的全部条目，按地址排序；索引那一篇不列，目录本身就是索引。 */
    public List<KnowledgeDocument.Entry> listing() {
        return catalog().stream().filter(entry -> !entry.uri().equals(INDEX)).toList();
    }

    /**
     * 按 lookup 的 id 找一篇：给完整地址就按地址读；只给末段名（例如 food）时找末段对得上的那一篇，
     * 和查能力时可以省掉 maicraft: 前缀一样。找不到、或末段名对上不止一篇时返回空，不拿相近的顶替。
     */
    public Optional<KnowledgeDocument> find(String id) {
        String wanted = id.strip();
        if (!wanted.contains("://")) {
            String suffix = "/" + wanted.toLowerCase(Locale.ROOT);
            List<String> uris = catalog().stream().map(KnowledgeDocument.Entry::uri)
                    .filter(uri -> uri.toLowerCase(Locale.ROOT).endsWith(suffix)).toList();
            if (uris.size() != 1) return Optional.empty();
            wanted = uris.getFirst();
        }
        try {
            return Optional.of(read(wanted));
        } catch (IllegalArgumentException missing) {
            return Optional.empty();
        }
    }

    /**
     * 各登记来源此刻的现状，一个来源一句（例如"Create 思索：可用，312 个场景"）；资料目录最后附上，
     * 让 LLM 知道这个实例能查到什么、为什么查不到。没登记的来源不出现：没列出来的就是这个实例没有。
     */
    public List<String> sourceStatuses() {
        return sources.stream().map(KnowledgeSource::status).filter(status -> status != null && !status.isBlank()).toList();
    }

    /** 各来源里跟这件物品或方块有关的条目，按来源先后、同一地址只出一次；物品资料页的"相关资料"就是它。 */
    public List<KnowledgeDocument.Entry> about(String registryId) {
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        for (KnowledgeSource source : sources) {
            source.entriesAbout(registryId).forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        }
        return List.copyOf(entries.values());
    }

    /**
     * 给 lookup 的关键词搜索，只比较目录元数据（标题、一句话说明、检索词），不读正文。
     * 搜的是内置常识加各来源的全部搜索候选，不只是目录那几行：思索场景、任务书条目都在这里找得到。
     * LLM 常把关键词写成一句不带空格的话（"钻石在哪一层"），所以两头都比：问句里的词出现在条目里，
     * 或条目的标题、检索词出现在问句里，都算命中；命中越多排得越前，同分按地址排。
     */
    public List<KnowledgeDocument.Entry> matching(String query) {
        String asked = query.strip().toLowerCase(Locale.ROOT);
        List<String> terms = Arrays.stream(asked.split("[\\s,，、;；。?？!！]+")).filter(term -> !term.isEmpty()).toList();
        Map<KnowledgeDocument.Entry, Integer> scores = new LinkedHashMap<>();
        for (KnowledgeDocument.Entry entry : searchable(asked)) {
            String searchable = entry.searchable();
            int score = 0;
            for (String term : terms) {
                if (searchable.contains(term)) score += 2;
            }
            for (String word : (entry.title() + " " + entry.keywords()).toLowerCase(Locale.ROOT).split("\\s+")) {
                if (!word.isEmpty() && asked.contains(word)) score++;
            }
            if (score > 0) scores.put(entry, score);
        }
        return scores.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<KnowledgeDocument.Entry, Integer> hit) -> -hit.getValue())
                        .thenComparing(hit -> hit.getKey().uri()))
                .map(Map.Entry::getKey)
                .toList();
    }

    // 搜索候选：内置常识（索引那一篇除外）加各来源按查询词给出的候选，同一地址只算一次。
    private List<KnowledgeDocument.Entry> searchable(String query) {
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        builtinEntries.values().stream().filter(entry -> !entry.uri().equals(INDEX))
                .sorted(Comparator.comparing(KnowledgeDocument.Entry::uri))
                .forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        for (KnowledgeSource source : sources) {
            source.searchCandidates(query).forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        }
        return List.copyOf(entries.values());
    }

    /** 各登记来源的状态并成一句；一个来源都没有时如实说不可用，空结果不冒充"没有这回事"。 */
    private String providerStatus() {
        if (sources.isEmpty()) return "unavailable";
        return sources.stream().map(KnowledgeSource::status).collect(Collectors.joining("; "));
    }

    private List<KnowledgeDocument.Entry> catalog() {
        Map<String, KnowledgeDocument.Entry> entries = new LinkedHashMap<>();
        entries.putAll(builtinEntries);
        for (KnowledgeSource source : sources) {
            source.entries().forEach(entry -> entries.putIfAbsent(entry.uri(), entry));
        }
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
        candidates.putAll(builtinEntries);
        for (KnowledgeSource source : sources) {
            source.searchCandidates(cleaned).forEach(entry -> candidates.putIfAbsent(entry.uri(), entry));
        }
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
        result.addProperty("provider_status", providerStatus());
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
        candidates.putAll(builtinEntries);
        // 候选召回也必须允许错字；否则目标在精确过滤阶段已经消失，后续排序无法补救。
        for (KnowledgeSource source : sources) {
            source.searchCandidates(query, true).forEach(entry -> candidates.putIfAbsent(entry.uri(), entry));
        }
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
        result.addProperty("provider_status", providerStatus());
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

// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonArray;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/** 知识库按元数据发现、按需读取：内置资料完整可读，目录分页绑定版本，搜索不展开正文。 */
class KnowledgeLibraryTest {
    @Test
    void bundledIndexAndGameMechanicsPagesAreReadable() {
        KnowledgeLibrary library = KnowledgeLibrary.offline();
        assertEquals("知识索引", library.read(KnowledgeLibrary.INDEX).title());
        for (String[] entry : KnowledgeLibrary.GAME_MECHANICS) {
            KnowledgeDocument document = library.read(KnowledgeLibrary.GAME_MECHANICS_PREFIX + entry[0]);
            assertEquals(entry[1], document.title());
            assertTrue(document.text().contains("#"), "每篇常识都有正文");
        }
        for (String[] entry : KnowledgeLibrary.BUILDING) {
            KnowledgeDocument document = library.read(KnowledgeLibrary.BUILDING_PREFIX + entry[0]);
            assertEquals(entry[1], document.title());
            assertTrue(document.text().contains("#"), "每篇建筑资料都有正文");
        }
        assertTrue(library.find("building/design").isPresent(), "lookup 给 building/design 这种末段写法要找得到");
        assertThrows(KnowledgeException.class, () -> library.read("maicraft://knowledge/no-such-page"));
    }

    @Test
    void catalogListsMetadataAndPaginatesWithAVersionBoundCursor() {
        KnowledgeLibrary library = KnowledgeLibrary.offline();
        JsonObject firstPage = library.request(request("list", null));
        assertTrue(firstPage.getAsJsonArray("resources").size() > 0);
        // 目录里还有索引那一篇，所以总数比 listing 多一；翻到第二页时剩下的都在那一页。
        int total = library.listing().size() + 1;
        assertEquals(Math.min(total, KnowledgeLibraryPage.PAGE_SIZE), firstPage.getAsJsonArray("resources").size());
        if (!firstPage.has("nextCursor")) return;
        JsonObject secondPage = library.request(request("list", firstPage.get("nextCursor").getAsString()));
        assertEquals(Math.min(total - KnowledgeLibraryPage.PAGE_SIZE, KnowledgeLibraryPage.PAGE_SIZE),
                secondPage.getAsJsonArray("resources").size());
        // 目录变了旧游标明确报错：先记下第一页游标，再造一个不同目录的库。
        String cursor = firstPage.get("nextCursor").getAsString();
        KnowledgeLibrary changed = new KnowledgeLibrary(new SingleEntrySource());
        assertThrows(IllegalArgumentException.class, () -> changed.request(request("list", cursor)));
    }

    @Test
    void searchMatchesMetadataOnlyAndReportsTruncation() {
        KnowledgeLibrary library = KnowledgeLibrary.offline();
        JsonObject hits = library.request(request("search", "塌落"));
        assertTrue(hits.getAsJsonArray("resources").size() >= 1, "内置常识能按关键词找到");
        assertFalse(hits.get("content_loaded").getAsBoolean(), "搜索只比较元数据，不展开正文");
        assertEquals(1, limitedSearch(library, "塌落", 1).size(), "limit 生效");
        assertThrows(IllegalArgumentException.class, () -> limitedSearch(library, "塌落", 0), "limit 越界明确报错");
    }

    private static JsonArray limitedSearch(KnowledgeLibrary library, String query, int limit) {
        JsonObject request = new JsonObject();
        request.addProperty("action", "search");
        request.addProperty("query", query);
        request.addProperty("limit", limit);
        return library.request(request).getAsJsonArray("resources");
    }

    @Test
    void registeredSourcesAppearInCatalogAndSearch() {
        KnowledgeLibrary library = new KnowledgeLibrary(new SingleEntrySource());
        JsonObject hits = library.request(request("search", "示例"));
        assertEquals(1, hits.getAsJsonArray("resources").size());
        assertEquals("registered", hits.get("provider_status").getAsString());
    }

    @Test
    void catalogListsSourceIndexesWhileSearchCoversEveryEntry() {
        KnowledgeLibrary library = new KnowledgeLibrary(new IndexedSource());

        List<String> catalog = library.listing().stream().map(KnowledgeDocument.Entry::uri).toList();
        assertTrue(catalog.contains(IndexedSource.INDEX), "目录里有来源的索引");
        assertFalse(catalog.contains(IndexedSource.SCENE), "场景不进目录");
        assertEquals(List.of(IndexedSource.SCENE),
                library.matching("搅拌器").stream().map(KnowledgeDocument.Entry::uri).toList(), "搜索找得到场景");
    }

    @Test
    void relatedEntriesAndStatusesAreGatheredPerSource() {
        KnowledgeLibrary library = new KnowledgeLibrary(List.of(new IndexedSource(), new SingleEntrySource()));

        assertEquals(List.of(IndexedSource.SCENE),
                library.about("create:mechanical_mixer").stream().map(KnowledgeDocument.Entry::uri).toList());
        assertTrue(library.about("minecraft:stone").isEmpty());
        assertEquals(List.of("思索：可用，1 个场景", "registered"), library.sourceStatuses());
    }

    /** 目录只交一行索引、搜索与相关资料才给出场景的来源，像思索那样。 */
    private static final class IndexedSource implements KnowledgeSource {
        static final String INDEX = "maicraft://knowledge/ponder/index";
        static final String SCENE = "maicraft://knowledge/ponder/create/mechanical_mixer/1";
        private static final KnowledgeDocument.Entry SCENE_ENTRY = new KnowledgeDocument.Entry(SCENE, "scene",
                "动力搅拌器 · 场景 1", "思索场景", "create:mechanical_mixer 动力搅拌器 搅拌器");

        @Override public List<KnowledgeDocument.Entry> entries() {
            return List.of(new KnowledgeDocument.Entry(INDEX, "index", "思索索引", "全部有思索的物品", "思索 ponder"));
        }

        @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
            return List.of(SCENE_ENTRY);
        }

        @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
            return registryId.equals("create:mechanical_mixer") ? List.of(SCENE_ENTRY) : List.of();
        }

        @Override public KnowledgeDocument read(String uri) {
            return null;
        }

        @Override public String status() { return "思索：可用，1 个场景"; }
    }

    private static JsonObject request(String action, String value) {
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        if ("list".equals(action) && value != null) request.addProperty("cursor", value);
        if ("search".equals(action) && value != null) request.addProperty("query", value);
        return request;
    }

    /** 只登记一条资料的来源，用于核对目录合并与来源状态。 */
    private static final class SingleEntrySource implements KnowledgeSource {
        @Override public List<KnowledgeDocument.Entry> entries() {
            return List.of(new KnowledgeDocument.Entry("maicraft://knowledge/example", "example",
                    "示例条目", "搜索用示例资料", "示例 example"));
        }

        @Override public KnowledgeDocument read(String uri) {
            return "maicraft://knowledge/example".equals(uri)
                    ? new KnowledgeDocument(uri, "example", "示例条目", "搜索用示例资料", "示例正文")
                    : null;
        }

        @Override public String status() { return "registered"; }
    }

    /** 目录分页大小的镜像，避免测试直接依赖私有常量。 */
    private static final class KnowledgeLibraryPage {
        static final int PAGE_SIZE = 16;
    }
}

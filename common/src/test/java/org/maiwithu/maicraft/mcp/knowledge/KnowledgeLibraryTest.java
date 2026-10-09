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
        assertThrows(KnowledgeException.class, () -> library.read("maicraft://knowledge/no-such-page"));
    }

    @Test
    void catalogListsMetadataAndPaginatesWithAVersionBoundCursor() {
        KnowledgeLibrary library = KnowledgeLibrary.offline();
        JsonObject firstPage = library.request(request("list", null));
        assertTrue(firstPage.getAsJsonArray("resources").size() > 0);
        int total = firstPage.getAsJsonArray("resources").size();
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

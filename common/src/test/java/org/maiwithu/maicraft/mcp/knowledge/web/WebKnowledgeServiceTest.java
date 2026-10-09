// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletionStage;

/** 用已知网页回放核对查阅流程：本地标题匹配、条目读取、错误分包；不触碰真实百科，也不启动游戏。 */
class WebKnowledgeServiceTest {
    private static final String ROBOTS = "User-agent: *\nAllow: /\n";
    private static final String SITEMAP_INDEX =
            "<sitemapindex><sitemap><loc>https://minecraft.wiki/images/sitemaps/NS_0-0.xml</loc></sitemap></sitemapindex>";
    private static final String SITEMAP_PART =
            "<urlset><url><loc>https://minecraft.wiki/w/Stone</loc></url>"
                    + "<url><loc>https://zh.minecraft.wiki/w/%E7%9F%B3%E5%A4%B4</loc></url></urlset>";
    private static final String ARTICLE = "<!doctype html><html><head><title>Stone</title></head><body>"
            + "<script>var config = {\"wgRevisionId\": 12345};</script>"
            + "<h1 id=\"firstHeading\">Stone</h1>"
            + "<div id=\"mw-content-text\"><div class=\"mw-parser-output\">"
            + "<p>Stone is a block.</p><table><tr><td>hardness</td><td>1.5</td></tr></table>"
            + "</div></div></body></html>";

    @Test
    void argumentsMustPickExactlyOneOfQueryOrUrl() {
        JsonObject both = new JsonObject();
        both.addProperty("query", "石头");
        both.addProperty("url", "https://minecraft.wiki/w/Stone");
        assertThrows(IllegalArgumentException.class, () -> WebKnowledgeService.validate(both));
        JsonObject neither = new JsonObject();
        assertThrows(IllegalArgumentException.class, () -> WebKnowledgeService.validate(neither));
        JsonObject urlWithSource = new JsonObject();
        urlWithSource.addProperty("url", "https://minecraft.wiki/w/Stone");
        urlWithSource.addProperty("source", "minecraft_wiki");
        assertThrows(IllegalArgumentException.class, () -> WebKnowledgeService.validate(urlWithSource),
                "读指定条目时来源与语言由 URL 决定");
    }

    @Test
    void searchMatchesTitlesLocallyThenReadsTheArticle() throws Exception {
        FakeFetch fetch = new FakeFetch();
        try (WebKnowledgeService service = service(fetch)) {
            JsonObject args = new JsonObject();
            args.addProperty("query", "stone");
            args.addProperty("language", "en");
            JsonObject result = complete(service.request(args));
            assertEquals("ok", result.get("status").getAsString());
            assertEquals(1, result.getAsJsonArray("documents").size(), "本地标题匹配命中的条目被读取");
            JsonObject document = result.getAsJsonArray("documents").get(0).getAsJsonObject();
            assertEquals("Stone", document.get("title").getAsString());
            assertEquals("12345", document.get("revision_id").getAsString(), "修订号来自页面自身");
            assertTrue(document.get("text").getAsString().contains("Stone is a block."));
            assertTrue(document.get("text").getAsString().contains("| hardness | 1.5 |"),
                    "表格行列在正文中保留");
            assertEquals("https://minecraft.wiki/w/Stone", document.get("url").getAsString());
        }
    }

    @Test
    void failedReadsAreReportedPerArticleWithoutMaskingOtherPages() throws Exception {
        FakeFetch fetch = new FakeFetch();
        // 条目正文缺失（改版或验证页）：检索仍完成，失败在 errors 里单独说明。
        fetch.pages.put(URI.create("https://minecraft.wiki/w/Stone"),
                "<!doctype html><html><head><title>Challenge</title></head><body>captcha</body></html>");
        try (WebKnowledgeService service = service(fetch)) {
            JsonObject args = new JsonObject();
            args.addProperty("query", "stone");
            args.addProperty("language", "en");
            JsonObject result = complete(service.request(args));
            assertEquals("unavailable", result.get("status").getAsString());
            assertEquals(0, result.getAsJsonArray("documents").size());
            assertEquals("layout_changed",
                    result.getAsJsonArray("errors").get(0).getAsJsonObject().get("code").getAsString());
        }
    }

    private static WebKnowledgeService service(WebFetch fetch) {
        return new WebKnowledgeService(fetch, new KnowledgeEnvironment());
    }

    private static JsonObject complete(CompletionStage<JsonElement> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS).getAsJsonObject();
    }

    /** 按地址返回固定网页的传输替身；robots 与站点地图都走同一条真实校验路径。 */
    private static final class FakeFetch implements WebFetch {
        private final Map<URI, String> pages = new HashMap<>();

        FakeFetch() {
            pages.put(URI.create("https://minecraft.wiki/robots.txt"), ROBOTS);
            pages.put(URI.create("https://zh.minecraft.wiki/robots.txt"), ROBOTS);
            pages.put(URI.create("https://minecraft.wiki/images/sitemaps/index.xml"), SITEMAP_INDEX);
            pages.put(URI.create("https://minecraft.wiki/images/sitemaps/NS_0-0.xml"), SITEMAP_PART);
            pages.put(URI.create("https://minecraft.wiki/w/Stone"), ARTICLE);
        }

        @Override public Page get(URI uri, long deadlineNanos) throws IOException {
            String body = pages.get(uri);
            if (body == null) throw new IOException("no replay page for " + uri);
            return new Page(uri, body, Instant.EPOCH, "", false);
        }
    }
}

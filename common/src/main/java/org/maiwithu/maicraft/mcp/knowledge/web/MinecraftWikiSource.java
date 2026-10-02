// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

/** Wiki 从公开站点地图匹配标题，再读正常条目；不调用站点禁止自动抓取的 API 或搜索页。 */
final class MinecraftWikiSource {
    record Search(List<URI> articles, int total, int indexedArticles, String indexFetchedAt) {}
    private static final Pattern REVISION = Pattern.compile("\"wgRevisionId\"\\s*:\\s*(\\d+)");
    private final WebFetch fetch;
    private final WikiTitleIndex titles;

    MinecraftWikiSource(WebFetch fetch) { this.fetch = fetch; titles = new WikiTitleIndex(fetch); }

    Search search(String query, String language, int limit, long deadline) throws IOException, InterruptedException {
        return titles.search(query, language, limit, deadline);
    }

    JsonObject read(URI article, long deadline) throws IOException, InterruptedException {
        WebFetch.Page page = fetch.get(article, deadline);
        var document = Jsoup.parse(page.body(), page.uri().toString());
        var body = document.selectFirst("#mw-content-text .mw-parser-output");
        var heading = document.selectFirst("#firstHeading");
        if (body == null || heading == null)
            throw new WebKnowledgeException("layout_changed", "Wiki article body was not found; a challenge or changed layout is not game knowledge");
        JsonObject result = EncyclopediaHtml.document(page, "minecraft_wiki", heading.text(), body);
        // 修订号仅从该页面自身的数据读取；缺失时保持未知，不把抓取时间推断成文章版本。
        var revision = REVISION.matcher(page.body());
        if (revision.find()) result.addProperty("revision_id", revision.group(1));
        else result.add("revision_id", null);
        result.addProperty("attribution_url", page.uri() + "?action=history");
        result.addProperty("license_note", "Minecraft Wiki normally uses CC BY-NC-SA 3.0; check page-specific notices and linked license before reuse");
        return result;
    }
}

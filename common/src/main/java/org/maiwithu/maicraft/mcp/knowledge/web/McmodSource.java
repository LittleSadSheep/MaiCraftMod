// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** MC百科按指定条目读取物品、模组和教程；不访问 robots 明确禁止的站内搜索。 */
final class McmodSource {
    private final WebFetch fetch;
    McmodSource(WebFetch fetch) { this.fetch = fetch; }

    JsonObject read(URI uri, long deadline) throws IOException, InterruptedException {
        WebFetch.Page page = fetch.get(uri, deadline);
        var document = Jsoup.parse(page.body(), page.uri().toString());
        String selector = uri.getPath().startsWith("/class/") ? ".class-text .text-area.common-text"
                : uri.getPath().startsWith("/post/") ? ".post-content" : ".item-content";
        var contents = document.select(selector);
        if (contents.isEmpty()) throw new WebKnowledgeException("layout_changed", "MC百科 article body was not found; the layout may have changed or access was challenged");
        Element body = new Element("article"); body.setBaseUri(page.uri().toString());
        // 物品正文与原生表格一起交付，保留旧版配方的移除备注；不把图片排列改写成可执行配方。
        for (Element part : contents) body.appendChild(part.clone());
        if (uri.getPath().startsWith("/item/"))
            for (Element part : document.select(".item-info-table, #item-table-area-out")) body.appendChild(part.clone());
        JsonObject result = EncyclopediaHtml.document(page, "mcmod", document.title(), body);
        result.addProperty("attribution_url", page.uri().toString());
        result.addProperty("license_note", "MC百科 public collaborative content normally uses CC BY-NC-SA 3.0 except where otherwise stated; see the original page");
        result.addProperty("site_search_available", false);
        return result;
    }
}
